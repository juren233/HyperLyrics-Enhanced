/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.color

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverTextGradientPaletteOptimizerTest {
    @Test
    fun `restrained neutral color remains byte-identical`() {
        val color = 0xFF777777.toInt()

        assertEquals(color, CoverTextGradientPaletteOptimizer.optimizeColor(color))
    }

    @Test
    fun `high chroma color is softly compressed below the configured ceiling`() {
        val color = 0xFFFF0000.toInt()
        val optimized = CoverTextGradientPaletteOptimizer.optimizeColor(color)

        assertTrue(PerceptualGradient.oklchChroma(optimized) < PerceptualGradient.oklchChroma(color))
        assertTrue(PerceptualGradient.oklchChroma(optimized) <= 0.181)
        assertTrue(PerceptualGradient.oklchChroma(optimized) >= 0.14)
    }

    @Test
    fun `alpha is preserved while chroma is compressed`() {
        val color = 0x80FF00FF.toInt()
        val optimized = CoverTextGradientPaletteOptimizer.optimizeColor(color)

        assertEquals(color ushr 24, optimized ushr 24)
    }

    @Test
    fun `gradient midpoint is regenerated from optimized endpoints`() {
        val colors = intArrayOf(
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
            0xFF0000FF.toInt(),
        )
        val optimizedStart = CoverTextGradientPaletteOptimizer.optimizeColor(colors.first())
        val optimizedEnd = CoverTextGradientPaletteOptimizer.optimizeColor(colors.last())

        assertArrayEquals(
            PerceptualGradient.threeColorAnchors(optimizedStart, optimizedEnd),
            CoverTextGradientPaletteOptimizer.optimize(colors),
        )
    }
}
