#!/usr/bin/env python3
"""Verify that a minified APK preserves the public Provider Pack ABI names."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


PROVIDER_PACKAGE = "com.juren233.hyperlyricsenhanced.provider"
REQUIRED_CLASSES = (
    "OfficialProviderPlugin",
    "OfficialProviderHost",
    "OfficialProviderSystemMediaPlugin",
    "OfficialProviderSystemMediaHost",
    "OfficialProviderSystemMediaCallback",
    "OfficialProviderSystemMediaSubscription",
    "OfficialProviderApplicationCallback",
    "OfficialProviderPlaybackStateCallback",
    "OfficialProviderMetadataCallback",
    "OfficialProviderMethodCallback",
    "OfficialProviderMethodResultCallback",
    "OfficialProviderConstructorCallback",
    "OfficialProviderDexMethodsCallback",
    "OfficialProviderMethodTarget",
    "OfficialProviderConstructorTarget",
    "OfficialProviderDexTypeSource",
    "OfficialProviderDexTypeReference",
    "OfficialProviderDexMethodQuery",
    "OfficialProviderNextTrackFrame",
    "OfficialProviderControlProtocol",
)


def find_apkanalyzer(explicit: str | None) -> str:
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit))

    on_path = shutil.which("apkanalyzer")
    if on_path:
        candidates.append(Path(on_path))

    for env_name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk_root = os.environ.get(env_name)
        if sdk_root:
            candidates.append(Path(sdk_root) / "cmdline-tools" / "latest" / "bin" / "apkanalyzer")

    candidates.append(Path.home() / "Android" / "Sdk" / "cmdline-tools" / "latest" / "bin" / "apkanalyzer")

    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    raise FileNotFoundError("找不到 apkanalyzer；请通过 --apkanalyzer 或 ANDROID_HOME 指定 Android SDK")


def read_defined_classes(apkanalyzer: str, apk: Path) -> set[str]:
    completed = subprocess.run(
        [apkanalyzer, "dex", "packages", "--defined-only", str(apk)],
        check=True,
        capture_output=True,
        text=True,
    )
    class_pattern = re.compile(r"^C\s+\S+\s+\d+\s+\d+\s+\d+\s+(\S+)$")
    return {
        match.group(1)
        for line in completed.stdout.splitlines()
        if (match := class_pattern.match(line)) is not None
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path, help="待检查的 APK")
    parser.add_argument("--apkanalyzer", help="apkanalyzer 可执行文件路径")
    args = parser.parse_args()

    if not args.apk.is_file():
        parser.error(f"APK 不存在: {args.apk}")

    try:
        apkanalyzer = find_apkanalyzer(args.apkanalyzer)
        defined_classes = read_defined_classes(apkanalyzer, args.apk)
    except (FileNotFoundError, subprocess.CalledProcessError) as error:
        print(f"Provider ABI 校验无法执行: {error}", file=sys.stderr)
        return 2

    required = {f"{PROVIDER_PACKAGE}.{name}" for name in REQUIRED_CLASSES}
    missing = sorted(required - defined_classes)
    if missing:
        print("Provider ABI 校验失败，以下公开类在 APK 中缺少原始二进制名称：", file=sys.stderr)
        for class_name in missing:
            print(f"- {class_name}", file=sys.stderr)
        return 1

    print(f"Provider ABI 校验通过：{len(required)} 个公开类均保留原始二进制名称")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
