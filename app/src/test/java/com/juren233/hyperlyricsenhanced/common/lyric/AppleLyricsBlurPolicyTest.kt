package com.juren233.hyperlyricsenhanced.common.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsBlurPolicyTest {
    @Test
    fun `normalizes the three supported modes`() {
        assertEquals(AppleLyricsBlurPolicy.OFF, AppleLyricsBlurPolicy.normalizeMode(-1))
        assertEquals(AppleLyricsBlurPolicy.NATIVE, AppleLyricsBlurPolicy.normalizeMode(1))
        assertEquals(
            AppleLyricsBlurPolicy.ADVANCED_MATERIAL,
            AppleLyricsBlurPolicy.normalizeMode(99),
        )
    }

    @Test
    fun `advanced material default uses direct pixel values`() {
        val expected = listOf(0, 13, 16, 20, 23, 26, 26)

        assertEquals(
            expected,
            (0..6).map {
                AppleLyricsBlurPolicy.advancedMaterialBlurRadiusPx(
                    rowDistance = it,
                    minRadiusPx = 13,
                    maxRadiusPx = 26,
                )
            },
        )
    }

    @Test
    fun `advanced material custom pixel range normalizes reversed bounds`() {
        assertEquals(
            listOf(0, 10, 15, 20, 25, 30, 30),
            (0..6).map {
                AppleLyricsBlurPolicy.advancedMaterialBlurRadiusPx(
                    rowDistance = it,
                    minRadiusPx = 10,
                    maxRadiusPx = 30,
                )
            },
        )
        assertEquals(
            15,
            AppleLyricsBlurPolicy.advancedMaterialBlurRadiusPx(
                rowDistance = 2,
                minRadiusPx = 30,
                maxRadiusPx = 10,
            ),
        )
    }

    @Test
    fun `native defaults remain dp based and density aware`() {
        assertEquals(
            listOf(0, 10, 13, 16, 20, 21, 21),
            (0..6).map {
                AppleLyricsBlurPolicy.nativeBlurRadiusPx(
                    rowDistance = it,
                    minRadiusDp = 3f,
                    maxRadiusDp = 6.5f,
                    density = 3.25f,
                )
            },
        )
    }

    @Test
    fun `short intro without an indicator stays blurred until the first line begins`() {
        assertTrue(AppleLyricsBlurPolicy.shouldBlurBeforeFirstLine(0L, 6_500L))
        assertTrue(AppleLyricsBlurPolicy.shouldBlurBeforeFirstLine(6_499L, 6_500L))
        assertEquals(
            13,
            AppleLyricsBlurPolicy.beforeFirstLineAdvancedMaterialBlurRadiusPx(
                visibleRowIndex = 0,
                minRadiusPx = 13,
                maxRadiusPx = 26,
            ),
        )
        assertEquals(
            26,
            AppleLyricsBlurPolicy.beforeFirstLineAdvancedMaterialBlurRadiusPx(
                visibleRowIndex = 100,
                minRadiusPx = 13,
                maxRadiusPx = 26,
            ),
        )
    }

    @Test
    fun `first line and invalid timing do not use the pre first line state`() {
        assertTrue(!AppleLyricsBlurPolicy.shouldBlurBeforeFirstLine(6_500L, 6_500L))
        assertTrue(!AppleLyricsBlurPolicy.shouldBlurBeforeFirstLine(6_501L, 6_500L))
        assertTrue(!AppleLyricsBlurPolicy.shouldBlurBeforeFirstLine(null, 6_500L))
        assertTrue(!AppleLyricsBlurPolicy.shouldBlurBeforeFirstLine(0L, null))
        assertTrue(!AppleLyricsBlurPolicy.shouldBlurBeforeFirstLine(-1L, 6_500L))
    }
}
