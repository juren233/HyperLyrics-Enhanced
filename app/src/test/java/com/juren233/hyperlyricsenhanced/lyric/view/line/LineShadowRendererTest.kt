/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineShadowRendererTest {
    @Test
    fun `word sync shadow follows the full line scroll offset without progress input`() {
        assertEquals(
            -36f,
            resolveShadowTextStartX(
                textWidth = 420f,
                viewWidth = 240f,
                scrollOffset = -36f,
                isPlainText = false,
                isAlignedRight = false,
                centerIfPossible = false,
                alignRight = false,
            ),
        )
    }

    @Test
    fun `short centered shadow uses the same line alignment`() {
        assertEquals(
            70f,
            resolveShadowTextStartX(
                textWidth = 100f,
                viewWidth = 240f,
                scrollOffset = 0f,
                isPlainText = false,
                isAlignedRight = false,
                centerIfPossible = true,
                alignRight = false,
            ),
        )
    }

    @Test
    fun `ghost shadow is emitted only after the primary marquee text leaves room`() {
        assertEquals(
            180f,
            resolveShadowGhostStartX(
                primaryStartX = -80f,
                textWidth = 220f,
                viewWidth = 200f,
                ghostSpacing = 40f,
                isPlainText = true,
            ),
        )
        assertNull(
            resolveShadowGhostStartX(
                primaryStartX = -20f,
                textWidth = 220f,
                viewWidth = 200f,
                ghostSpacing = 40f,
                isPlainText = true,
            )
        )
    }
}
