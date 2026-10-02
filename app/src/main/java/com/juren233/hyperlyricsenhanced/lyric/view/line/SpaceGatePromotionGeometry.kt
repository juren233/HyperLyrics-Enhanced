/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

/** Promotion changes glyph positions before viewport clipping, never the camera viewport itself. */
internal class SpaceGatePromotionGeometry(val source: Endpoint, val target: Endpoint) {
    data class Glyph(
        val charStart: Int,
        val charEnd: Int,
        val naturalStart: Float,
        val start: Float,
        val end: Float,
        val blank: Boolean,
        val naturalEnd: Float = naturalStart + (end - start),
    )

    data class Endpoint(val glyphs: List<Glyph>, val viewWidth: Float, val seam: Float)

    /** Keep the original font advances when a new animation starts from an in-flight frame. */
    fun frame(fraction: Float): Endpoint = target.copy(glyphs = source.glyphs.mapIndexed { index, glyph ->
        glyph.copy(start = start(index, fraction), end = end(index, fraction))
    })

    init {
        require(source.glyphs.size == target.glyphs.size)
        require(source.glyphs.zip(target.glyphs).all { (a, b) ->
            a.charStart == b.charStart && a.charEnd == b.charEnd
        })
    }

    fun start(index: Int, fraction: Float): Float =
        lerp(source.glyphs[index].start, target.glyphs[index].start, fraction)

    fun end(index: Int, fraction: Float): Float =
        lerp(source.glyphs[index].end, target.glyphs[index].end, fraction)

    /** Same majority-side clipping and fade as normal camera transit, in fixed viewport coordinates. */
    fun fade(index: Int, fraction: Float): SeamStripPlan.SeamFade? {
        val glyph = source.glyphs[index]
        if (glyph.blank) return null
        val start = start(index, fraction)
        val end = end(index, fraction)
        val seam = target.seam
        if (seam <= start + SeamOcclusionLayout.FUZZ || seam >= end - SeamOcclusionLayout.FUZZ) return null
        val leftFraction = ((seam - start) / (end - start)).coerceIn(0f, 1f)
        return SeamStripPlan.SeamFade(
            alpha = 2f * kotlin.math.abs(leftFraction - 0.5f),
            seamLocalX = seam,
            majorityLeft = leftFraction > 0.5f,
        )
    }

    companion object {
        /** A horizontal handoff moves glyphs, while the physical camera coordinates stay fixed. */
        fun translated(endpoint: Endpoint, offset: Float): Endpoint = endpoint.copy(
            glyphs = endpoint.glyphs.map { it.copy(start = it.start + offset, end = it.end + offset) },
        )

        /** Resizing changes the virtual origin, while the physical camera remains stationary. */
        fun rebaseCamera(endpoint: Endpoint, viewWidth: Float, seam: Float): Endpoint =
            Endpoint(endpoint.glyphs.map { glyph ->
                val offset = seam - endpoint.seam
                glyph.copy(start = glyph.start + offset, end = glyph.end + offset)
            }, viewWidth, seam)

        /** Independent slots have no seam avoidance; only their origin is moved into island coordinates. */
        fun inIsland(endpoint: Endpoint, leftWidth: Float, rightWidth: Float, isRightSide: Boolean): Endpoint =
            Endpoint(endpoint.glyphs.map { glyph ->
                val offset = if (isRightSide) leftWidth else 0f
                glyph.copy(start = glyph.start + offset, end = glyph.end + offset)
            }, leftWidth + rightWidth, leftWidth)

        fun endpoint(
            units: SeamOcclusionLayout,
            textWidth: Float,
            viewWidth: Float,
            seam: Float,
            isAlignedRight: Boolean = false,
            centerIfPossible: Boolean = false,
            alignRight: Boolean = false,
            scrollOffset: Float = 0f,
            nextLineOnRight: Boolean = false,
        ): Endpoint {
            val layout = SpaceGateLineLayout(
                textWidth, viewWidth, if (nextLineOnRight && seam == 0f) null else units, seam,
                isAlignedRight, centerIfPossible, alignRight,
                nextLineOnRight = nextLineOnRight,
            )
            val origin = layout.textOrigin(scrollOffset)
            val plan = layout.plan(scrollOffset)
            val glyphs = ArrayList<Glyph>(units.drawingUnits.size)
            for (unit in units.drawingUnits) {
                val shift = origin + (plan?.shiftAt(unit.start) ?: 0f)
                val previous = glyphs.lastOrNull()
                // Paint assigns zero advance to surrogate tails and combining marks.
                // Keep them in the preceding drawing call instead of emitting broken UTF-16 glyphs.
                if (!unit.blank && unit.width == 0f && previous != null &&
                    !previous.blank && previous.charEnd == unit.charStart
                ) {
                    glyphs[glyphs.lastIndex] = previous.copy(charEnd = unit.charEnd)
                } else {
                    glyphs += Glyph(unit.charStart, unit.charEnd, unit.start, unit.start + shift, unit.end + shift, unit.blank, unit.end)
                }
            }
            return Endpoint(glyphs, viewWidth, seam)
        }

        fun lerp(from: Float, to: Float, fraction: Float): Float = when {
            fraction <= 0f -> from
            fraction >= 1f -> to
            else -> from + (to - from) * fraction
        }
    }
}
