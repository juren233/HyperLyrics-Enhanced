package com.juren233.hyperlyricsenhanced.common.lyric

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class TraditionalLyricsSimplifierTest {
    @Test
    fun `converts every displayed lyric field without mutating the source`() {
        val source = Song(
            id = "song-id",
            name = "歌曲名稱",
            lyrics = listOf(
                RichLyricLine(
                    begin = 1_000L,
                    end = 2_000L,
                    text = "繁體歌詞",
                    words = listOf(LyricWord(begin = 1_000L, text = "繁體")),
                    secondary = "伴唱歌詞",
                    secondaryWords = listOf(LyricWord(begin = 1_200L, text = "伴唱")),
                    translation = "翻譯內容",
                    translationWords = listOf(LyricWord(begin = 1_000L, text = "翻譯")),
                    roma = "fan ti ge ci",
                    metadata = lyricMetadataOf(
                        LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION to "伴唱翻譯",
                        LyricMetadataKeys.GROUP_VOCALS to "true"
                    )
                )
            )
        )
        val replacements = mapOf(
            "繁體歌詞" to "繁体歌词",
            "繁體" to "繁体",
            "伴唱歌詞" to "伴唱歌词",
            "伴唱" to "伴唱",
            "翻譯內容" to "翻译内容",
            "翻譯" to "翻译",
            "伴唱翻譯" to "伴唱翻译"
        )

        val simplified = TraditionalLyricsSimplifier.simplify(source) {
            replacements[it] ?: it
        }
        val line = simplified.lyrics.orEmpty().single()

        assertNotSame(source, simplified)
        assertNotSame(source.lyrics.orEmpty().single(), line)
        assertEquals("歌曲名稱", simplified.name)
        assertEquals("繁体歌词", line.text)
        assertEquals("繁体", line.words.orEmpty().single().text)
        assertEquals("伴唱歌词", line.secondary)
        assertEquals("伴唱", line.secondaryWords.orEmpty().single().text)
        assertEquals("翻译内容", line.translation)
        assertEquals("翻译", line.translationWords.orEmpty().single().text)
        assertEquals("伴唱翻译", line.metadata?.getString(
            LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION
        ))
        assertEquals(true, line.metadata?.getBoolean(LyricMetadataKeys.GROUP_VOCALS))
        assertEquals("fan ti ge ci", line.roma)
        assertEquals(1_000L, line.begin)
        assertEquals("繁體歌詞", source.lyrics.orEmpty().single().text)
        assertEquals(
            "伴唱翻譯",
            source.lyrics.orEmpty().single().metadata?.getString(
                LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION
            )
        )
    }
}
