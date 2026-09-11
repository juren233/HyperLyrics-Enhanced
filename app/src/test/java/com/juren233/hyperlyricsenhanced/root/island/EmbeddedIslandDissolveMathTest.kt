/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedIslandDissolveMathTest {

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    @Test
    fun scaleColorAlphaKeepsFullAlphaUntouched() {
        val color = argb(255, 10, 20, 30)
        assertEquals(color, scaleColorAlpha(color, 255))
    }

    @Test
    fun scaleColorAlphaZeroClearsAlphaOnly() {
        assertEquals(argb(0, 10, 20, 30), scaleColorAlpha(argb(255, 10, 20, 30), 0))
    }

    @Test
    fun scaleColorAlphaHalvesAlpha() {
        assertEquals(argb(128, 10, 20, 30), scaleColorAlpha(argb(255, 10, 20, 30), 128))
    }

    @Test
    fun premultiplyRoundTripsWithinRounding() {
        val pixels = intArrayOf(
            argb(255, 200, 100, 50),
            argb(128, 200, 100, 50),
            // alpha=0 的像素会被有意清零 RGB（防模糊渗色），不能参与通道往返对比。
            argb(0, 0, 0, 0),
            argb(30, 255, 255, 255),
        )
        val roundTripped = premultiplyArgb(pixels.copyOf())
        unpremultiplyArgbInPlace(roundTripped)
        for (index in pixels.indices) {
            val original = pixels[index]
            val result = roundTripped[index]
            assertEquals(original ushr 24, result ushr 24)
            // 预乘/反预乘各带一次整数取整，允许每通道 ±1 的往返误差。
            assertTrue(kotlin.math.abs((original shr 16 and 0xFF) - (result shr 16 and 0xFF)) <= 1)
            assertTrue(kotlin.math.abs((original shr 8 and 0xFF) - (result shr 8 and 0xFF)) <= 1)
            assertTrue(kotlin.math.abs((original and 0xFF) - (result and 0xFF)) <= 1)
        }
    }

    @Test
    fun premultiplyFullyTransparentPixelStaysZero() {
        val pixels = intArrayOf(argb(0, 200, 100, 50))
        val premultiplied = premultiplyArgb(pixels.copyOf())
        assertEquals(0, premultiplied[0])
        unpremultiplyArgbInPlace(premultiplied)
        assertEquals(argb(0, 0, 0, 0), premultiplied[0])
    }
}
