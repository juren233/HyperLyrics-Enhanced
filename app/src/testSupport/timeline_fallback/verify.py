#!/usr/bin/env python3
# Copyright 2026 juren233. Licensed under the Apache License, Version 2.0.
"""Run the real LocalTimelineDriver against isolated JVM boundary fakes.

No Android device, screenshots, Gradle packaging, or dependency downloads. Android,
the render bridge and scheduling are faked; driver and identity/playback policies
are compiled unchanged. This verifies binding/events, not rendering or timer cadence.
Uses the Kotlin compiler already cached by this repository's Gradle build.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
MAIN = ROOT / "app/src/main/java"
PACKAGE = "com/juren233/hyperlyricsenhanced"

STUBS = {
    "Android.kt": """
package android.os
class Looper { companion object { private val main = Looper(); fun getMainLooper() = main; fun myLooper() = main } }
class Handler(val looper: Looper) { fun post(action: () -> Unit) { action() } }
object SystemClock { var now = 10_000L; fun elapsedRealtime() = now; fun uptimeMillis() = now }
""",
    "Coroutines.kt": """
package kotlinx.coroutines
class Context { operator fun plus(other: Context) = this }
class CoroutineScope(context: Context)
class Job { var isActive = true; fun cancel() { isActive = false } }
object Dispatchers { object Main { val immediate = Context() } }
fun SupervisorJob() = Context()
val CoroutineScope.isActive: Boolean get() = true
// Deliberately do not run the background loop; its cadence has separate JVM tests.
fun CoroutineScope.launch(block: suspend CoroutineScope.() -> Unit) = Job()
suspend fun delay(ms: Long) = Unit
""",
    "Serialization.kt": """
package kotlinx.serialization
annotation class Serializable
""",
    "Build.kt": """
package com.juren233.hyperlyricsenhanced
object BuildConfig { const val DEBUG = false }
""",
    "Catalog.kt": """
package com.juren233.hyperlyricsenhanced.common
object IslandMusicAppCatalog { fun isSupported(pkg: String) = pkg in setOf("cn.kuwo.player", "com.netease.cloudmusic") }
""",
    "Song.kt": """
package com.juren233.hyperlyricsenhanced.lyric.model
data class RichLyricLine(val begin: Long, val end: Long, val duration: Long, val text: String, val words: List<String>? = null)
data class Song(val id: String, val name: String? = null, val artist: String? = null, val duration: Long = 0, val lyrics: List<RichLyricLine>? = null)
""",
    "Demand.kt": """
package com.juren233.hyperlyricsenhanced.lyric.view.line
object PositionUpdateDemand { val active = this; fun requiresFrequentUpdates() = false }
""",
    "Bridge.kt": """
package com.juren233.hyperlyricsenhanced.root
import com.juren233.hyperlyricsenhanced.lyric.model.*
object SystemUiEnhancementGate { var enabled = true; fun isLyricRuntimeEnabled() = enabled }
object LyriconDataBridge {
    var currentSongName = ""; var currentSong: Song? = null; var packageName = ""
    val currentLyricLine: RichLyricLine? get() = currentSong?.lyrics?.firstOrNull()
    val currentNextLyricLine: RichLyricLine? get() = null
    fun updateLyricPackage(pkg: String) { packageName = pkg }
    fun updateSong(song: Song?) { currentSong = song }
    fun replaceSameSongContent(song: Song?): Boolean {
        if (currentSong?.id != song?.id || currentSong == null) return false
        currentSong = song; return true
    }
    fun nextDisplayChangeMs(position: Long): Long? = null
}
""",
    "Logger.kt": """
package com.juren233.hyperlyricsenhanced.root.utils
object HookLogger { fun i(tag: String, message: String) {}; fun d(tag: String, message: String) {} }
""",
    "Anchor.kt": """
package com.juren233.hyperlyricsenhanced.root.timeline
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity
class SystemMediaPlaybackAnchor {
    interface Listener { fun onTrackChanged(track: TrackIdentity?); fun onTrackMetadataRefreshed(track: TrackIdentity); fun onPlaybackStateChanged(isPlaying: Boolean) }
    data class Anchor(val isPlaying: Boolean = false, val stateUpdatedAtMs: Long = 0)
    var currentTrack: TrackIdentity? = null
    var state = Anchor()
    fun start(listener: Listener) {}; fun removeListener(listener: Listener) {}
    fun estimatedPosition(now: Long): Long? = 1000L
    fun anchor(): Anchor = state
}
""",
}


def jar(cache: Path, group: str, artifact: str, version: str) -> Path:
    matches = list((cache / group / artifact / version).glob("*/*.jar"))
    if len(matches) != 1:
        raise SystemExit(f"Expected one cached {artifact} {version}, found {len(matches)}")
    return matches[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--driver-source", type=Path, help="Optional pre-fix source for a negative control")
    args = parser.parse_args()
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
    versions = (cache / "org.jetbrains.kotlin/kotlin-compiler-embeddable").iterdir()
    version = max((p.name for p in versions), key=lambda v: tuple(int(n) for n in v.split(".")))
    compiler = [jar(cache, "org.jetbrains.kotlin", name, version) for name in (
        "kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-script-runtime",
    )]
    # Compiler runtime dependencies, unrelated to the scheduling fakes above.
    for group, name in (("org.jetbrains.kotlin", "kotlin-reflect"),
                        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
                        ("org.jetbrains", "annotations"), ("org.jetbrains.intellij.deps", "trove4j")):
        matches = sorted((cache / group / name).glob("*/*/*.jar"))
        if matches:
            compiler.append(matches[-1])
    sources = [MAIN / PACKAGE / "root/timeline" / (name + ".kt") for name in (
        "PlaybackSmoothingPolicy", "TimelineContentPolicy", "SourceClockUpdatePolicy",
        "TimelineCadencePolicy", "XiaomiMusicMetadataRefreshPolicy",
    )]
    sources += [MAIN / PACKAGE / "lyric/source/LyricSink.kt", MAIN / PACKAGE / "lyric/source/TimelineContent.kt",
                MAIN / PACKAGE / "timeline/model/TrackIdentity.kt",
                args.driver_source or MAIN / PACKAGE / "root/timeline/LocalTimelineDriver.kt",
                Path(__file__).with_name("Scenarios.kt")]
    with tempfile.TemporaryDirectory(prefix="hle-timeline-regression-") as temp:
        folder = Path(temp)
        for name, content in STUBS.items():
            path = folder / name
            path.write_text(content)
            sources.append(path)
        classes = folder / "classes"
        subprocess.run(["java", "-Xmx512m", "-cp", os.pathsep.join(map(str, compiler)),
                        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-nowarn",
                        "-classpath", str(compiler[1]), "-d", str(classes), *map(str, sources)], check=True)
        subprocess.run(["java", "-Xmx256m", "-cp", os.pathsep.join((str(classes), str(compiler[1]))),
                        "ScenariosKt"], check=True)


if __name__ == "__main__":
    main()
