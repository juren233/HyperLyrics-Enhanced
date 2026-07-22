package com.juren233.hyperlyricsenhanced.common.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjacentTranslationPolicyTest {

    @Test
    fun `allows verbatim mode when only the right side is lyrics`() {
        assertTrue(AdjacentTranslationPolicy.isEligible(0, 5, 7))
        assertEquals(true, AdjacentTranslationPolicy.targetIsLeft(5, 7))
    }

    @Test
    fun `allows verbatim mode when only the left side is lyrics`() {
        assertTrue(AdjacentTranslationPolicy.isEligible(0, 7, 0))
        assertEquals(false, AdjacentTranslationPolicy.targetIsLeft(7, 0))
    }

    @Test
    fun `rejects separated lyric mode`() {
        assertFalse(AdjacentTranslationPolicy.isEligible(1, 5, 7))
    }

    @Test
    fun `rejects layouts with zero or two lyric sides`() {
        assertFalse(AdjacentTranslationPolicy.isEligible(0, 5, 6))
        assertFalse(AdjacentTranslationPolicy.isEligible(0, 7, 7))
    }
}
