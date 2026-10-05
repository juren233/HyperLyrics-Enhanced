package com.juren233.hyperlyricsenhanced.lyric.view.line

/** Automatic Debug windows, with at most one admission per 120 seconds per process. */
internal class GeometryWindowGate {
    private var lastStartNs: Long? = null
    private var nextId = 1

    fun start(nowNs: Long): Int? {
        lastStartNs?.let { if (nowNs - it < GeometrySampleWindow.WINDOW_NS) return null }
        lastStartNs = nowNs
        return nextId++
    }
}
