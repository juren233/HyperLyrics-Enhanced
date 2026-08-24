package com.juren233.hyperlyricsenhanced.root.utils

import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import java.util.concurrent.ConcurrentHashMap

/** Debug-only display decisions shared by the bridge, island, and AOD renderers. */
object DisplayDiagnosticLogger {
    private const val MAX_IDENTITY_CHARS = 80
    private val lastSignatures = ConcurrentHashMap<String, String>()

    fun log(
        channel: String,
        result: String,
        reason: String,
        extra: String = "",
        dedupeKey: String = channel,
    ) {
        if (!BuildConfig.DEBUG) return

        val song = LyriconDataBridge.currentSong
        val line = LyriconDataBridge.currentLyricLine
        val packageName = LyriconDataBridge.currentLyricPackageName
            ?: LyriconDataBridge.activePackageName
        val signature = listOf(
            song?.id,
            song?.name,
            packageName,
            LyriconDataBridge.currentPlaybackState,
            LyriconDataBridge.isTextMode,
            line?.begin,
            line?.end,
            line?.text?.length,
            line?.translation?.isNotBlank(),
            line?.secondary?.isNotBlank(),
            result,
            reason,
            extra,
        ).joinToString("|")
        if (lastSignatures.put(dedupeKey, signature) == signature) return

        val lyrics = song?.lyrics.orEmpty()
        val details = buildString {
            append("result=").append(result)
            append(", reason=").append(reason)
            append(", songId=").append(sanitize(song?.id))
            append(", title=").append(sanitize(song?.name))
            append(", artist=").append(sanitize(song?.artist))
            append(", package=").append(sanitize(packageName))
            append(", playing=").append(LyriconDataBridge.currentPlaybackState)
            append(", position=").append(LyriconDataBridge.currentPosition)
            append(", duration=").append(song?.duration ?: 0L)
            append(", lyricLines=").append(lyrics.size)
            append(", translatedLines=").append(lyrics.count { !it.translation.isNullOrBlank() })
            append(", lineBegin=").append(line?.begin)
            append(", lineEnd=").append(line?.end)
            append(", textLength=").append(line?.text?.length ?: 0)
            append(", hasTranslation=").append(!line?.translation.isNullOrBlank())
            append(", hasBacking=").append(!line?.secondary.isNullOrBlank())
            append(", textMode=").append(LyriconDataBridge.isTextMode)
            if (extra.isNotBlank()) append(", ").append(extra)
        }
        // AOD diagnosis must survive the default log level used by "export all logs".
        // It remains Debug-build-only because this method returns above in Release builds.
        if (channel == "AOD_LOCK" || channel == "AOD_CLASSIC") {
            HookLogger.i("DISPLAY_DIAG/$channel", details)
        } else {
            HookLogger.d("DISPLAY_DIAG/$channel", details)
        }
    }

    fun clear(channel: String? = null) {
        if (!BuildConfig.DEBUG) return
        if (channel == null) {
            lastSignatures.clear()
        } else {
            lastSignatures.keys.removeAll { it == channel || it.startsWith("$channel/") }
        }
    }

    private fun sanitize(value: String?): String = value
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(MAX_IDENTITY_CHARS)
        .orEmpty()
}
