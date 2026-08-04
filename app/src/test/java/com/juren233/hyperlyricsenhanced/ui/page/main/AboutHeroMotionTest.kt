/*
 * Copyright 2026 juren233
 * Licensed under the GNU General Public License v3.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.main

import org.junit.Assert.assertEquals
import org.junit.Test

class AboutHeroMotionTest {
    @Test
    fun `logo follows about page from the right`() {
        assertEquals(1200f, aboutLogoTranslationX(pageOffsetFraction = -1f, pageWidthPx = 1200), 0f)
        assertEquals(600f, aboutLogoTranslationX(pageOffsetFraction = -0.5f, pageWidthPx = 1200), 0f)
        assertEquals(0f, aboutLogoTranslationX(pageOffsetFraction = 0f, pageWidthPx = 1200), 0f)
    }

    @Test
    fun `long app name receives the full width between screen margins`() {
        assertEquals(1122, aboutNameContainerWidthPx(screenWidthPx = 1200, sideMarginPx = 39))
        assertEquals(0, aboutNameContainerWidthPx(screenWidthPx = 40, sideMarginPx = 24))
    }
}
