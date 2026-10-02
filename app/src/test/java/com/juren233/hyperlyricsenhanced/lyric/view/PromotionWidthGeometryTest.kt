/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromotionWidthGeometryTest {
    @Test
    fun `growing preview reserves width for every alignment`() {
        val reserved = PromotionWidthGeometry.reserveWidth(
            pendingWidth = 304,
            currentWidth = 208f,
            hugContentWidth = true,
            scaledPreviewWidth = 297f,
            targetTextWidth = 304f
        )
        assertEquals(304, reserved)
    }

    @Test
    fun `content that fits old width does not reserve`() {
        assertNull(PromotionWidthGeometry.reserveWidth(
            pendingWidth = 304,
            currentWidth = 208f,
            hugContentWidth = true,
            scaledPreviewWidth = 180f,
            targetTextWidth = 190f
        ))
    }

    @Test
    fun `new space follows left center and right anchors`() {
        assertEquals(0f, PromotionWidthGeometry.childOffset(304, 208f,
            PromotionWidthGeometry.placementFactor(false, false, false)), 0f)
        assertEquals(48f, PromotionWidthGeometry.childOffset(304, 208f,
            PromotionWidthGeometry.placementFactor(false, true, false)), 0f)
        assertEquals(96f, PromotionWidthGeometry.childOffset(304, 208f,
            PromotionWidthGeometry.placementFactor(true, false, false)), 0f)
        assertEquals(96f, PromotionWidthGeometry.childOffset(304, 208f,
            PromotionWidthGeometry.placementFactor(false, false, true)), 0f)
    }
}
