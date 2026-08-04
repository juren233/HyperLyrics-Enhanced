package com.juren233.hyperlyricsenhanced.common.lyric

import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import java.util.Locale

object ApplePronunciationVisibilityPolicy {
    private val cantoneseGenreMarkers = listOf(
        "cantopop",
        "cantonese",
        "粤语",
        "粵語",
        "粤曲",
        "粵曲",
        "广东歌",
        "廣東歌",
        "港乐",
        "港樂",
    )
    private val mandarinGenreMarkers = listOf(
        "mandopop",
        "mandarin",
        "国语",
        "國語",
        "华语",
        "華語",
    )

    fun shouldHide(genre: String?, hideMandarinPinyin: Boolean): Boolean =
        shouldHide(
            genre = genre,
            pronunciationLanguages = emptyList(),
            hideMandarinPinyin = hideMandarinPinyin,
        )

    fun shouldHide(
        genre: String?,
        pronunciationLanguages: Collection<String>,
        hideMandarinPinyin: Boolean,
    ): Boolean {
        if (!hideMandarinPinyin) return false
        if (pronunciationLanguages.any(::isCantonesePronunciationLanguage)) return false
        if (pronunciationLanguages.any(::isMandarinPronunciationLanguage)) return true
        return isMandarinGenre(genre)
    }

    fun shouldHide(song: Song, hideMandarinPinyin: Boolean): Boolean =
        shouldHide(
            genre = song.metadata?.getString(LyricMetadataKeys.APPLE_CATALOG_GENRE),
            pronunciationLanguages = song.metadata
                ?.getString(LyricMetadataKeys.APPLE_PRONUNCIATION_LANGUAGES)
                ?.split(',')
                .orEmpty(),
            hideMandarinPinyin = hideMandarinPinyin,
        )

    fun allowsOnlineSupplementation(song: Song, hideMandarinPinyin: Boolean): Boolean =
        !shouldHide(song, hideMandarinPinyin)

    fun filterOnlineLines(
        song: Song,
        onlineLines: List<LrcLine>,
        hideMandarinPinyin: Boolean,
    ): List<LrcLine> {
        if (!shouldHide(song, hideMandarinPinyin)) return onlineLines
        return onlineLines.map { line -> line.copy(romanization = null) }
    }

    fun filterSong(song: Song, hideMandarinPinyin: Boolean): Song {
        if (!shouldHide(song, hideMandarinPinyin)) return song
        val lines = song.lyrics ?: return song
        return song.copy(lyrics = lines.map { line -> line.copy(roma = null) })
    }

    fun isMandarinGenre(genre: String?): Boolean {
        val normalized = genre.orEmpty().trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return false
        if (cantoneseGenreMarkers.any(normalized::contains)) return false
        return mandarinGenreMarkers.any(normalized::contains)
    }

    fun isMandarinPronunciationLanguage(language: String?): Boolean {
        val normalized = language.orEmpty().trim().lowercase(Locale.ROOT)
        return normalized.contains("pinyin") ||
            normalized == "cmn-latn" ||
            normalized.startsWith("cmn-latn-")
    }

    fun isCantonesePronunciationLanguage(language: String?): Boolean {
        val normalized = language.orEmpty().trim().lowercase(Locale.ROOT)
        return normalized.contains("jyutping") ||
            normalized == "yue-latn" ||
            normalized.startsWith("yue-latn-")
    }
}
