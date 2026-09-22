package com.juren233.hyperlyricsenhanced.common.lyric

import com.juren233.hyperlyricsenhanced.common.RootConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjacentTranslationPolicyTest {

    @Test
    fun `allows single side mode when only the right side is lyrics`() {
        assertTrue(AdjacentTranslationPolicy.isEligible(RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE, 5, 7))
        assertEquals(true, AdjacentTranslationPolicy.targetIsLeft(5, 7))
    }

    @Test
    fun `allows single side mode when only the left side is lyrics`() {
        assertTrue(AdjacentTranslationPolicy.isEligible(RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE, 7, 0))
        assertEquals(false, AdjacentTranslationPolicy.targetIsLeft(7, 0))
    }

    @Test
    fun `rejects both dual slot lyric modes`() {
        assertFalse(AdjacentTranslationPolicy.isEligible(RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND, 5, 7))
        assertFalse(AdjacentTranslationPolicy.isEligible(RootConstants.HOOK_LYRIC_MODE_SEPARATED, 5, 7))
    }

    @Test
    fun `rejects layouts with zero or two lyric sides`() {
        assertFalse(AdjacentTranslationPolicy.isEligible(RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE, 5, 6))
        assertFalse(AdjacentTranslationPolicy.isEligible(RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE, 7, 7))
    }
}
