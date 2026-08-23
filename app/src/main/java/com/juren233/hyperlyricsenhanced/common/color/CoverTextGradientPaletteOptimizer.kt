/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.color

/** Keeps cover-derived text gradients colourful without allowing extreme endpoint chroma. */
internal object CoverTextGradientPaletteOptimizer {
    private const val CHROMA_COMPRESSION_THRESHOLD = 0.14
    private const val CHROMA_COMPRESSION_LIMIT = 0.18

    fun optimize(colors: IntArray): IntArray {
        if (colors.isEmpty()) return colors
        val start = optimizeColor(colors.first())
        if (colors.size == 1) return intArrayOf(start)
        val end = optimizeColor(colors.last())
        return PerceptualGradient.threeColorAnchors(start, end)
    }

    internal fun optimizeColor(color: Int): Int = PerceptualGradient.compressChroma(
        color = color,
        threshold = CHROMA_COMPRESSION_THRESHOLD,
        limit = CHROMA_COMPRESSION_LIMIT,
    )
}
