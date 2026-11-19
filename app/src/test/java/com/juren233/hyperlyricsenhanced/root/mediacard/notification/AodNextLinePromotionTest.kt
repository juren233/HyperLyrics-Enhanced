/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import com.juren233.hyperlyricsenhanced.lyric.view.NextLinePromotionMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodNextLinePromotionTest {

    private fun promote(
        enabled: Boolean = true,
        showNextLyric: Boolean = true,
        rowsBetweenBlank: Boolean = true,
        wasPreview: Boolean = true,
        currentMainText: String? = "line one",
        previewText: String? = "line two",
        nextMainText: String? = "line two",
        lineAdvanced: Boolean = true,
        attached: Boolean = true,
        mainHeight: Int = 40,
        nextHeight: Int = 30,
    ) = AodNextLinePromotion.shouldPromote(
        enabled, showNextLyric, rowsBetweenBlank, wasPreview, currentMainText,
        previewText, nextMainText, lineAdvanced, attached, mainHeight, nextHeight,
    )

    @Test
    fun `promotes when the shown next line becomes the main line`() {
        assertTrue(promote())
    }

    @Test
    fun `switch off or next lyric hidden never promotes`() {
        assertFalse(promote(enabled = false))
        assertFalse(promote(showNextLyric = false))
    }

    @Test
    fun `rows between main and next block the promotion`() {
        assertFalse(promote(rowsBetweenBlank = false))
    }

    @Test
    fun `only a real advance to the previewed line promotes`() {
        assertFalse(promote(lineAdvanced = false))
        assertFalse(promote(nextMainText = "something else"))
        assertFalse(promote(wasPreview = false))
    }

    @Test
    fun `unmeasured or detached views do not promote`() {
        assertFalse(promote(attached = false))
        assertFalse(promote(mainHeight = 0))
        assertFalse(promote(nextHeight = 0))
    }

    @Test
    fun `scale follows the text size ratio within the shared clamp`() {
        assertEquals(1.5f, NextLinePromotionMotion.targetScale(30f, 20f), 0.001f)
        assertEquals(2f, NextLinePromotionMotion.targetScale(100f, 10f), 0.001f)
        assertEquals(0.5f, NextLinePromotionMotion.targetScale(10f, 100f), 0.001f)
    }
}
