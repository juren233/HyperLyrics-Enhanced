package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplePronunciationPolicyTest {
    @Test
    fun `refreshes official pronunciation when it arrives after the first presentation`() {
        assertEquals(
            true,
            ApplePronunciationPolicy.shouldRefreshPresentationAfterBuild(
                sourceIsApple = true,
                hasValidOfficialPronunciation = true,
                hasOnlineTranslation = false,
                hasOnlinePronunciation = false,
                pronunciationSelected = true,
            ),
        )
    }

    @Test
    fun `keeps fallback refresh and gives official data precedence when both exist`() {
        assertEquals(
            true,
            ApplePronunciationPolicy.shouldRefreshPresentationAfterBuild(
                sourceIsApple = true,
                hasValidOfficialPronunciation = true,
                hasOnlineTranslation = true,
                hasOnlinePronunciation = true,
                pronunciationSelected = false,
            ),
        )
        assertEquals(
            true,
            ApplePronunciationPolicy.shouldRefreshPresentationAfterBuild(
                sourceIsApple = true,
                hasValidOfficialPronunciation = true,
                hasOnlineTranslation = false,
                hasOnlinePronunciation = true,
                pronunciationSelected = true,
            ),
        )
    }

    @Test
    fun `does not refresh a non Apple model or an unselected unavailable model`() {
        assertEquals(
            false,
            ApplePronunciationPolicy.shouldRefreshPresentationAfterBuild(
                sourceIsApple = false,
                hasValidOfficialPronunciation = true,
                hasOnlineTranslation = false,
                hasOnlinePronunciation = false,
                pronunciationSelected = true,
            ),
        )
        assertEquals(
            false,
            ApplePronunciationPolicy.shouldRefreshPresentationAfterBuild(
                sourceIsApple = true,
                hasValidOfficialPronunciation = false,
                hasOnlineTranslation = false,
                hasOnlinePronunciation = false,
                pronunciationSelected = true,
            ),
        )
        assertEquals(
            false,
            ApplePronunciationPolicy.shouldRefreshPresentationAfterBuild(
                sourceIsApple = true,
                hasValidOfficialPronunciation = true,
                hasOnlineTranslation = false,
                hasOnlinePronunciation = false,
                pronunciationSelected = false,
            ),
        )
    }

    @Test
    fun `keeps Apple pronunciation display getter non null`() {
        assertEquals("", ApplePronunciationPolicy.nonNullDisplayText(null))
        assertEquals(
            "Kimi no na wa",
            ApplePronunciationPolicy.nonNullDisplayText("Kimi no na wa"),
        )
    }

    @Test
    fun `uses main line timing only for a real supplemented pronunciation`() {
        assertEquals(
            ApplePronunciationWordTrack.MAIN_LINE_TIMING,
            ApplePronunciationPolicy.wordTrack(
                hasValidOfficialPronunciation = false,
                hasOnlinePronunciation = true,
            )
        )
        assertEquals(
            ApplePronunciationWordTrack.OFFICIAL,
            ApplePronunciationPolicy.wordTrack(
                hasValidOfficialPronunciation = true,
                hasOnlinePronunciation = true,
            )
        )
        assertEquals(
            ApplePronunciationWordTrack.HIDDEN,
            ApplePronunciationPolicy.wordTrack(
                hasValidOfficialPronunciation = false,
                hasOnlinePronunciation = false,
            )
        )
    }

    @Test
    fun `accepts official word timing only when every main begin has an exact match`() {
        assertTrue(
            ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
                mainWordBegins = listOf(12_937, 13_598, 14_114),
                pronunciationWordBegins = listOf(12_937, 13_598, 14_114),
            )
        )
        assertTrue(
            ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
                mainWordBegins = listOf(12_937, 13_598),
                pronunciationWordBegins = listOf(12_937, 13_598, 14_114),
            )
        )
    }

    @Test
    fun `rejects official word timing shifted ahead of the main lyrics`() {
        assertFalse(
            ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
                mainWordBegins = listOf(12_937, 13_598, 14_114, 14_616),
                pronunciationWordBegins = listOf(12_527, 13_198, 13_714, 14_216),
            )
        )
        assertFalse(
            ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
                mainWordBegins = emptyList(),
                pronunciationWordBegins = listOf(12_527),
            )
        )
    }

    @Test
    fun `maps supplemented romanization across all native main words`() {
        assertEquals(
            listOf("Kimi", "no", "na", "wa"),
            ApplePronunciationPolicy.displaySegments(
                pronunciation = "Kimi no na wa",
                mainWordTexts = listOf("君", "の", "名", "は"),
            ),
        )
    }

    @Test
    fun `groups pronunciation by the Han characters inside native words`() {
        assertEquals(
            listOf("siu sa", "di", "fong", "pei"),
            ApplePronunciationPolicy.displaySegments(
                pronunciation = "siu sa di fong pei",
                mainWordTexts = listOf("潇洒", "的", "放", "屁"),
            ),
        )
    }

    @Test
    fun `maps the misaligned official Cantonese line onto native main words`() {
        assertEquals(
            listOf("sai", "jyu daai", "fung", "sap tau wong fan", "dik", "gaai", "dou"),
            ApplePronunciationPolicy.displaySegments(
                pronunciation = "sai jyu daai fung sap tau wong fan dik gaai dou",
                mainWordTexts = listOf("细", "雨带", "风", "湿透黄昏", "的", "街", "道"),
            ),
        )
    }

    @Test
    fun `uses proportional safe fallback when token and character counts differ`() {
        assertEquals(
            listOf("jyut6 jyu5", "go1 ci4"),
            ApplePronunciationPolicy.displaySegments(
                pronunciation = "jyut6 jyu5 go1 ci4",
                mainWordTexts = listOf("粤语", "歌词"),
            ),
        )
        assertEquals(
            emptyList<String>(),
            ApplePronunciationPolicy.displaySegments("Konnichiwa", mainWordTexts = emptyList()),
        )
    }

    @Test
    fun `keeps system matched Latin Apple pronunciation first`() {
        assertEquals(
            "ja-Latn",
            ApplePronunciationPolicy.selectLanguage(
                systemMatch = "ja-Latn",
                appleLanguages = listOf("ko-Latn"),
                onlineFallbackLanguage = "und-Latn",
            )
        )
    }

    @Test
    fun `falls back to first Apple pronunciation before online source`() {
        assertEquals(
            "ja-Latn",
            ApplePronunciationPolicy.selectLanguage(
                systemMatch = null,
                appleLanguages = listOf(" ", "ja-Hrkt", "ja-Latn"),
                onlineFallbackLanguage = "und-Latn",
            )
        )
    }

    @Test
    fun `uses online fallback language only without an Apple track`() {
        assertEquals(
            "und-Latn",
            ApplePronunciationPolicy.selectLanguage(
                systemMatch = null,
                appleLanguages = emptyList(),
                onlineFallbackLanguage = "und-Latn",
            )
        )
        assertNull(
            ApplePronunciationPolicy.selectLanguage(
                systemMatch = null,
                appleLanguages = emptyList(),
                onlineFallbackLanguage = null,
            )
        )
    }

    @Test
    fun `rejects non Latin Apple and fallback languages`() {
        assertNull(
            ApplePronunciationPolicy.selectLanguage(
                systemMatch = "ja-Hrkt",
                appleLanguages = listOf("zh-Hans", "ko-Hang"),
                onlineFallbackLanguage = "zh-Hani",
            )
        )
    }
}
