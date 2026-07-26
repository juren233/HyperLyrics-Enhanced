package com.juren233.hyperlyricsenhanced.root.utils

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
        assertTrue(
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
}
