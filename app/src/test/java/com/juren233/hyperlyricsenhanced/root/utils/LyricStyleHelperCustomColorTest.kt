/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class LyricStyleHelperCustomColorTest {
    @Test
    fun `custom font color uses the selected color and a seventy five percent background alpha`() {
        val palette = LyricStyleHelper.customTextColorPalette(0xCC3366FF.toInt())

        assertArrayEquals(intArrayOf(0xCC3366FF.toInt()), palette.primary)
        assertArrayEquals(intArrayOf(0x993366FF.toInt()), palette.background)
        assertArrayEquals(intArrayOf(0xCC3366FF.toInt()), palette.highlight)
    }

    @Test
    fun `opaque custom font color stays opaque for primary and highlight`() {
        val palette = LyricStyleHelper.customTextColorPalette(0xFF80C0FF.toInt())

        assertArrayEquals(intArrayOf(0xFF80C0FF.toInt()), palette.primary)
        assertArrayEquals(intArrayOf(0xBF80C0FF.toInt()), palette.background)
        assertArrayEquals(intArrayOf(0xFF80C0FF.toInt()), palette.highlight)
    }
}
