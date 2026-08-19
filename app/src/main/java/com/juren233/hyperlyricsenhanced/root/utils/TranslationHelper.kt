package com.juren233.hyperlyricsenhanced.root.utils

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine

object TranslationHelper {

    fun readTranslationPronunciationMode(
        prefs: SharedPreferences?,
        key: String,
        defaultValue: Int = RootConstants.DEFAULT_HOOK_TRANSLATION_PRONUNCIATION_DISPLAY
    ): Int {
        if (prefs == null) return defaultValue
        val raw = try {
            prefs.all[key]
        } catch (_: Exception) {
            null
        }
        return when (raw) {
            is Int -> raw.coerceIn(
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION
            )
            is Number -> raw.toInt().coerceIn(
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION
            )
            is Boolean -> if (raw) RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION
            else RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF
            is String -> raw.toIntOrNull()?.coerceIn(
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION
            ) ?: defaultValue
            else -> defaultValue
        }
    }

    fun getTranslationDisplayMode(prefs: SharedPreferences): Int {
        if (prefs.contains(RootConstants.KEY_HOOK_TRANSLATION_DISPLAY)) {
            return readTranslationPronunciationMode(
                prefs,
                RootConstants.KEY_HOOK_TRANSLATION_DISPLAY,
                RootConstants.DEFAULT_HOOK_TRANSLATION_PRONUNCIATION_DISPLAY
            )
        }
        val legacyDisabled = prefs.getBoolean(
            RootConstants.KEY_HOOK_DISABLE_TRANSLATION,
            RootConstants.DEFAULT_HOOK_DISABLE_TRANSLATION
        )
        if (legacyDisabled) return RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF

        val nextLyricLine = prefs.getBoolean(
            RootConstants.KEY_HOOK_NEXT_LYRIC_LINE,
            RootConstants.DEFAULT_HOOK_NEXT_LYRIC_LINE
        )
        val hasLegacyAutoSwitch = prefs.contains(RootConstants.KEY_HOOK_AUTO_SWITCH_TRANSLATION)
        if (nextLyricLine && hasLegacyAutoSwitch) {
            val legacyAutoSwitch = prefs.getBoolean(
                RootConstants.KEY_HOOK_AUTO_SWITCH_TRANSLATION,
                RootConstants.DEFAULT_HOOK_AUTO_SWITCH_TRANSLATION
            )
            return if (legacyAutoSwitch) RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION
            else RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF
        }

        return RootConstants.DEFAULT_HOOK_TRANSLATION_PRONUNCIATION_DISPLAY
    }

    fun isTranslationFallback(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(
            RootConstants.KEY_HOOK_TRANSLATION_FALLBACK,
            RootConstants.DEFAULT_HOOK_TRANSLATION_PRONUNCIATION_FALLBACK
        )

    fun isTranslationDisplayed(prefs: SharedPreferences): Boolean =
        getTranslationDisplayMode(prefs) != RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF

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

    data class TargetSecondary(
        val text: String,
        val words: List<com.juren233.hyperlyricsenhanced.lyric.model.LyricWord>?
    )

    fun resolveTargetSecondary(
        line: IRichLyricLine,
        displayMode: Int = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION,
        fallback: Boolean = false
    ): TargetSecondary? {
        val hasTranslation = !line.translation.isNullOrBlank() || !line.translationWords.isNullOrEmpty()
        val hasRoma = !line.roma.isNullOrBlank()
        return when (displayMode) {
            RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION -> when {
                hasTranslation -> TargetSecondary(line.translation.orEmpty(), line.translationWords)
                fallback && hasRoma -> TargetSecondary(line.roma.orEmpty(), null)
                else -> null
            }
            RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION -> when {
                hasRoma -> TargetSecondary(line.roma.orEmpty(), null)
                fallback && hasTranslation -> TargetSecondary(line.translation.orEmpty(), line.translationWords)
                else -> null
            }
            else -> null
        }
    }

    fun applyTranslationOnly(
        line: IRichLyricLine,
        displayMode: Int = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION,
        fallback: Boolean = false
    ): IRichLyricLine {
        val target = resolveTargetSecondary(line, displayMode, fallback) ?: return line

        return com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine(
            begin = line.begin,
            end = line.end,
            duration = line.duration,
            text = target.text,
            words = target.words ?: emptyList(),
            translation = null,
            translationWords = null,
            secondary = line.secondary,
            secondaryWords = line.secondaryWords,
            roma = line.roma,
            metadata = line.metadata
        )
    }

    fun swapTranslation(
        line: IRichLyricLine,
        displayMode: Int = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION,
        fallback: Boolean = false
    ): IRichLyricLine {
        val target = resolveTargetSecondary(line, displayMode, fallback) ?: return line

        return com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine(
            begin = line.begin,
            end = line.end,
            duration = line.duration,
            text = target.text,
            words = target.words ?: emptyList(),
            translation = line.text,
            translationWords = line.words,
            secondary = line.secondary,
            secondaryWords = line.secondaryWords,
            roma = line.roma,
            metadata = line.metadata
        )
    }
}
