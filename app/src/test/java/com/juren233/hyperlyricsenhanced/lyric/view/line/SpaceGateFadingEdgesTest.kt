/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpaceGateFadingEdgesTest {
    @Test
    fun `long line fades its overflowing right edge before scrolling`() {
        assertEquals(SpaceGateFadingEdges(0f, 0f, 1f), longLine(0f))
    }

    @Test
    fun `scrolling fades the outer edges and the camera edges`() {
        assertEquals(SpaceGateFadingEdges(1f, 1f, 1f), longLine(-100f))
        assertEquals(0.5f, longLine(-10f).camera, 0f)
    }

    @Test
    fun `stopped tail and words beside camera are fully visible`() {
        assertEquals(SpaceGateFadingEdges(1f, 0f, 0f), longLine(-200f))
        assertEquals(0.5f, longLine(-190f).camera, 0f)
        assertEquals(0.5f, longLine(-190f).outerRight, 0f)
    }

    @Test
    fun `camera fade does not jump at the first or final scrolling pixel`() {
        var previous = longLine(0f)
        for (step in 1..20_000) {
            val offset = -step / 100f
            val current = longLine(offset)
            assertTrue(abs(current.camera - previous.camera) <= 0.00051f)
            assertEquals(current, longLine(offset)) // seek to the same geometry is identical
            previous = current
        }
    }

    @Test
    fun `static line across camera is not faded just because it uses two slots`() {
        assertEquals(SpaceGateFadingEdges.NONE, SpaceGateFadingEdges.resolve(
            textWidth = 180f, scrollWidth = 180f, viewWidth = 200f,
            seam = 80f, offset = 0f, edgeLength = 20f, plan = null,
        ))
    }

    @Test
    fun `capacity overflow uses real band extent rather than synthetic travel`() {
        val text = "I got it"
        val units = requireNotNull(SeamOcclusionLayout.build(text, FloatArray(text.length) { 10f }))
        val geometry = SpaceGateLineLayout(80f, 90f, units, 35f)
        fun fade(offset: Float) = SpaceGateFadingEdges.resolve(
            textWidth = 80f, scrollWidth = geometry.scrollWidth, viewWidth = 90f,
            seam = 35f, offset = offset, edgeLength = 10f, plan = geometry.plan(offset),
        )
        assertTrue(geometry.isOverflow)
        // Only 5 px of actual text overflow, although the scroll range is a whole word.
        assertEquals(0.5f, fade(0f).outerRight, 0.001f)
        assertEquals(0f, fade(0f).camera, 0f)
        val end = -(geometry.scrollWidth - 90f)
        assertEquals(0f, fade(end).outerRight, 0.001f)
        assertEquals(0f, fade(end).camera, 0f)
        assertTrue(fade(end / 2f).camera > 0f)
    }

    @Test
    fun `loop ghost fades only the boundaries its real text crosses`() {
        val leaving = longLine(-350f, ghost = 220f)
        assertEquals(0f, leaving.outerRight, 0f)
        val entering = longLine(-350f, ghost = 170f)
        assertEquals(1f, entering.outerRight, 0f)
        val throughCamera = longLine(-550f, ghost = 50f)
        assertEquals(1f, throughCamera.camera, 0f)
        assertEquals(0f, throughCamera.outerLeft, 0f)
    }

    @Test
    fun `disabled fading and missing camera geometry retain their intended scope`() {
        assertEquals(SpaceGateFadingEdges.NONE, longLine(-100f, length = 0f))
        for (seam in listOf(0f, 200f)) {
            val fade = SpaceGateFadingEdges.resolve(
                textWidth = 400f, scrollWidth = 400f, viewWidth = 200f,
                seam = seam, offset = -100f, edgeLength = 20f, plan = null,
            )
            assertEquals(SpaceGateFadingEdges(1f, 0f, 1f), fade)
        }
    }

    private fun longLine(offset: Float, ghost: Float? = null, length: Float = 20f) =
        SpaceGateFadingEdges.resolve(
            textWidth = 400f, scrollWidth = 400f, viewWidth = 200f,
            seam = 80f, offset = offset, edgeLength = length, plan = null, ghostStart = ghost,
        )
}
