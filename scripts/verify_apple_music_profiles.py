#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Apple Music Hook 档案与语义契约离线 APK 校验工具。
Copyright 2026 juren233
Licensed under the Apache License, Version 2.0
"""

import argparse
import json
import os
import struct
import sys
import zipfile
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set, Tuple


# --- 轻量纯 Python DEX 解析器 ---

def uleb128_decode(data: bytes, offset: int) -> Tuple[int, int]:
    result = 0
    shift = 0
    read_bytes = 0
    while True:
        byte = data[offset + read_bytes]
        read_bytes += 1
        result |= (byte & 0x7F) << shift
        if (byte & 0x80) == 0:
            break
        shift += 7
    return result, read_bytes


def sleb128_decode(data: bytes, offset: int) -> Tuple[int, int]:
    result = 0
    shift = 0
    read_bytes = 0
    while True:
        byte = data[offset + read_bytes]
        read_bytes += 1
        result |= (byte & 0x7F) << shift
        shift += 7
        if (byte & 0x80) == 0:
            if (shift < 32) and (byte & 0x40) != 0:
                result |= - (1 << shift)
            break
    return result, read_bytes


@dataclass
class DexField:
    name: str
    type_descriptor: str
    access_flags: int

    @property
    def is_static(self) -> bool:
        return bool(self.access_flags & 0x0008)


@dataclass
class DexMethod:
    name: str
    shorty: str
    return_type: str
    param_types: List[str]
    access_flags: int

    @property
    def is_static(self) -> bool:
        return bool(self.access_flags & 0x0008)

    @property
    def is_synthetic(self) -> bool:
        return bool(self.access_flags & 0x1000)

    @property
    def is_bridge(self) -> bool:
        return bool(self.access_flags & 0x0040)


@dataclass
class DexClass:
    descriptor: str
    superclass_descriptor: Optional[str]
    interfaces: List[str]
    access_flags: int
    fields: List[DexField] = field(default_factory=list)
    methods: List[DexMethod] = field(default_factory=list)

    @property
    def binary_name(self) -> str:
        # Lcom/apple/android/music/foo; -> com.apple.android.music.foo
        if self.descriptor.startswith('L') and self.descriptor.endswith(';'):
            return self.descriptor[1:-1].replace('/', '.')
        return self.descriptor


class DexParser:
    def __init__(self, data: bytes):
        self.data = data
        self.classes: Dict[str, DexClass] = {}
        self.parse_header()

    def parse_header(self):
        if len(self.data) < 112 or self.data[:4] != b'dex\n':
            return
        (
            string_ids_size, string_ids_off,
            type_ids_size, type_ids_off,
            proto_ids_size, proto_ids_off,
            field_ids_size, field_ids_off,
            method_ids_size, method_ids_off,
            class_defs_size, class_defs_off,
        ) = struct.unpack_from('<IIIIIIIIIIII', self.data, 56)

        # 1. string_ids
        string_offsets = struct.unpack_from(f'<{string_ids_size}I', self.data, string_ids_off)
        strings = []
        for off in string_offsets:
            length, read = uleb128_decode(self.data, off)
            str_data = self.data[off + read: off + read + length * 3]
            end = str_data.find(b'\x00')
            if end != -1:
                str_data = str_data[:end]
            strings.append(str_data.decode('utf-8', errors='replace'))

        # 2. type_ids
        type_str_ids = struct.unpack_from(f'<{type_ids_size}I', self.data, type_ids_off)
        types = [strings[sid] for sid in type_str_ids]

        # 3. proto_ids
        protos = []
        for i in range(proto_ids_size):
            shorty_idx, return_type_idx, parameters_off = struct.unpack_from('<III', self.data, proto_ids_off + i * 12)
            param_list = []
            if parameters_off != 0:
                param_size = struct.unpack_from('<I', self.data, parameters_off)[0]
                type_indices = struct.unpack_from(f'<{param_size}H', self.data, parameters_off + 4)
                param_list = [types[tidx] for tidx in type_indices]
            protos.append((strings[shorty_idx], types[return_type_idx], param_list))

        # 4. field_ids
        fields_info = []
        for i in range(field_ids_size):
            class_idx, type_idx, name_idx = struct.unpack_from('<HHI', self.data, field_ids_off + i * 8)
            fields_info.append((types[class_idx], types[type_idx], strings[name_idx]))

        # 5. method_ids
        methods_info = []
        for i in range(method_ids_size):
            class_idx, proto_idx, name_idx = struct.unpack_from('<HHI', self.data, method_ids_off + i * 8)
            shorty, ret_type, params = protos[proto_idx]
            methods_info.append((types[class_idx], strings[name_idx], shorty, ret_type, params))

        # 6. class_defs
        for i in range(class_defs_size):
            (
                class_idx, access_flags, superclass_idx,
                interfaces_off, source_file_idx, annotations_off,
                class_data_off, static_values_off
            ) = struct.unpack_from('<IIIIIIII', self.data, class_defs_off + i * 32)

            class_desc = types[class_idx]
            super_desc = types[superclass_idx] if superclass_idx != 0xFFFFFFFF else None
            interfaces = []
            if interfaces_off != 0:
                iface_size = struct.unpack_from('<I', self.data, interfaces_off)[0]
                iface_indices = struct.unpack_from(f'<{iface_size}H', self.data, interfaces_off + 4)
                interfaces = [types[tidx] for tidx in iface_indices]

            dex_class = DexClass(
                descriptor=class_desc,
                superclass_descriptor=super_desc,
                interfaces=interfaces,
                access_flags=access_flags,
            )

            if class_data_off != 0:
                cur = class_data_off
                static_fields_size, read = uleb128_decode(self.data, cur)
                cur += read
                instance_fields_size, read = uleb128_decode(self.data, cur)
                cur += read
                direct_methods_size, read = uleb128_decode(self.data, cur)
                cur += read
                virtual_methods_size, read = uleb128_decode(self.data, cur)
                cur += read

                # Static fields
                field_idx = 0
                for _ in range(static_fields_size):
                    field_idx_diff, read = uleb128_decode(self.data, cur)
                    cur += read
                    field_idx += field_idx_diff
                    f_access, read = uleb128_decode(self.data, cur)
                    cur += read
                    _, f_type, f_name = fields_info[field_idx]
                    dex_class.fields.append(DexField(name=f_name, type_descriptor=f_type, access_flags=f_access))

                # Instance fields
                field_idx = 0
                for _ in range(instance_fields_size):
                    field_idx_diff, read = uleb128_decode(self.data, cur)
                    cur += read
                    field_idx += field_idx_diff
                    f_access, read = uleb128_decode(self.data, cur)
                    cur += read
                    _, f_type, f_name = fields_info[field_idx]
                    dex_class.fields.append(DexField(name=f_name, type_descriptor=f_type, access_flags=f_access))

                # Direct methods
                method_idx = 0
                for _ in range(direct_methods_size):
                    method_idx_diff, read = uleb128_decode(self.data, cur)
                    cur += read
                    method_idx += method_idx_diff
                    m_access, read = uleb128_decode(self.data, cur)
                    cur += read
                    code_off, read = uleb128_decode(self.data, cur)
                    cur += read
                    _, m_name, shorty, ret_type, params = methods_info[method_idx]
                    dex_class.methods.append(DexMethod(
                        name=m_name, shorty=shorty, return_type=ret_type,
                        param_types=params, access_flags=m_access,
                    ))

                # Virtual methods
                method_idx = 0
                for _ in range(virtual_methods_size):
                    method_idx_diff, read = uleb128_decode(self.data, cur)
                    cur += read
                    method_idx += method_idx_diff
                    m_access, read = uleb128_decode(self.data, cur)
                    cur += read
                    code_off, read = uleb128_decode(self.data, cur)
                    cur += read
                    _, m_name, shorty, ret_type, params = methods_info[method_idx]
                    dex_class.methods.append(DexMethod(
                        name=m_name, shorty=shorty, return_type=ret_type,
                        param_types=params, access_flags=m_access,
                    ))

            self.classes[dex_class.binary_name] = dex_class


class ApkDexContext:
    def __init__(self, apk_path: str):
        self.apk_path = apk_path
        self.classes: Dict[str, DexClass] = {}
        self.load_dex_files()

    def load_dex_files(self):
        with zipfile.ZipFile(self.apk_path, 'r') as zf:
            for entry in zf.namelist():
                if entry.startswith('classes') and entry.endswith('.dex'):
                    data = zf.read(entry)
                    parser = DexParser(data)
                    self.classes.update(parser.classes)

    def find_class(self, class_name: str) -> Optional[DexClass]:
        return self.classes.get(class_name)


# --- 契约检查器与版本档案定义 ---

KNOWN_TARGETS_6_5_2 = {
    "LYRICS_SOURCE_MENU_CLICK_LISTENER": {
        "class": "com.apple.android.music.player.fragment.d0",
        "method": "onClick",
        "params": ["Landroid/view/View;"],
        "contract": "require_field_PlayerLyricsViewFragment",
    },
    "COMPOSE_NEVER_EQUAL_POLICY": {
        "class": "z0.s0",
        "contract": "require_static_self_singleton",
    },
    "COMPOSE_OBSERVE_AS_STATE": {
        "class": "C1.w",
        "method": "e",
        "params": ["Landroidx/lifecycle/G;", "Lz0/n;"],
        "return": "Lz0/p0;",
        "contract": "require_live_data_param",
    },
    "APPLE_TEXT_STYLE_UTILS": {
        "class": "com.apple.android.music.utils.j1$a",
        "method": "j",
        "params": [
            "Landroid/content/Context;",
            "Landroid/util/AttributeSet;",
            "LP6/a;",
            "LLg/a;",
        ],
        "return": "Landroid/graphics/Typeface;",
    },
    "COMPOSE_TEXT_LAYOUT_PRIMARY": {
        "class": "z1.q",
        "method": "<init>",
        "params": [
            "Ljava/lang/CharSequence;",
            "F",
            "Landroid/text/TextPaint;",
            "I",
            "Landroid/text/TextUtils$TruncateAt;",
            "I",
            "Z",
            "I",
            "I",
            "I",
            "I",
            "I",
            "I",
            "Lz1/i;",
        ],
        "return": "V",
    },
    "COMPOSE_TEXT_LAYOUT_INTRINSICS": {
        "class": "z1.i",
        "method": "<init>",
        "params": ["Ljava/lang/CharSequence;", "Landroid/text/TextPaint;", "I"],
        "return": "V",
    },
    "LISTEN_NOW_MODEL_BUILDER": {
        "class": "com.apple.android.music.listennow.ListenNowEpoxyController",
        "method": "buildStandardSwoosh$lambda$35",
        "param_count": 5,
        "contract": "require_static_model_builder",
    },
    "LISTEN_NOW_ARTWORK_RESOLVER": {
        "class": "com.apple.android.music.common.L",
        "method": "t",
        "params": ["Lcom/apple/android/music/model/CollectionItemView;"],
        "return": "V",
    },
    "LIBRARY_EPOXY_BUILD": {
        "class": "com.apple.android.music.library2.LibraryMainContentEpoxyController",
        "method": "buildModels",
        "param_count": 5,
        "contract": "require_library_epoxy_build",
    },
    "IN_APP_GLOBAL_METADATA_DISPATCHER": {
        "class": "com.apple.android.music.player.e",
        "method": "onMediaMetadataChanged",
        "param_count": 1,
    },
    "IN_APP_ACTION_SHEET_BINDING": {
        "class": "l7.e8",
        "method": "l",
        "contract": "require_field_CollectionItemView",
    },
}


def to_dex_type(type_name: str) -> str:
    primitive_map = {
        "void": "V", "boolean": "Z", "byte": "B", "char": "C",
        "short": "S", "int": "I", "long": "J", "float": "F", "double": "D",
    }
    if type_name in primitive_map:
        return primitive_map[type_name]
    if type_name.startswith("["):
        return type_name.replace('.', '/')
    return f"L{type_name.replace('.', '/')};"


def check_contract(dex_ctx: ApkDexContext, hook_point: str, dex_class: DexClass, method: Optional[DexMethod], contract_name: str) -> Tuple[bool, str]:
    if contract_name == "require_static_self_singleton":
        for f in dex_class.fields:
            if f.is_static and f.type_descriptor == dex_class.descriptor:
                return True, "Found static self-typed singleton"
        return False, f"Class {dex_class.binary_name} lacks static self-typed singleton field"

    elif contract_name == "require_field_PlayerLyricsViewFragment":
        expected_type = "Lcom/apple/android/music/player/fragment/PlayerLyricsViewFragment;"
        for f in dex_class.fields:
            if f.type_descriptor == expected_type:
                return True, f"Found PlayerLyricsViewFragment field '{f.name}'"
        return False, f"Class {dex_class.binary_name} lacks PlayerLyricsViewFragment field"

    elif contract_name == "require_field_CollectionItemView":
        expected_type = "Lcom/apple/android/music/model/CollectionItemView;"
        # 遍历自身及父类
        curr = dex_class
        while curr:
            for f in curr.fields:
                if f.type_descriptor == expected_type:
                    return True, f"Found CollectionItemView field '{f.name}' in {curr.binary_name}"
            if curr.superclass_descriptor:
                curr = dex_ctx.classes.get(curr.superclass_descriptor[1:-1].replace('/', '.'))
            else:
                break
        return False, f"Class {dex_class.binary_name} lacks CollectionItemView field"

    elif contract_name == "require_library_epoxy_build":
        if not method or method.name != "buildModels" or len(method.param_types) != 5:
            return False, "Method must be buildModels with 5 parameters"
        if method.param_types[0] in ("Ljava/lang/Object;", "[Ljava/lang/Object;"):
            return False, "First parameter cannot be Object/Object[] bridge"
        return True, "Valid buildModels 5-param overload"

    elif contract_name == "require_static_model_builder":
        if not method or not method.is_static or len(method.param_types) < 4:
            return False, "Model builder must be static with at least 4 params"
        return True, "Valid static model builder"

    return True, "Contract OK"


def verify_target(dex_ctx: ApkDexContext, hook_point: str, profile_spec: dict) -> dict:
    target_class_name = profile_spec["class"]
    dex_class = dex_ctx.find_class(target_class_name)
    if not dex_class:
        return {
            "status": "missing",
            "hook_point": hook_point,
            "target": target_class_name,
            "reason": f"Class {target_class_name} not found in DEX",
        }

    target_method_name = profile_spec.get("method")
    matched_method = None
    if target_method_name:
        for m in dex_class.methods:
            if m.name == target_method_name:
                if "param_count" in profile_spec and len(m.param_types) != profile_spec["param_count"]:
                    continue
                if "params" in profile_spec and m.param_types != profile_spec["params"]:
                    continue
                if "return" in profile_spec and m.return_type != profile_spec["return"]:
                    continue
                matched_method = m
                break
        if not matched_method:
            return {
                "status": "signature_mismatch",
                "hook_point": hook_point,
                "target": f"{target_class_name}#{target_method_name}",
                "reason": f"Method {target_method_name} signature mismatch in {target_class_name}",
            }

    contract_name = profile_spec.get("contract")
    if contract_name:
        passed, reason = check_contract(dex_ctx, hook_point, dex_class, matched_method, contract_name)
        if not passed:
            return {
                "status": "contract_rejected",
                "hook_point": hook_point,
                "target": f"{target_class_name}#{target_method_name or '<class>'}",
                "reason": reason,
            }

    return {
        "status": "exact",
        "hook_point": hook_point,
        "target": f"{target_class_name}#{target_method_name or '<class>'}",
        "details": "Exact target matched and verified by contract",
    }


def main():
    parser = argparse.ArgumentParser(description="Apple Music Hook Profile Offline APK Verifier")
    parser.add_argument("--apk", help="Path to Apple Music APK file")
    parser.add_argument("--version", default="6.5.2", help="Target Apple Music version (default: 6.5.2)")
    parser.add_argument("--json", action="store_true", help="Output results in JSON format")
    args = parser.parse_args()

    if not args.apk:
        print("[Offline Verifier] No APK provided. Use --apk <path_to_apk> to verify against real APK.")
        print(f"[Offline Verifier] Target version: {args.version}")
        print(f"[Offline Verifier] Configured Hook Points: {len(KNOWN_TARGETS_6_5_2)}")
        sys.exit(0)

    if not os.path.isfile(args.apk):
        print(f"Error: APK file not found at {args.apk}", file=sys.stderr)
        sys.exit(1)

    print(f"Loading DEX files from {args.apk}...")
    dex_ctx = ApkDexContext(args.apk)
    print(f"Loaded {len(dex_ctx.classes)} classes from DEX.")

    results = []
    for hook_point, spec in KNOWN_TARGETS_6_5_2.items():
        res = verify_target(dex_ctx, hook_point, spec)
        results.append(res)

    if args.json:
        print(json.dumps(results, indent=2))
        return

    print("\n=== Verification Report ===")
    exact_count = sum(1 for r in results if r["status"] == "exact")
    print(f"Total Hook Points: {len(results)}, Exact & Verified: {exact_count}")
    for r in results:
        status_sym = "✅" if r["status"] == "exact" else "❌"
        print(f"{status_sym} [{r['status'].upper()}] {r['hook_point']}: {r['target']} ({r.get('reason') or r.get('details')})")


if __name__ == "__main__":
    main()
