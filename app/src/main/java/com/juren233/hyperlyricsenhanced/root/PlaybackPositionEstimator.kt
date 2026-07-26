package com.juren233.hyperlyricsenhanced.root

internal class PlaybackPositionEstimator {
    private var anchorPosition: Long? = null
    private var anchorRealtime: Long = 0L
    private var playing: Boolean = false

    @Synchronized
    fun update(position: Long, realtime: Long) {
        anchorPosition = position.coerceAtLeast(0L)
        anchorRealtime = realtime
    }

    @Synchronized
    fun setPlaying(value: Boolean, realtime: Long) {
        if (playing == value) return
        val current = estimateLocked(realtime)
        if (current != null) {
            anchorPosition = current
            anchorRealtime = realtime
        }
        playing = value
    }

    @Synchronized
    fun estimate(realtime: Long): Long? = estimateLocked(realtime)

    @Synchronized
    fun reset() {
        anchorPosition = null
        anchorRealtime = 0L
        playing = false
    }

    private fun estimateLocked(realtime: Long): Long? {
        val position = anchorPosition ?: return null
        if (!playing) return position
        return (position + (realtime - anchorRealtime).coerceAtLeast(0L)).coerceAtLeast(0L)
    }
}
