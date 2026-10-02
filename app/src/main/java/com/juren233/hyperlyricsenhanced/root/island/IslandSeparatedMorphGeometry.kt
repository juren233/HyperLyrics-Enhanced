/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.lyric.view.line.SpaceGatePromotionGeometry

/** Independent text halves keep their own clipping window while travelling through the camera. */
internal object IslandSeparatedMorphGeometry {
    data class Clip(val start: Float, val end: Float) {
        fun shifted(offset: Float) = Clip(start + offset, end + offset)
        fun towards(target: Clip, fraction: Float) = Clip(
            SpaceGatePromotionGeometry.lerp(start, target.start, fraction),
            SpaceGatePromotionGeometry.lerp(end, target.end, fraction),
        )
    }

    fun clip(leftWidth: Float, rightWidth: Float, right: Boolean): Clip =
        if (right) Clip(leftWidth, leftWidth + rightWidth) else Clip(0f, leftWidth)

    fun shouldStart(oldLeft: Boolean?, oldRight: Boolean?, split: Boolean, preview: Boolean): Boolean =
        oldLeft != null && oldLeft == oldRight && (oldLeft != split || preview)

    fun matches(a: SpaceGatePromotionGeometry.Endpoint, b: SpaceGatePromotionGeometry.Endpoint): Boolean =
        a.glyphs.size == b.glyphs.size && a.glyphs.zip(b.glyphs).all { (x, y) ->
            x.charStart == y.charStart && x.charEnd == y.charEnd
        }

    fun gatherRight(
        endpoint: SpaceGatePromotionGeometry.Endpoint,
        advanceBefore: Float,
        totalAdvance: Float,
        leftWidth: Float,
        rightWidth: Float,
    ): SpaceGatePromotionGeometry.Endpoint {
        val scale = (rightWidth / totalAdvance.coerceAtLeast(1f)).coerceAtMost(1f)
        val origin = endpoint.glyphs.firstOrNull()?.naturalStart ?: 0f
        return SpaceGatePromotionGeometry.Endpoint(endpoint.glyphs.map {
            it.copy(
                start = leftWidth + (advanceBefore + it.naturalStart - origin) * scale,
                end = leftWidth + (advanceBefore + it.naturalEnd - origin) * scale,
            )
        }, leftWidth + rightWidth, leftWidth)
    }

    /** Never split a surrogate pair, combining sequence, or grouped punctuation glyph. */
    fun slice(source: SpaceGatePromotionGeometry.Endpoint, from: Int, until: Int): SpaceGatePromotionGeometry.Endpoint? {
        if (from < 0 || until <= from) return null
        val glyphs = source.glyphs.filter { it.charStart >= from && it.charEnd <= until }
        if (glyphs.firstOrNull()?.charStart != from || glyphs.lastOrNull()?.charEnd != until) return null
        return source.copy(glyphs = glyphs.map {
            it.copy(charStart = it.charStart - from, charEnd = it.charEnd - from)
        })
    }
}
