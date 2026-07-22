package com.juren233.hyperlyricsenhanced.root.utils

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine

object TranslationHelper {

    fun isTranslationDisabled(prefs: SharedPreferences): Boolean {
        return prefs.getBoolean(RootConstants.KEY_HOOK_DISABLE_TRANSLATION, RootConstants.DEFAULT_HOOK_DISABLE_TRANSLATION)
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

