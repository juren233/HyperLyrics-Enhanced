/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import com.juren233.hyperlyricsenhanced.online.model.Source

/** Positions use row units, so density changes cannot alter the stored priority. */
internal data class SourceDragSession(
    val originalOrder: List<Source>,
    val source: Source,
    val position: Float = originalOrder.indexOf(source).toFloat(),
    val targetIndex: Int = originalOrder.indexOf(source),
    val released: Boolean = false,
) {
    init {
        require(source in originalOrder && originalOrder.distinct().size == originalOrder.size)
    }

    val previewOrder: List<Source>
        get() = originalOrder.toMutableList().apply {
            remove(source)
            add(targetIndex, source)
        }

    fun moveBy(delta: Float): SourceDragSession {
        if (released || !delta.isFinite()) return this
        // Clamp each event, not just drawing: reversing at an edge responds immediately.
        val next = (position + delta).coerceIn(0f, originalOrder.lastIndex.toFloat())
        var target = targetIndex
        // Hysteresis around each half-row boundary prevents repeated swaps from finger jitter.
        while (target < originalOrder.lastIndex && next > target + SWAP_THRESHOLD) target++
        while (target > 0 && next < target - SWAP_THRESHOLD) target--
        return copy(position = next, targetIndex = target)
    }

    fun finish(cancelled: Boolean): SourceDragSession = if (released) this else copy(
        targetIndex = if (cancelled) originalOrder.indexOf(source) else targetIndex,
        released = true,
    )

    private companion object {
        const val SWAP_THRESHOLD = 0.58f
    }
}
