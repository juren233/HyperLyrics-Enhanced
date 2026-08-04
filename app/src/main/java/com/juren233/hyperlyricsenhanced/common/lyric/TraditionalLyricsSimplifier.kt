package com.juren233.hyperlyricsenhanced.common.lyric

import com.juren233.hyperlyricsenhanced.lyric.model.LyricMetadata
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song

object TraditionalLyricsSimplifier {
    fun simplify(song: Song, convert: (String) -> String): Song =
        song.copy(
            lyrics = song.lyrics?.map { line -> simplify(line, convert) }
        )

    private fun simplify(
        line: RichLyricLine,
        convert: (String) -> String
    ): RichLyricLine = line.copy(
        text = line.text.convertWith(convert),
        words = line.words.simplifyWords(convert),
        secondary = line.secondary.convertWith(convert),
        secondaryWords = line.secondaryWords.simplifyWords(convert),
        translation = line.translation.convertWith(convert),
        translationWords = line.translationWords.simplifyWords(convert),
        metadata = line.metadata.simplifyBackgroundTranslation(convert)
    )

    private fun List<LyricWord>?.simplifyWords(
        convert: (String) -> String
    ): List<LyricWord>? = this?.map { word ->
        word.copy(text = word.text.convertWith(convert))
    }

    private fun LyricMetadata?.simplifyBackgroundTranslation(
        convert: (String) -> String
    ): LyricMetadata? {
        this ?: return null
        val translation = getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
            ?: return this
        return LyricMetadata(
            this + (
                LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION to
                    convert(translation)
                )
        )
    }

    private fun String?.convertWith(convert: (String) -> String): String? =
        this?.let(convert)
}
