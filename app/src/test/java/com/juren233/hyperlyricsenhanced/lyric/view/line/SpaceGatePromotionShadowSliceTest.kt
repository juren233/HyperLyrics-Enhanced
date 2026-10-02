/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceGatePromotionShadowSliceTest {
    @Test
    fun `shadow stretches over the same moving interval with a fixed physical offset`() {
        val slice = SpaceGatePromotionShadowSlice()
        assertTrue(slice.update(6f, 18f, 100f, 124f, 8, 54, false, false, 2f))
        assertEquals(14, slice.sourceLeft)
        assertEquals(26, slice.sourceRight)
        assertEquals(102f, slice.left, 0f)
        assertEquals(126f, slice.right, 0f)
    }

    @Test
    fun `adjacent landed slices tile the cached mask without dark overlap or missing blur edges`() {
        val slice = SpaceGatePromotionShadowSlice()
        val boundaries = listOf(0f, 10.3f, 22.8f, 30f)
        var previousSource = 0
        var previousRight = 65f // 70px text origin - 6px mask padding + 1px shadow offset.
        for (index in 0..2) {
            val a = boundaries[index]
            val b = boundaries[index + 1]
            assertTrue(slice.update(a, b, 70f + a, 70f + b, 6, 42, index == 0, index == 2, 1f))
            assertEquals(previousSource, slice.sourceLeft)
            assertEquals(previousRight, slice.left, 0.0001f)
            previousSource = slice.sourceRight
            previousRight = slice.right
        }
        assertEquals(42, previousSource)
        assertEquals(107f, previousRight, 0.0001f)
    }

    @Test
    fun `resizing and camera rebasing move shadow by exactly the glyph displacement`() {
        val slice = SpaceGatePromotionShadowSlice()
        assertTrue(slice.update(0f, 12f, 40f, 49f, 5, 22, true, true, 0f))
        val left = slice.left
        val right = slice.right
        assertTrue(slice.update(0f, 12f, 73f, 82f, 5, 22, true, true, 0f))
        assertEquals(left + 33f, slice.left, 0f)
        assertEquals(right + 33f, slice.right, 0f)
        assertFalse(slice.update(0f, 0f, 1f, 2f, 5, 22, false, false, 0f))
    }
}
