package com.juren233.hyperlyricsenhanced.common.lyric

import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplePronunciationVisibilityPolicyTest {
    @Test
    fun `recognizes only explicit Mandarin genres`() {
        listOf("Mandopop", "Mandarin Pop", "国语流行", "國語流行", "华语流行").forEach {
            assertTrue(it, ApplePronunciationVisibilityPolicy.isMandarinGenre(it))
        }
        listOf("Cantopop", "Cantonese Pop", "粤语流行", "粵語流行", "C-Pop", null).forEach {
            assertFalse(it, ApplePronunciationVisibilityPolicy.isMandarinGenre(it))
        }
    }

    @Test
    fun `uses Apple pronunciation language before genre metadata`() {
        assertTrue(
            ApplePronunciationVisibilityPolicy.shouldHide(
                genre = null,
                pronunciationLanguages = listOf("zh-Latn-pinyin"),
                hideMandarinPinyin = true,
            )
        )
        assertFalse(
            ApplePronunciationVisibilityPolicy.shouldHide(
                genre = "国语流行",
                pronunciationLanguages = listOf("zh-Latn-jyutping"),
                hideMandarinPinyin = true,
            )
        )
    }

    @Test
    fun `removes pronunciation using serialized Apple pinyin language`() {
        val filtered = ApplePronunciationVisibilityPolicy.filterSong(
            song = song(
                genre = null,
                pronunciationLanguages = listOf("zh-Latn-pinyin"),
                lyrics = listOf(RichLyricLine(text = "国语歌词", roma = "guo yu ge ci")),
            ),
            hideMandarinPinyin = true,
        )

        assertNull(filtered.lyrics?.single()?.roma)
    }

    @Test
    fun `removes only online pinyin from Mandarin candidates`() {
        val lines = listOf(
            LrcLine(
                startTimeMs = 1_000L,
                content = "国语歌词",
                translation = "Mandarin lyrics",
                romanization = "guo yu ge ci",
            )
        )

        val filtered = ApplePronunciationVisibilityPolicy.filterOnlineLines(
            song = song("国语流行"),
            onlineLines = lines,
            hideMandarinPinyin = true,
        ).single()

        assertEquals("Mandarin lyrics", filtered.translation)
        assertNull(filtered.romanization)
    }

    @Test
    fun `removes Apple and online pronunciation from a Mandarin song`() {
        val source = song(
            genre = "Mandopop",
            lyrics = listOf(
                RichLyricLine(text = "第一行", roma = "Apple official"),
                RichLyricLine(text = "第二行", roma = "di er hang"),
            ),
        )

        val filtered = ApplePronunciationVisibilityPolicy.filterSong(
            song = source,
            hideMandarinPinyin = true,
        )

        assertNull(filtered.lyrics?.get(0)?.roma)
        assertNull(filtered.lyrics?.get(1)?.roma)
    }

    @Test
    fun `keeps Cantonese jyutping from Apple and online sources`() {
        val source = song(
            genre = "Cantopop",
            lyrics = listOf(RichLyricLine(text = "粵語歌詞", roma = "jyut6 jyu5 go1 ci4")),
        )
        val onlineLines = listOf(
            LrcLine(
                startTimeMs = 1_000L,
                content = "粵語歌詞",
                romanization = "jyut6 jyu5 go1 ci4",
            )
        )

        val filteredSong = ApplePronunciationVisibilityPolicy.filterSong(
            song = source,
            hideMandarinPinyin = true,
        )
        val filteredOnline = ApplePronunciationVisibilityPolicy.filterOnlineLines(
            song = source,
            onlineLines = onlineLines,
            hideMandarinPinyin = true,
        )

        assertEquals("jyut6 jyu5 go1 ci4", filteredSong.lyrics?.single()?.roma)
        assertEquals("jyut6 jyu5 go1 ci4", filteredOnline.single().romanization)
    }

    private fun song(
        genre: String?,
        pronunciationLanguages: List<String> = emptyList(),
        lyrics: List<RichLyricLine> = listOf(RichLyricLine(text = "歌词")),
    ): Song = Song(
        metadata = buildList {
            genre?.let { add(LyricMetadataKeys.APPLE_CATALOG_GENRE to it) }
            pronunciationLanguages.takeIf(List<String>::isNotEmpty)?.let {
                add(LyricMetadataKeys.APPLE_PRONUNCIATION_LANGUAGES to it.joinToString(","))
            }
        }.takeIf(List<Pair<String, String>>::isNotEmpty)
            ?.let { lyricMetadataOf(*it.toTypedArray()) },
        lyrics = lyrics,
    )
}
