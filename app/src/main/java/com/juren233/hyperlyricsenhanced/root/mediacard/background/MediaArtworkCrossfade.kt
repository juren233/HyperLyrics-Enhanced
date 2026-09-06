/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0. */
package com.juren233.hyperlyricsenhanced.root.mediacard.background

/** Advances only while drawn; playback pauses do not cancel an artwork change.
 * A burst keeps only its latest target and completes the visible blend before starting it.
 */
internal class MediaArtworkCrossfade<T>(private val durationMs: Long = 850L) {
    var target: T? = null
        private set
    var pending: T? = null
        private set
    var active = false
        private set
    private var elapsedMs = 0L
    private var lastDrawMs: Long? = null

    fun offer(value: T): Boolean {
        if (value == target) {
            pending = null
            return false
        }
        if (active) {
            pending = value
            return false
        }
        active = target != null
        target = value
        elapsedMs = 0L
        lastDrawMs = null
        return true
    }

    fun draw(nowMs: Long): Float {
        if (!active) return 1f
        lastDrawMs?.let { elapsedMs += (nowMs - it).coerceAtLeast(0L) }
        lastDrawMs = nowMs
        val fraction = (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
        return fraction * fraction * (3f - 2f * fraction)
    }

    fun suspendDrawing() { lastDrawMs = null }

    /** Called after drawing the final frame, so the next blend starts at that exact target. */
    fun completeFrame(): T? {
        if (!active || elapsedMs < durationMs) return null
        active = false
        lastDrawMs = null
        val next = pending
        pending = null
        return next?.takeIf { offer(it) }
    }
}
