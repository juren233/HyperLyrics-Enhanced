/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.display

import org.junit.Assert.assertEquals
import org.junit.Test

class FontColorModeTest {
    @Test
    fun `custom mode takes precedence over monet and legacy cover switches`() {
        assertEquals(
            FONT_COLOR_MODE_CUSTOM,
            resolveFontColorMode(
                customEnabled = true,
                monetEnabled = true,
                coverEnabled = true,
                coverGradient = true,
            )
        )
    }

    @Test
    fun `monet mode is the second option and takes precedence over cover colors`() {
        assertEquals(1, FONT_COLOR_MODE_MONET)
        assertEquals(
            FONT_COLOR_MODE_MONET,
            resolveFontColorMode(
                customEnabled = false,
                monetEnabled = true,
                coverEnabled = true,
                coverGradient = true,
            )
        )
    }

    @Test
    fun `legacy switches still restore existing user selections`() {
        assertEquals(FONT_COLOR_MODE_DEFAULT, resolveFontColorMode(false, false, false, true))
        assertEquals(FONT_COLOR_MODE_COVER, resolveFontColorMode(false, false, true, false))
        assertEquals(FONT_COLOR_MODE_COVER_GRADIENT, resolveFontColorMode(false, false, true, true))
    }
}
