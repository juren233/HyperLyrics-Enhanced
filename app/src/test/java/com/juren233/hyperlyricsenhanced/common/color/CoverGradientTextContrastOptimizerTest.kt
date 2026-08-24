/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.color

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverGradientTextContrastOptimizerTest {
    @Test
    fun `optimization is limited to cover color modes over a gradient cover background`() {
        assertTrue(
            CoverGradientTextContrastOptimizer.shouldOptimize(
                useCustomColor = false,
                useMonetColor = false,
                useCoverColor = true,
                gradientCoverBackgroundActive = true,
                hasArtwork = true,
            ),
        )
        assertFalse(
            CoverGradientTextContrastOptimizer.shouldOptimize(
                useCustomColor = true,
                useMonetColor = false,
                useCoverColor = true,
                gradientCoverBackgroundActive = true,
                hasArtwork = true,
            ),
        )
        assertFalse(
            CoverGradientTextContrastOptimizer.shouldOptimize(
                useCustomColor = false,
                useMonetColor = true,
                useCoverColor = true,
                gradientCoverBackgroundActive = true,
                hasArtwork = true,
            ),
        )
        assertFalse(
            CoverGradientTextContrastOptimizer.shouldOptimize(
                useCustomColor = false,
                useMonetColor = false,
                useCoverColor = true,
                gradientCoverBackgroundActive = false,
                hasArtwork = true,
            ),
        )
    }

    @Test
    fun `samples the visible center-crop right edge instead of physical image edge`() {
        val width = 8
        val height = 4
        val pixels = IntArray(width * height) { 0xFF101010.toInt() }
        // Center-crop is x=2..5, so x=5 is the visible edge and x=7 must be ignored.
        for (y in 0 until height) {
            pixels[y * width + 5] = 0xFF8090A0.toInt()
            pixels[y * width + 7] = 0xFFFFFFFF.toInt()
        }

        val anchors = CoverGradientTextContrastOptimizer.sampleBackgroundAnchors(
            width = width,
            height = height,
            pixelAt = { x, y -> pixels[y * width + x] },
        )

        assertEquals(3, anchors.size)
        assertTrue(((anchors[0] ushr 16) and 0xFF) < 0x80)
        assertTrue(((anchors[0] ushr 16) and 0xFF) > 0x30)
        assertEquals(0xFF000000.toInt(), anchors.last())
    }

    @Test
    fun `similar cover text receives a bounded shift in the background safe direction`() {
        val text = intArrayOf(0xFF80858A.toInt())
        val background = intArrayOf(
            0xFF81868B.toInt(),
            0xFF3A3C3E.toInt(),
            0xFF000000.toInt(),
        )

        val result = CoverGradientTextContrastOptimizer.optimize(text, background)

        assertTrue(result.applied)
        assertTrue(abs(result.lightnessDelta) <= 0.1001)
        assertTrue(result.lightnessDelta > 0.0)
        assertTrue(result.minimumContrastAfter >= result.minimumContrastBefore + 0.05)
        assertTrue(result.minimumContrastAfter <= 1.90)
        assertEquals(text[0] ushr 24, result.colors[0] ushr 24)
    }

    @Test
    fun `dark gradient tail makes the reported 587 palette brighten instead of darken`() {
        val text = intArrayOf(0xFF8BB593.toInt())
        val background = intArrayOf(
            0xFF89B7BD.toInt(),
            0xFF3E5255.toInt(),
            0xFF000000.toInt(),
        )
        val beforeLightness = PerceptualGradient.oklchLightness(text[0])

        val result = CoverGradientTextContrastOptimizer.optimize(text, background)

        assertTrue(result.applied)
        assertEquals(
            CoverGradientTextContrastOptimizer.DirectionPolicy.BRIGHTEN_FOR_DARK_TAIL,
            result.directionPolicy,
        )
        assertTrue(result.lightnessDelta > 0.0)
        assertTrue(PerceptualGradient.oklchLightness(result.colors[0]) > beforeLightness)
        assertTrue(result.lightnessDelta <= 0.1001)
        assertTrue(result.minimumBackgroundLuminance <= 0.001)
    }

    @Test
    fun `only a uniformly light background permits darkening`() {
        val text = intArrayOf(0xFFC8C8C8.toInt())
        val background = intArrayOf(
            0xFFC9C9C9.toInt(),
            0xFFBDBDBD.toInt(),
        )

        val result = CoverGradientTextContrastOptimizer.optimize(text, background)

        assertTrue(result.applied)
        assertEquals(
            CoverGradientTextContrastOptimizer.DirectionPolicy.DARKEN_FOR_UNIFORMLY_LIGHT_BACKGROUND,
            result.directionPolicy,
        )
        assertTrue(result.lightnessDelta < 0.0)
        assertTrue(result.minimumContrastAfter > result.minimumContrastBefore)
    }

    @Test
    fun `perceptually different colors are not changed merely for sharing luminance`() {
        val text = intArrayOf(0xFFD73535.toInt())
        val background = intArrayOf(0xFF268E66.toInt())

        val result = CoverGradientTextContrastOptimizer.optimize(text, background)

        assertFalse(result.applied)
        assertArrayEquals(text, result.colors)
    }

    @Test
    fun `already separated cover colors remain byte-identical`() {
        val text = intArrayOf(0xFFE9C9FF.toInt())
        val background = intArrayOf(0xFF38263F.toInt())

        val result = CoverGradientTextContrastOptimizer.optimize(text, background)

        assertFalse(result.applied)
        assertArrayEquals(text, result.colors)
    }

    @Test
    fun `one uniform lightness delta preserves gradient relationships`() {
        val text = intArrayOf(
            0xFF767B80.toInt(),
            0xFF7B8085.toInt(),
            0xFF80858A.toInt(),
        )
        val background = intArrayOf(
            0xFF777C81.toInt(),
            0xFF3A3D40.toInt(),
            0xFF000000.toInt(),
        )
        val beforeLightness = text.map(PerceptualGradient::oklchLightness)

        val result = CoverGradientTextContrastOptimizer.optimize(text, background)
        val afterLightness = result.colors.map(PerceptualGradient::oklchLightness)

        assertTrue(result.applied)
        for (index in text.indices) {
            assertEquals(
                result.lightnessDelta,
                afterLightness[index] - beforeLightness[index],
                0.006,
            )
        }
    }

    @Test
    fun `empty inputs are left untouched`() {
        val result = CoverGradientTextContrastOptimizer.optimize(intArrayOf(), intArrayOf())

        assertFalse(result.applied)
        assertEquals(0, result.colors.size)
    }
}
