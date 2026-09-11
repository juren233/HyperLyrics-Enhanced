/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IslandBackgroundColorResolverTest {

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private fun uniformPixels(width: Int, height: Int, color: Int): IntArray =
        IntArray(width * height) { color }

    @Test
    fun averagesTheCentralRegionOfUniformOpaquePixels() {
        val width = 32
        val height = 32
        val color = argb(255, 0x1C, 0x1C, 0x1E)
        assertEquals(
            color,
            averageOpaqueSampledColor(uniformPixels(width, height, color), width, height),
        )
    }

    @Test
    fun averagesOnlyOpaquePixelsWhenEdgesAreTranslucent() {
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val insideCentralRegion = x in 2 until width - 2 && y in 2 until height - 2
            if (insideCentralRegion) argb(255, 10, 20, 30) else argb(0, 10, 20, 30)
        }
        assertEquals(argb(255, 10, 20, 30), averageOpaqueSampledColor(pixels, width, height))
    }

    @Test
    fun mixesCentralOpaquePixelsByChannel() {
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val insideCentralRegion = x in 2 until width - 2 && y in 2 until height - 2
            if (x < width / 2) {
                if (insideCentralRegion) argb(255, 0, 0, 0) else argb(0, 0, 0, 0)
            } else {
                if (insideCentralRegion) argb(255, 40, 80, 120) else argb(0, 40, 80, 120)
            }
        }
        // 中央区 16 个不透明像素：黑色 8 个 + (40,80,120) 8 个，均值 (20,40,60)。
        assertEquals(argb(255, 20, 40, 60), averageOpaqueSampledColor(pixels, width, height))
    }

    @Test
    fun returnsNullWhenNoOpaquePixelRemains() {
        val width = 8
        val height = 8
        assertNull(
            averageOpaqueSampledColor(
                uniformPixels(width, height, argb(120, 0, 0, 0)),
                width,
                height,
            ),
        )
    }

    @Test
    fun returnsNullForInvalidInputs() {
        assertNull(averageOpaqueSampledColor(IntArray(0), 0, 8))
        assertNull(averageOpaqueSampledColor(IntArray(4), 8, 8))
    }
}
