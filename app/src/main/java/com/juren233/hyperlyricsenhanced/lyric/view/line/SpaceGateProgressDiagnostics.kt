/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger

/** Created and called only behind BuildConfig.DEBUG. No text payload or per-frame logging. */
internal class SpaceGateProgressDiagnostics {
    private val rows = Array(120) { DoubleArray(14) }
    private var count = 0
    private var windows = 0
    private var lineCaptured = false
    private var tickNanos = 0L
    private var position = 0L
    private var wordBegin = -1L
    private var wordEnd = -1L
    private var drawCount = 0
    private var drawWidth = Float.NaN
    private var startedNanos = 0L
    private var lineBegin = 0L

    fun tick(posMs: Long, begin: Long?, end: Long?) {
        if (windows >= 4) return
        tickNanos = SystemClock.elapsedRealtimeNanos()
        position = posMs
        wordBegin = begin ?: -1L
        wordEnd = end ?: -1L
    }

    fun draw(width: Float) {
        if (windows >= 4) return
        drawCount++
        drawWidth = width
    }

    fun frame(
        viewId: Int, begin: Long, deltaNanos: Long, current: Float, target: Float,
        offset: Float, animating: Boolean, changed: Boolean,
    ) {
        if (windows >= 4 || lineCaptured || (count == 0 && offset >= 0f)) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (count == 0) {
            startedNanos = now
            lineBegin = begin
        }
        val row = rows[count++]
        row[0] = (now - startedNanos) / 1e6
        row[1] = deltaNanos / 1e6
        row[2] = position.toDouble()
        row[3] = if (tickNanos == 0L) -1.0 else (now - tickNanos) / 1e6
        row[4] = wordBegin.toDouble()
        row[5] = wordEnd.toDouble()
        row[6] = current.toDouble()
        row[7] = target.toDouble()
        row[8] = offset.toDouble()
        row[9] = if (animating) 1.0 else 0.0
        row[10] = if (changed) 1.0 else 0.0
        // Previous draw consumption: onDraw runs after the frame callback.
        row[11] = drawWidth.toDouble()
        row[12] = drawCount.toDouble()
        row[13] = (position - wordBegin).toDouble()
        drawCount = 0
        if (count == rows.size) flush(viewId)
    }

    fun reset(viewId: Int) {
        flush(viewId)
        lineCaptured = false
        tickNanos = 0L
        drawCount = 0
        drawWidth = Float.NaN
    }

    private fun flush(viewId: Int) {
        if (count == 0) return
        windows++
        // Small chunks stay below logcat's line limit. At most four windows per renderer.
        for (start in 0 until count step 12) {
            HookLogger.i("IslandProgressFrames", buildString {
                append("view=").append(viewId.toString(16))
                append(" window=").append(windows).append(" line=").append(lineBegin)
                append(" rows=").append(start).append(" schema=elapsed,dt,pos,tickAge,wb,we,hw,target,offset,anim,changed,prevDraw,draws,wordAge data=")
                for (i in start until minOf(start + 12, count)) {
                    if (i != start) append(';')
                    rows[i].forEachIndexed { column, value ->
                        if (column != 0) append(',')
                        append(if (value.isFinite()) (value * 100).toLong() / 100.0 else value)
                    }
                }
            })
        }
        count = 0
        lineCaptured = true
    }
}
