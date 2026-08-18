/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import com.juren233.hyperlyricsenhanced.BuildConfig

/**
 * Debug-only aggregation for one user-initiated missing-lyrics source switch.
 *
 * Hot paths only update counters while a trace is active. Detailed output is emitted at
 * request boundaries so diagnostic logging does not become another source of UI jank.
 */
internal object AppleSourceSwitchPerformanceDiagnostics {
    private const val POST_RESULT_WINDOW_MS = 2_500L
    private const val TRACE_TIMEOUT_MS = 15_000L

    private data class Counter(
        var calls: Long = 0L,
        var units: Long = 0L,
        var totalNanos: Long = 0L,
        var maxNanos: Long = 0L,
        var lastDetails: String? = null,
    )

    private data class Trace(
        val requestId: Long,
        val songId: String,
        val previousSource: String,
        val targetSource: String,
        val startedAtMs: Long,
        val counters: LinkedHashMap<String, Counter> = linkedMapOf(),
        var lastFrameTimeNanos: Long = 0L,
        var frameCount: Long = 0L,
        var framesOver24Ms: Long = 0L,
        var framesOver40Ms: Long = 0L,
        var framesOver80Ms: Long = 0L,
        var framesOver160Ms: Long = 0L,
        var maxFrameNanos: Long = 0L,
    )

    private val lock = Any()

    @Volatile
    private var activeTrace: Trace? = null

    fun start(
        mainHandler: Handler,
        requestId: Long,
        songId: String,
        previousSource: String,
        targetSource: String,
    ) {
        if (!BuildConfig.DEBUG) return
        val previous = synchronized(lock) {
            val old = activeTrace
            activeTrace = Trace(
                requestId = requestId,
                songId = songId,
                previousSource = previousSource,
                targetSource = targetSource,
                startedAtMs = SystemClock.elapsedRealtime(),
            )
            old
        }
        previous?.let { logSummary(it, reason = "superseded") }
        stage(
            requestId = requestId,
            songId = songId,
            stage = "request_started",
            details = "from=$previousSource,to=$targetSource",
        )
        mainHandler.post {
            if (isActive(requestId)) {
                Choreographer.getInstance().postFrameCallback(FrameProbe(requestId))
            }
        }
        mainHandler.postDelayed(
            { finish(requestId, reason = "timeout") },
            TRACE_TIMEOUT_MS,
        )
    }

    fun stage(
        requestId: Long,
        songId: String?,
        stage: String,
        details: String = "",
    ) {
        if (!BuildConfig.DEBUG) return
        val snapshot = synchronized(lock) {
            activeTrace?.takeIf { trace ->
                trace.requestId == requestId &&
                    (songId.isNullOrBlank() || trace.songId == songId)
            }?.let { trace -> trace.startedAtMs to trace.songId }
        } ?: return
        val suffix = details.takeIf(String::isNotBlank)?.let { ", $it" }.orEmpty()
        ProviderLogger.diagnostic(
            "[SourceSwitchPerf] stage=$stage, requestId=$requestId, songId=${snapshot.second}, " +
                "elapsedMs=${SystemClock.elapsedRealtime() - snapshot.first}, " +
                "clockMs=${SystemClock.elapsedRealtime()}, ${threadDetails()}$suffix"
        )
    }

    /**
     * Emits a timestamped stage for the currently active source-switch trace of a song.
     * Binder callbacks do not carry the source-switch request id, so resolve it from the
     * in-process trace while keeping the event correlated with the existing request summary.
     */
    fun stageForSong(
        songId: String?,
        stage: String,
        details: String = "",
    ) {
        if (!BuildConfig.DEBUG || songId.isNullOrBlank()) return
        val requestId = synchronized(lock) {
            activeTrace?.takeIf { it.songId == songId }?.requestId
        } ?: return
        stage(
            requestId = requestId,
            songId = songId,
            stage = stage,
            details = details,
        )
    }

    fun isTracing(songId: String?): Boolean {
        if (!BuildConfig.DEBUG || songId.isNullOrBlank()) return false
        return synchronized(lock) { activeTrace?.songId == songId }
    }

    /**
     * Cross-process/core-side stage marker for the same source-switch request.
     *
     * The root-side source runs outside the Apple Music hook package, so it cannot
     * share the in-memory trace. Keep the request id and song id in every line so
     * host-side and root-side logs can be joined after a device reproduction.
     */
    fun coreStage(
        requestId: Long?,
        songId: String?,
        stage: String,
        details: String = "",
    ) {
        if (!BuildConfig.DEBUG || requestId == null || songId.isNullOrBlank()) return
        val suffix = details.takeIf(String::isNotBlank)?.let { ", $it" }.orEmpty()
        ProviderLogger.diagnostic(
            "[SourceSwitchPerf/Core] stage=$stage, requestId=$requestId, " +
                "songId=$songId, clockMs=${SystemClock.elapsedRealtime()}, " +
                "${threadDetails()}$suffix"
        )
    }

    fun record(
        songId: String?,
        event: String,
        durationNanos: Long = 0L,
        units: Long = 1L,
        details: String? = null,
    ) {
        if (!BuildConfig.DEBUG || songId.isNullOrBlank()) return
        synchronized(lock) {
            val trace = activeTrace?.takeIf { it.songId == songId } ?: return
            val counter = trace.counters.getOrPut(event) { Counter() }
            counter.calls += 1L
            counter.units += units.coerceAtLeast(0L)
            counter.totalNanos += durationNanos.coerceAtLeast(0L)
            counter.maxNanos = maxOf(counter.maxNanos, durationNanos)
            counter.lastDetails = details?.take(160) ?: counter.lastDetails
        }
    }

    fun complete(
        mainHandler: Handler,
        requestId: Long,
        songId: String?,
        successful: Boolean,
        actualSource: String?,
    ) {
        if (!BuildConfig.DEBUG) return
        stage(
            requestId = requestId,
            songId = songId,
            stage = "result_received",
            details = "successful=$successful,actual=${actualSource ?: "none"}",
        )
        mainHandler.postDelayed(
            { finish(requestId, reason = if (successful) "completed" else "failed") },
            POST_RESULT_WINDOW_MS,
        )
    }

    fun fail(
        mainHandler: Handler,
        requestId: Long,
        songId: String?,
        reason: String,
    ) {
        if (!BuildConfig.DEBUG) return
        stage(requestId, songId, stage = "request_failed", details = "reason=$reason")
        mainHandler.postDelayed(
            { finish(requestId, reason = "failed_$reason") },
            POST_RESULT_WINDOW_MS,
        )
    }

    private fun finish(requestId: Long, reason: String) {
        if (!BuildConfig.DEBUG) return
        val trace = synchronized(lock) {
            activeTrace?.takeIf { it.requestId == requestId }?.also { activeTrace = null }
        } ?: return
        logSummary(trace, reason)
    }

    private fun isActive(requestId: Long): Boolean = synchronized(lock) {
        activeTrace?.requestId == requestId
    }

    private fun logSummary(trace: Trace, reason: String) {
        val elapsedMs = SystemClock.elapsedRealtime() - trace.startedAtMs
        val counters = trace.counters.entries.joinToString(separator = " | ") { (event, counter) ->
            buildString {
                append(event)
                append("[calls=").append(counter.calls)
                append(",units=").append(counter.units)
                append(",totalMs=").append(nanosToMillis(counter.totalNanos))
                append(",maxMs=").append(nanosToMillis(counter.maxNanos))
                counter.lastDetails?.let { append(",last=").append(it) }
                append(']')
            }
        }.ifBlank { "none" }
        ProviderLogger.diagnostic(
            "[SourceSwitchPerf] stage=summary, requestId=${trace.requestId}, " +
                "songId=${trace.songId}, from=${trace.previousSource}, to=${trace.targetSource}, " +
                "reason=$reason, elapsedMs=$elapsedMs, frames=${trace.frameCount}, " +
                "frameOver24=${trace.framesOver24Ms}, frameOver40=${trace.framesOver40Ms}, " +
                "frameOver80=${trace.framesOver80Ms}, frameOver160=${trace.framesOver160Ms}, " +
                "maxFrameMs=${nanosToMillis(trace.maxFrameNanos)}, events=$counters"
        )
    }

    private fun nanosToMillis(value: Long): String =
        String.format(java.util.Locale.US, "%.2f", value / 1_000_000.0)

    private fun threadDetails(): String =
        "thread=${Thread.currentThread().name},main=${Looper.myLooper() === Looper.getMainLooper()}"

    private class FrameProbe(
        private val requestId: Long,
    ) : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val keepRunning = synchronized(lock) {
                val trace = activeTrace?.takeIf { it.requestId == requestId }
                    ?: return@synchronized false
                val previous = trace.lastFrameTimeNanos
                trace.lastFrameTimeNanos = frameTimeNanos
                if (previous > 0L && frameTimeNanos > previous) {
                    val duration = frameTimeNanos - previous
                    trace.frameCount += 1L
                    trace.maxFrameNanos = maxOf(trace.maxFrameNanos, duration)
                    if (duration >= 24_000_000L) trace.framesOver24Ms += 1L
                    if (duration >= 40_000_000L) trace.framesOver40Ms += 1L
                    if (duration >= 80_000_000L) trace.framesOver80Ms += 1L
                    if (duration >= 160_000_000L) trace.framesOver160Ms += 1L
                }
                true
            }
            if (keepRunning) Choreographer.getInstance().postFrameCallback(this)
        }
    }
}
