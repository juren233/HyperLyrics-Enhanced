package com.juren233.hyperlyricsenhanced.root.utils

import android.os.SystemClock
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only, cross-layer diagnostics for Issue #22 media/AOD lifecycle tracing.
 *
 * Keep this logger structured and bounded: it records identities and state transitions,
 * never lyric text or artwork bytes, and rate-limits position samples per stage.
 */
object MediaCardDiagnosticLogger {
    private const val TAG = "MediaCardDiag"
    private const val PREFIX = "[MEDIA_CARD_DIAG]"
    private const val POSITION_SAMPLE_INTERVAL_MS = 2_000L
    private const val MAX_TEXT_CHARS = 80

    private val sequence = AtomicLong(0)
    private val lastPositionSampleAt = ConcurrentHashMap<String, Long>()

    fun log(
        stage: String,
        event: String,
        reason: String? = null,
        details: String = "",
        positionSample: Boolean = false,
    ) {
        if (!BuildConfig.DEBUG) return
        val now = monotonicNowMs()
        if (positionSample) {
            val previous = lastPositionSampleAt[stage]
            if (previous != null && now - previous < POSITION_SAMPLE_INTERVAL_MS) return
            lastPositionSampleAt[stage] = now
        }
        val message = buildString {
            append(PREFIX)
            append(" seq=").append(sequence.incrementAndGet())
            append(" elapsedMs=").append(now)
            append(" stage=").append(sanitize(stage))
            append(" event=").append(sanitize(event))
            reason?.takeIf(String::isNotBlank)?.let {
                append(" reason=").append(sanitize(it))
            }
            append(" bridge=").append(bridgeSnapshot())
            if (details.isNotBlank()) append(" ").append(details.trim())
        }
        // INFO is intentional: the exported default matrix retains I in Debug builds.
        // Logging must never alter the state machine, including local JVM tests where
        // android.util.Log methods are intentionally not mocked.
        runCatching { HookLogger.i(TAG, message) }
    }

    fun view(view: View?): String {
        if (view == null) return "null"
        return "${view.javaClass.simpleName}@${System.identityHashCode(view)}" +
            "{visibility=${view.visibility},shown=${view.isShown},attached=${view.isAttachedToWindow}," +
            "alpha=${view.alpha},size=${view.width}x${view.height}}"
    }

    fun identity(value: Any?): String = value?.let {
        "${it.javaClass.simpleName}@${System.identityHashCode(it)}"
    } ?: "null"

    fun sanitize(value: Any?): String = value?.toString()
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(MAX_TEXT_CHARS)
        ?.replace(',', ';')
        .orEmpty()

    private fun monotonicNowMs(): Long = runCatching {
        SystemClock.elapsedRealtime()
    }.getOrElse {
        // Local JVM tests use android.jar stubs where SystemClock is not mocked.
        System.nanoTime() / 1_000_000L
    }

    private fun bridgeSnapshot(): String {
        val song = LyriconDataBridge.currentSong
        val line = LyriconDataBridge.currentLyricLine
        return "songId=${sanitize(song?.id)}" +
            ",title=${sanitize(song?.name)}" +
            ",pkg=${sanitize(LyriconDataBridge.currentLyricPackageName ?: LyriconDataBridge.activePackageName)}" +
            ",playing=${LyriconDataBridge.currentPlaybackState}" +
            ",position=${LyriconDataBridge.currentPosition}" +
            ",line=${line?.begin}-${line?.end}" +
            ",lineTextLen=${line?.text?.length ?: 0}" +
            ",textMode=${LyriconDataBridge.isTextMode}" +
            ",version=${LyriconDataBridge.versionCounter.get()}"
    }
}
