package com.juren233.hyperlyricsenhanced.common.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomanizationPolicyTest {
    @Test
    fun `keeps Latin romanization for non Latin lyrics`() {
        assertEquals(
            "Kimi no na wa",
            RomanizationPolicy.sanitize("君の名は", "Kimi no na wa"),
        )
        assertEquals(
            "Nǐ hǎo, shìjiè",
            RomanizationPolicy.sanitize("你好，世界", "Nǐ hǎo, shìjiè"),
        )
        assertEquals(
            "annyeong world",
            RomanizationPolicy.sanitize("안녕 world", "annyeong world"),
        )
        assertEquals(
            "nei hou Hello",
            RomanizationPolicy.sanitize("你好 Hello", "nei hou Hello"),
        )
    }

    @Test
    fun `rejects Latin pronunciation attached to Latin numeric or punctuation only lyrics`() {
        assertNull(
            RomanizationPolicy.sanitize(
                "Where did you go?",
                "yi zei yei yv guong zong",
            )
        )
        assertNull(RomanizationPolicy.sanitize("2026", "er ling er liu"))
        assertNull(RomanizationPolicy.sanitize("...?!", "la la la"))
    }

    @Test
    fun `rejects non Latin pronunciation text`() {
        assertNull(RomanizationPolicy.sanitize("Getting washed", "ゲッティング ウォッシュト"))
        assertNull(RomanizationPolicy.sanitize("君の名は", "きみのなは"))
        assertNull(RomanizationPolicy.sanitize("Home", "回家"))
    }

    @Test
    fun `rejects pronunciation copied from original lyrics`() {
        assertNull(RomanizationPolicy.sanitize("Getting washed", "Getting   washed!"))
        assertNull(RomanizationPolicy.sanitize("<b>Home</b>", "HOME"))
        assertNull(RomanizationPolicy.sanitize("君の名は", "君の名は"))
    }

    @Test
    fun `accepts only explicit Latin script language tags`() {
        assertTrue(RomanizationPolicy.isLatinLanguageTag("ja-Latn"))
        assertTrue(RomanizationPolicy.isLatinLanguageTag("und-latn"))
        assertFalse(RomanizationPolicy.isLatinLanguageTag("ja-Hrkt"))
        assertFalse(RomanizationPolicy.isLatinLanguageTag("zh-Hans"))
        assertFalse(RomanizationPolicy.isLatinLanguageTag(null))
    }
}
