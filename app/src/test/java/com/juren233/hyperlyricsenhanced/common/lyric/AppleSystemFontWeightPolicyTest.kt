package com.juren233.hyperlyricsenhanced.common.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleSystemFontWeightPolicyTest {
    @Test
    fun `replaces only Apple Music primary text font resources`() {
        listOf("regular", "medium", "semibold", "bold", "black").forEach { resourceName ->
            assertTrue(
                AppleSystemFontWeightPolicy.shouldReplaceFontResource(
                    packageName = "com.apple.android.music",
                    resourceType = "font",
                    resourceName = resourceName,
                )
            )
        }

        assertFalse(
            AppleSystemFontWeightPolicy.shouldReplaceFontResource(
                packageName = "com.apple.android.music",
                resourceType = "font",
                resourceName = "regular_arabic",
            )
        )
        assertFalse(
            AppleSystemFontWeightPolicy.shouldReplaceFontResource(
                packageName = "com.apple.android.music",
                resourceType = "font",
                resourceName = "roboto_medium_numbers",
            )
        )
        assertFalse(
            AppleSystemFontWeightPolicy.shouldReplaceFontResource(
                packageName = "another.package",
                resourceType = "font",
                resourceName = "regular",
            )
        )
    }

    @Test
    fun `preserves valid Apple semantic weights`() {
        assertEquals(400, AppleSystemFontWeightPolicy.semanticWeight(400, isBold = false))
        assertEquals(510, AppleSystemFontWeightPolicy.semanticWeight(510, isBold = false))
        assertEquals(700, AppleSystemFontWeightPolicy.semanticWeight(700, isBold = true))
        assertEquals(900, AppleSystemFontWeightPolicy.semanticWeight(900, isBold = true))
    }

    @Test
    fun `falls back to regular or bold for invalid reported weights`() {
        assertEquals(400, AppleSystemFontWeightPolicy.semanticWeight(0, isBold = false))
        assertEquals(700, AppleSystemFontWeightPolicy.semanticWeight(1001, isBold = true))
    }

    @Test
    fun `composite reports Apple semantic weight instead of mapped font axis`() {
        assertEquals(400, AppleSystemFontWeightPolicy.compositeStyleWeight(400))
        assertEquals(510, AppleSystemFontWeightPolicy.compositeStyleWeight(510))
        assertEquals(700, AppleSystemFontWeightPolicy.compositeStyleWeight(700))
        assertEquals(1, AppleSystemFontWeightPolicy.compositeStyleWeight(-50))
        assertEquals(1000, AppleSystemFontWeightPolicy.compositeStyleWeight(1200))
    }

    @Test
    fun `maps HyperOS scale onto the SF Pro weight axis without flattening hierarchy`() {
        assertEquals(452, AppleSystemFontWeightPolicy.sfProWeightForSystemScale(400, 76))
        assertEquals(562, AppleSystemFontWeightPolicy.sfProWeightForSystemScale(510, 76))
        assertEquals(652, AppleSystemFontWeightPolicy.sfProWeightForSystemScale(600, 76))
        assertEquals(752, AppleSystemFontWeightPolicy.sfProWeightForSystemScale(700, 76))
        assertEquals(952, AppleSystemFontWeightPolicy.sfProWeightForSystemScale(900, 76))
    }

    @Test
    fun `uses scale 50 as neutral and clamps to the real SF Pro axis`() {
        assertEquals(400, AppleSystemFontWeightPolicy.sfProWeightForSystemScale(400, null))
        assertEquals(510, AppleSystemFontWeightPolicy.sfProWeightForSystemScale(510, 50))
        assertEquals(300, AppleSystemFontWeightPolicy.sfProWeightForSystemScale(400, 0))
        assertEquals(500, AppleSystemFontWeightPolicy.sfProWeightForSystemScale(400, 100))
        assertEquals(1, AppleSystemFontWeightPolicy.sfProWeightForSystemScale(1, -50))
        assertEquals(1000, AppleSystemFontWeightPolicy.sfProWeightForSystemScale(950, 150))
    }

    @Test
    fun `ordinary Apple Music text is eligible across the whole app`() {
        assertTrue(AppleSystemFontWeightPolicy.shouldReplaceTextContent("Apple Music"))
        assertTrue(AppleSystemFontWeightPolicy.shouldReplaceTextContent("中文歌词"))
        assertTrue(AppleSystemFontWeightPolicy.shouldReplaceTextContent("-3:42"))
    }

    @Test
    fun `empty and icon only text is not eligible`() {
        assertFalse(AppleSystemFontWeightPolicy.shouldReplaceTextContent(null))
        assertFalse(AppleSystemFontWeightPolicy.shouldReplaceTextContent(""))
        assertFalse(AppleSystemFontWeightPolicy.shouldReplaceTextContent("\uE000"))
        assertFalse(AppleSystemFontWeightPolicy.shouldReplaceTextContent("•••"))
    }

    @Test
    fun `uses HyperOS CJK fallback only when the text needs CJK glyphs`() {
        assertTrue(AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback("中文歌词"))
        assertTrue(AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback("注音ㄅㄆㄇ"))
        assertFalse(AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback("日本語かな"))
        assertFalse(AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback("かなカナ"))
        assertFalse(AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback("한글"))
        assertFalse(AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback("Apple Music"))
        assertFalse(AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback("-3:42 •••"))
        assertFalse(AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback("\uE000"))
    }
}
