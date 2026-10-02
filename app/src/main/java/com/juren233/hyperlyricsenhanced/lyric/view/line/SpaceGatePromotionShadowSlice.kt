/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

/** Reused bounds map one immutable mask slice onto the same moving glyph interval as the text. */
internal class SpaceGatePromotionShadowSlice {
    var sourceLeft = 0
        private set
    var sourceRight = 0
        private set
    var left = 0f
        private set
    var right = 0f
        private set

    fun update(
        naturalStart: Float, naturalEnd: Float, frameStart: Float, frameEnd: Float,
        textOrigin: Int, bitmapWidth: Int, first: Boolean, last: Boolean, dx: Float,
    ): Boolean {
        val width = naturalEnd - naturalStart
        if (width <= 0f || frameEnd <= frameStart) return false
        sourceLeft = if (first) 0 else (naturalStart + textOrigin).toInt().coerceIn(0, bitmapWidth)
        sourceRight = if (last) bitmapWidth else (naturalEnd + textOrigin).toInt().coerceIn(0, bitmapWidth)
        if (sourceRight <= sourceLeft) return false
        val scale = (frameEnd - frameStart) / width
        left = frameStart + (sourceLeft - textOrigin - naturalStart) * scale + dx
        right = left + (sourceRight - sourceLeft) * scale
        return true
    }
}
