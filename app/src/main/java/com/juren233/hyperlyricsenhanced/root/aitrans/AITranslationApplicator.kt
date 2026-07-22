package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.lyric.model.Song

/** Applies validated translation items back to lyric lines. */
internal object AITranslationApplicator {
    fun apply(
        song: Song,
        transItems: List<TranslationItem>,
        forceOverride: Boolean = false
    ): Song {
        var appliedCount = 0
        val translationsByIndex = transItems.associateBy { it.index }
        val newLyrics = song.lyrics?.mapIndexed { index, line ->
            val transText = translationsByIndex[index]?.trans?.trim()

            if (!transText.isNullOrBlank()
                && (forceOverride || line.translation.isNullOrBlank())
                && transText.lowercase() != line.text?.trim()?.lowercase()
            ) {
                appliedCount++
                line.copy(translation = transText, translationWords = null)
            } else {
                line
            }
        }
        HookLogger.d(
            "AITranslationApplicator",
            "应用翻译结果: song=${song.name}, lines=$appliedCount"
        )
        return song.copy(lyrics = newLyrics)
    }
}

