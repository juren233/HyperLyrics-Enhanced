/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

/** Extra travel is presentation-only. It never changes the current line's timed advance. */
internal object SpaceGateRightPreviewGeometry {
    // Keep a fixed focus slightly inside the left slot, clear of the camera. The
    // independent right slot retains its midpoint beside the song information.
    fun anchor(viewWidth: Float, seam: Float): Float =
        if (seam > 0f && seam < viewWidth) seam * 0.85f else viewWidth / 2f

    fun travel(textWidth: Float, viewWidth: Float, seam: Float): Float =
        (textWidth - anchor(viewWidth, seam)).coerceAtLeast(0f)

    fun previewStart(
        currentEnd: Float,
        viewWidth: Float,
        seam: Float,
        textSize: Float,
        reveal: Float,
    ): Float {
        val gap = minOf(textSize * 0.65f, (viewWidth - seam) * 0.15f).coerceAtLeast(0f)
        val target = maxOf(currentEnd + gap, seam + gap)
        // A short, already fully visible current line leaves spare room. Slide the
        // preview into that room as progress advances instead of popping it in.
        return maxOf(target, viewWidth + (target - viewWidth) * reveal.coerceIn(0f, 1f))
    }
}
