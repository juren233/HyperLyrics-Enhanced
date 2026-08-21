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
REQUIRED_PROVIDER_CLASSES = (
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

# Union of external kotlin-stdlib class references in the currently published official
# Provider Packs. Packs declare kotlin-stdlib as compileOnly, so these names must resolve from
# the minified core APK at runtime. Keep this list in sync when the Pack runtime ABI expands.
REQUIRED_KOTLIN_RUNTIME_CLASSES = (
    "kotlin.DeprecationLevel",
    "kotlin.NoWhenBranchMatchedException",
    "kotlin.Pair",
    "kotlin.Result",
    "kotlin.ResultKt",
    "kotlin.TuplesKt",
    "kotlin.Unit",
    "kotlin.collections.ArraysKt",
    "kotlin.collections.CollectionsKt",
    "kotlin.collections.IntIterator",
    "kotlin.collections.MapsKt",
    "kotlin.collections.SetsKt",
    "kotlin.comparisons.ComparisonsKt",
    "kotlin.enums.EnumEntriesKt",
    "kotlin.internal.ProgressionUtilKt",
    "kotlin.io.ByteStreamsKt",
    "kotlin.io.CloseableKt",
    "kotlin.io.FilesKt",
    "kotlin.io.TextStreamsKt",
    "kotlin.jvm.functions.Function0",
    "kotlin.jvm.functions.Function1",
    "kotlin.jvm.functions.Function2",
    "kotlin.jvm.internal.FunctionReferenceImpl",
    "kotlin.jvm.internal.Intrinsics",
    "kotlin.jvm.internal.PropertyReference1Impl",
    "kotlin.jvm.internal.SpreadBuilder",
    "kotlin.jvm.internal.StringCompanionObject",
    "kotlin.math.MathKt",
    "kotlin.ranges.IntRange",
    "kotlin.ranges.RangesKt",
    "kotlin.sequences.Sequence",
    "kotlin.sequences.SequencesKt",
    "kotlin.text.CharsKt",
    "kotlin.text.Charsets",
    "kotlin.text.MatchResult",
    "kotlin.text.Regex",
    "kotlin.text.RegexOption",
    "kotlin.text.StringsKt",
)

REQUIRED_LYRICON_RUNTIME_CLASSES = (
    "io.github.proify.lyricon.lyric.model.LyricWord",
    "io.github.proify.lyricon.lyric.model.RichLyricLine",
    "io.github.proify.lyricon.lyric.model.Song",
    "io.github.proify.lyricon.provider.LyriconFactory",
    "io.github.proify.lyricon.provider.LyriconProvider",
    "io.github.proify.lyricon.provider.RemotePlayer",
    "io.github.proify.lyricon.provider.service.RemoteService",
)

REQUIRED_LYRICON_SUBSCRIBER_CLASSES = (
    "io.github.proify.lyricon.subscriber.ActivePlayerListener",
    "io.github.proify.lyricon.subscriber.ActivePlayerListenerDispatcher",
    "io.github.proify.lyricon.subscriber.ActivePlayerListenerDispatcher$Companion",
    "io.github.proify.lyricon.subscriber.ActivePlayerListenerDispatcher$launchPositionObserver$1$1",
    "io.github.proify.lyricon.subscriber.BuildConfig",
    "io.github.proify.lyricon.subscriber.CentralServiceReceiver",
    "io.github.proify.lyricon.subscriber.CentralServiceReceiver$ServiceListener",
    "io.github.proify.lyricon.subscriber.CentralServiceReceiver$innerReceiver$1",
    "io.github.proify.lyricon.subscriber.ConnectionListener",
    "io.github.proify.lyricon.subscriber.EmptyLyriconSubscriber",
    "io.github.proify.lyricon.subscriber.ExtensionsKt",
    "io.github.proify.lyricon.subscriber.IActivePlayerListener",
    "io.github.proify.lyricon.subscriber.IActivePlayerListener$Default",
    "io.github.proify.lyricon.subscriber.IActivePlayerListener$Stub",
    "io.github.proify.lyricon.subscriber.IActivePlayerListener$Stub$Proxy",
    "io.github.proify.lyricon.subscriber.IRemoteService",
    "io.github.proify.lyricon.subscriber.IRemoteService$Default",
    "io.github.proify.lyricon.subscriber.IRemoteService$Stub",
    "io.github.proify.lyricon.subscriber.IRemoteService$Stub$Proxy",
    "io.github.proify.lyricon.subscriber.IRemoteService$_Parcel",
    "io.github.proify.lyricon.subscriber.ISubscriberBinder",
    "io.github.proify.lyricon.subscriber.ISubscriberBinder$Default",
    "io.github.proify.lyricon.subscriber.ISubscriberBinder$Stub",
    "io.github.proify.lyricon.subscriber.ISubscriberBinder$Stub$Proxy",
    "io.github.proify.lyricon.subscriber.LyriconFactory",
    "io.github.proify.lyricon.subscriber.LyriconSubscriber",
    "io.github.proify.lyricon.subscriber.LyriconSubscriberImpl",
    "io.github.proify.lyricon.subscriber.LyriconSubscriberImpl$Companion",
    "io.github.proify.lyricon.subscriber.LyriconSubscriberImpl$binder$1$1",
    "io.github.proify.lyricon.subscriber.LyriconSubscriberImpl$launchConnectTimeoutTask$1",
    "io.github.proify.lyricon.subscriber.LyriconSubscriberImpl$serviceListener$1",
    "io.github.proify.lyricon.subscriber.ProviderInfo",
    "io.github.proify.lyricon.subscriber.ProviderInfo$$serializer",
    "io.github.proify.lyricon.subscriber.ProviderInfo$Companion",
    "io.github.proify.lyricon.subscriber.ProviderLogo",
    "io.github.proify.lyricon.subscriber.ProviderLogo$$serializer",
    "io.github.proify.lyricon.subscriber.ProviderLogo$Companion",
    "io.github.proify.lyricon.subscriber.ProviderMetadata",
    "io.github.proify.lyricon.subscriber.ProviderMetadata$$serializer",
    "io.github.proify.lyricon.subscriber.ProviderMetadata$Companion",
    "io.github.proify.lyricon.subscriber.ProviderMetadataKt",
    "io.github.proify.lyricon.subscriber.RemoteSubscriberService",
    "io.github.proify.lyricon.subscriber.RemoteSubscriberService$Companion",
    "io.github.proify.lyricon.subscriber.SubscriberBinder",
    "io.github.proify.lyricon.subscriber.SubscriberBinder$RegistrationCallback",
    "io.github.proify.lyricon.subscriber.SubscriberConstants",
    "io.github.proify.lyricon.subscriber.SubscriberInfo",
    "io.github.proify.lyricon.subscriber.SubscriberInfo$$serializer",
    "io.github.proify.lyricon.subscriber.SubscriberInfo$Companion",
    "io.github.proify.lyricon.subscriber.SubscriberInfo$Creator",
    "io.github.proify.lyricon.subscriber.SubscriberStatus",
)

REQUIRED_LYRICON_CENTRAL_CLASSES = (
    "io.github.proify.lyricon.central.BridgeCentral",
    "io.github.proify.lyricon.central.CentralReceiver",
    "io.github.proify.lyricon.central.CentralRuntime",
    "io.github.proify.lyricon.central.connection.ConnectionRegistry",
    "io.github.proify.lyricon.central.connection.RemoteConnection",
    "io.github.proify.lyricon.central.provider.ProviderConnection",
    "io.github.proify.lyricon.central.provider.ProviderServiceBinder",
    "io.github.proify.lyricon.central.provider.player.ActivePlayerCoordinator",
    "io.github.proify.lyricon.central.provider.player.PlayerBinder",
    "io.github.proify.lyricon.central.provider.player.PlayerListener",
    "io.github.proify.lyricon.central.provider.player.PlayerRecorder",
    "io.github.proify.lyricon.central.registration.RegistrationHandler",
    "io.github.proify.lyricon.central.subscriber.ActivePlayerSubscription",
    "io.github.proify.lyricon.central.subscriber.SubscriberConnection",
    "io.github.proify.lyricon.central.subscriber.SubscriberServiceBinder",
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

    required = {
        *(f"{PROVIDER_PACKAGE}.{name}" for name in REQUIRED_PROVIDER_CLASSES),
        *REQUIRED_KOTLIN_RUNTIME_CLASSES,
        *REQUIRED_LYRICON_RUNTIME_CLASSES,
        *REQUIRED_LYRICON_SUBSCRIBER_CLASSES,
        *REQUIRED_LYRICON_CENTRAL_CLASSES,
    }
    missing = sorted(required - defined_classes)
    if missing:
        print("Provider ABI 校验失败，以下宿主类在 APK 中缺少原始二进制名称：", file=sys.stderr)
        for class_name in missing:
            print(f"- {class_name}", file=sys.stderr)
        return 1

    print(f"Provider ABI 校验通过：{len(required)} 个宿主类均保留原始二进制名称")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
