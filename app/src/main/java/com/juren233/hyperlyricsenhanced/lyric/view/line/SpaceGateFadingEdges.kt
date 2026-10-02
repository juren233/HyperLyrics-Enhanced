/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

import kotlin.math.abs

/** Physical viewport edge fades, independent of the current word/highlight animator. */
internal data class SpaceGateFadingEdges(
    val outerLeft: Float,
    val camera: Float,
    val outerRight: Float,
) {
    companion object {
        val NONE = SpaceGateFadingEdges(0f, 0f, 0f)

        fun resolve(
            textWidth: Float,
            scrollWidth: Float,
            viewWidth: Float,
            seam: Float,
            offset: Float,
            edgeLength: Float,
            plan: SeamStripPlan?,
            ghostStart: Float? = null,
        ): SpaceGateFadingEdges {
            if (edgeLength <= 0f || viewWidth <= 0f || scrollWidth <= viewWidth) return NONE
            // Capacity compensation can extend a naturally short line. Use its
            // actual first/last band positions, not the synthetic scroll extent.
            val start = offset + (plan?.bands?.firstOrNull()?.delta ?: 0f)
            val end = offset + textWidth + (plan?.bands?.lastOrNull()?.delta ?: 0f)
            fun left(start: Float, end: Float): Float =
                if (end > 0f) (-start / edgeLength).coerceIn(0f, 1f) else 0f
            fun right(start: Float, end: Float): Float =
                if (start < viewWidth) ((end - viewWidth) / edgeLength).coerceIn(0f, 1f) else 0f
            fun camera(start: Float, end: Float): Float =
                if (seam > 0f && seam < viewWidth) {
                    (minOf(seam - start, end - seam) / edgeLength).coerceIn(0f, 1f)
                } else 0f

            // Both endpoints deliberately place complete words beside the camera.
            // Fade length grows/shrinks with displacement so they remain readable
            // at rest, without an animation-state toggle or a second clock.
            val travel = scrollWidth - viewWidth
            val moving = (minOf(abs(offset), abs(offset + travel)) / edgeLength).coerceIn(0f, 1f)
            val ghostEnd = ghostStart?.plus(textWidth)
            return SpaceGateFadingEdges(
                outerLeft = maxOf(left(start, end), if (ghostStart != null) left(ghostStart, ghostEnd!!) else 0f),
                camera = maxOf(minOf(moving, camera(start, end)),
                    if (ghostStart != null) camera(ghostStart, ghostEnd!!) else 0f),
                outerRight = maxOf(right(start, end), if (ghostStart != null) right(ghostStart, ghostEnd!!) else 0f),
            )
        }
    }
}
