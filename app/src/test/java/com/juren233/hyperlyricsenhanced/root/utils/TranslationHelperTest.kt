package com.juren233.hyperlyricsenhanced.root.utils

import com.juren233.hyperlyricsenhanced.common.RootConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationHelperTest {

    @Test
    fun `new translation display preference takes priority over legacy values`() {
        assertTrue(
            TranslationHelper.resolveTranslationDisplay(
                hasTranslationDisplayPreference = true,
                translationDisplay = true,
                legacyTranslationDisabled = true,
                nextLyricLine = true,
                hasLegacyAutoSwitchPreference = true,
                legacyAutoSwitchTranslation = false
            )
        )
    }

    @Test
    fun `legacy disabled translation migrates to hidden translations`() {
        assertFalse(
            TranslationHelper.resolveTranslationDisplay(
                hasTranslationDisplayPreference = false,
                translationDisplay = true,
                legacyTranslationDisabled = true,
                nextLyricLine = false,
                hasLegacyAutoSwitchPreference = false,
                legacyAutoSwitchTranslation = false
            )
        )
    }

    @Test
    fun `legacy auto switch migrates only when next lyric was enabled`() {
        assertFalse(
            TranslationHelper.resolveTranslationDisplay(
                hasTranslationDisplayPreference = false,
                translationDisplay = true,
                legacyTranslationDisabled = false,
                nextLyricLine = true,
                hasLegacyAutoSwitchPreference = true,
                legacyAutoSwitchTranslation = false
            )
        )
        assertFalse(
            TranslationHelper.resolveTranslationDisplay(
                hasTranslationDisplayPreference = false,
                translationDisplay = false,
                legacyTranslationDisabled = false,
                nextLyricLine = false,
                hasLegacyAutoSwitchPreference = true,
                legacyAutoSwitchTranslation = false
            )
        )
    }

    @Test
    fun `resolveTargetSecondary respects translation and pronunciation modes without fallback`() {
        val line = com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine(
            text = "Original",
            translation = "翻译文本",
            roma = "roma text"
        )
        val lineNoTrans = com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine(
            text = "Original",
            translation = null,
            roma = "roma text"
        )
        val lineNoRoma = com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine(
            text = "Original",
            translation = "翻译文本",
            roma = null
        )

        // Translation mode, no fallback
        org.junit.Assert.assertEquals(
            "翻译文本",
            TranslationHelper.resolveTargetSecondary(line, RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION, false)?.text
        )
        org.junit.Assert.assertNull(
            TranslationHelper.resolveTargetSecondary(lineNoTrans, RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION, false)
        )

        // Translation mode, with fallback
        org.junit.Assert.assertEquals(
            "roma text",
            TranslationHelper.resolveTargetSecondary(lineNoTrans, RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION, true)?.text
        )

        // Pronunciation mode, no fallback
        org.junit.Assert.assertEquals(
            "roma text",
            TranslationHelper.resolveTargetSecondary(line, RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION, false)?.text
        )
        org.junit.Assert.assertNull(
            TranslationHelper.resolveTargetSecondary(lineNoRoma, RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION, false)
        )

        // Pronunciation mode, with fallback
        org.junit.Assert.assertEquals(
            "翻译文本",
            TranslationHelper.resolveTargetSecondary(lineNoRoma, RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION, true)?.text
        )

        // Off mode
        org.junit.Assert.assertNull(
            TranslationHelper.resolveTargetSecondary(line, RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF, true)
        )
    }
}
