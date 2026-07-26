package com.juren233.hyperlyricsenhanced.root.utils

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine

object TranslationHelper {

    fun isTranslationDisplayed(prefs: SharedPreferences): Boolean {
        return resolveTranslationDisplay(
            hasTranslationDisplayPreference = prefs.contains(
                RootConstants.KEY_HOOK_TRANSLATION_DISPLAY
            ),
            translationDisplay = prefs.getBoolean(
                RootConstants.KEY_HOOK_TRANSLATION_DISPLAY,
                RootConstants.DEFAULT_HOOK_TRANSLATION_DISPLAY
            ),
            legacyTranslationDisabled = prefs.getBoolean(
                RootConstants.KEY_HOOK_DISABLE_TRANSLATION,
                RootConstants.DEFAULT_HOOK_DISABLE_TRANSLATION
            ),
            nextLyricLine = prefs.getBoolean(
                RootConstants.KEY_HOOK_NEXT_LYRIC_LINE,
                RootConstants.DEFAULT_HOOK_NEXT_LYRIC_LINE
            ),
            hasLegacyAutoSwitchPreference = prefs.contains(
                RootConstants.KEY_HOOK_AUTO_SWITCH_TRANSLATION
            ),
            legacyAutoSwitchTranslation = prefs.getBoolean(
                RootConstants.KEY_HOOK_AUTO_SWITCH_TRANSLATION,
                RootConstants.DEFAULT_HOOK_AUTO_SWITCH_TRANSLATION
            )
        )
    }

    internal fun resolveTranslationDisplay(
        hasTranslationDisplayPreference: Boolean,
        translationDisplay: Boolean,
        legacyTranslationDisabled: Boolean,
        nextLyricLine: Boolean,
        hasLegacyAutoSwitchPreference: Boolean,
        legacyAutoSwitchTranslation: Boolean
    ): Boolean = when {
        hasTranslationDisplayPreference -> translationDisplay
        legacyTranslationDisabled -> false
        nextLyricLine && hasLegacyAutoSwitchPreference -> legacyAutoSwitchTranslation
        else -> RootConstants.DEFAULT_HOOK_TRANSLATION_DISPLAY
    }

    fun isTranslationOnly(prefs: SharedPreferences): Boolean {
        return prefs.getBoolean(RootConstants.KEY_HOOK_TRANSLATION_ONLY, RootConstants.DEFAULT_HOOK_TRANSLATION_ONLY)
    }

    fun isSwapTranslation(prefs: SharedPreferences): Boolean {
        return prefs.getBoolean(RootConstants.KEY_HOOK_SWAP_TRANSLATION, RootConstants.DEFAULT_HOOK_SWAP_TRANSLATION)
    }

    fun applyTranslationOnly(line: IRichLyricLine): IRichLyricLine {
        val translation = line.translation
        if (translation.isNullOrBlank()) return line

        return com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine(
            begin = line.begin,
            end = line.end,
            duration = line.duration,
            text = translation,
            words = line.translationWords ?: emptyList(),
            translation = null,
            translationWords = null,
            secondary = line.secondary,
            secondaryWords = line.secondaryWords,
            roma = line.roma,
            metadata = line.metadata
        )
    }

    fun swapTranslation(line: IRichLyricLine): IRichLyricLine {
        val translation = line.translation
        if (translation.isNullOrBlank()) return line

        return com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine(
            begin = line.begin,
            end = line.end,
            duration = line.duration,
            text = translation,
            words = line.translationWords ?: emptyList(),
            translation = line.text,
            translationWords = line.words,
            secondary = line.secondary,
            secondaryWords = line.secondaryWords,
            roma = line.roma,
            metadata = line.metadata
        )
    }
}
