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

class PerceptualGradientTest {
    @Test
    fun `complementary cover colors generate a chromatic midpoint instead of gray`() {
        val anchors = PerceptualGradient.threeColorAnchors(
            0xFFFF0000.toInt(),
            0xFF00FFFF.toInt(),
        )

        assertEquals(3, anchors.size)
        assertEquals(0xFFFF0000.toInt(), anchors.first())
        assertEquals(0xFF00FFFF.toInt(), anchors.last())
        assertTrue(channelRange(anchors[1]) >= 64)
        assertTrue(PerceptualGradient.oklchChroma(anchors[1]) >= 0.08)
    }

    @Test
    fun `blue and yellow generate a chromatic middle anchor`() {
        val anchors = PerceptualGradient.threeColorAnchors(
            0xFF0057FF.toInt(),
            0xFFFFD600.toInt(),
        )

        assertEquals(3, anchors.size)
        assertTrue(PerceptualGradient.oklchChroma(anchors[1]) >= 0.04)
    }

    @Test
    fun `neutral artwork remains neutral instead of inventing a hue`() {
        val anchors = PerceptualGradient.threeColorAnchors(
            0xFF444444.toInt(),
            0xFFD8D8D8.toInt(),
        )
        val midpoint = anchors[1]

        assertTrue(channelRange(midpoint) <= 2)
    }

    @Test
    fun `identical endpoints stay a single color`() {
        assertArrayEquals(
            intArrayOf(0xFF336699.toInt()),
            PerceptualGradient.threeColorAnchors(
                0xFF336699.toInt(),
                0xFF336699.toInt(),
            )
        )
    }

    private fun channelRange(color: Int): Int {
        val channels = intArrayOf(
            (color ushr 16) and 0xFF,
            (color ushr 8) and 0xFF,
            color and 0xFF,
        )
        return channels.max() - channels.min()
    }
}
