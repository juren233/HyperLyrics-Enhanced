/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.lyric.view.line.SeamOcclusionLayout
import com.juren233.hyperlyricsenhanced.lyric.view.line.SpaceGatePromotionGeometry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandGaplessSeamTest {
    @Test fun `only a continuous full island band without a top cutout is gapless`() {
        assertTrue(IslandGaplessSeam.decide(true, true, true, hasTopCutout = false))
        assertFalse(IslandGaplessSeam.decide(true, true, true, hasTopCutout = true))
        // Unknown display state keeps the native gap.
        assertFalse(IslandGaplessSeam.decide(true, true, true, hasTopCutout = null))
        assertFalse(IslandGaplessSeam.decide(false, true, true, hasTopCutout = false))
        // Independent slots (song info, metadata) keep the native gap.
        assertFalse(IslandGaplessSeam.decide(true, true, false, hasTopCutout = false))
        assertFalse(IslandGaplessSeam.decide(true, false, false, hasTopCutout = false))
    }

    @Test fun `promotion glyphs do not fade at a gapless seam`() {
        // One glyph spanning 20..40 with the seam at 30.
        fun endpoint(occluding: Boolean) = SpaceGatePromotionGeometry.Endpoint(
            listOf(SpaceGatePromotionGeometry.Glyph(0, 1, 0f, 20f, 40f, blank = false)),
            viewWidth = 200f, seam = 30f, occluding = occluding,
        )
        assertNotNull(SpaceGatePromotionGeometry(endpoint(true), endpoint(true)).fade(0, 1f))
        assertNull(SpaceGatePromotionGeometry(endpoint(false), endpoint(false)).fade(0, 1f))
        // Endpoints built from gapless units carry the flag.
        val units = requireNotNull(SeamOcclusionLayout.build("你好", floatArrayOf(20f, 20f), occluding = false))
        assertFalse(SpaceGatePromotionGeometry.endpoint(units, 40f, 200f, 30f).occluding)
    }
}
