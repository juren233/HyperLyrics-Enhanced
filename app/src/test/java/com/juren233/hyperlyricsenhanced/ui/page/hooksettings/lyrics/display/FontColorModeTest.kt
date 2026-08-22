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
    fun `custom mode takes precedence over legacy cover switches`() {
        assertEquals(
            FONT_COLOR_MODE_CUSTOM,
            resolveFontColorMode(
                customEnabled = true,
                coverEnabled = true,
                coverGradient = true,
            )
        )
    }

    @Test
    fun `legacy switches still restore existing user selections`() {
        assertEquals(FONT_COLOR_MODE_DEFAULT, resolveFontColorMode(false, false, true))
        assertEquals(FONT_COLOR_MODE_COVER, resolveFontColorMode(false, true, false))
        assertEquals(FONT_COLOR_MODE_COVER_GRADIENT, resolveFontColorMode(false, true, true))
    }
}
