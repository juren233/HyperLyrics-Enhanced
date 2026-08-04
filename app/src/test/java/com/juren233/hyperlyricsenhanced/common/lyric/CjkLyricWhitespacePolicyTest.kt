package com.juren233.hyperlyricsenhanced.common.lyric

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class CjkLyricWhitespacePolicyTest {
    @Test
    fun `removes whitespace from Chinese Japanese and Korean lyric text`() {
        assertEquals("你好世界", CjkLyricWhitespacePolicy.transformText("你 好\t世界"))
        assertEquals("君と僕", CjkLyricWhitespacePolicy.transformText("君 と 僕"))
        assertEquals("안녕world", CjkLyricWhitespacePolicy.transformText("안 녕 world"))
    }

    @Test
    fun `preserves spaces in pure Latin lyric text`() {
        assertEquals(
            "We are the world",
            CjkLyricWhitespacePolicy.transformText("We are the world")
        )
    }

    @Test
    fun `supports supplementary Han characters`() {
        assertEquals("𠀀A", CjkLyricWhitespacePolicy.transformText("𠀀 A"))
    }

    @Test
    fun `copies every displayed lyric layer but preserves pronunciation`() {
        val source = RichLyricLine(
            begin = 1_000L,
            end = 2_000L,
            text = "你 好",
            words = listOf(
                LyricWord(begin = 1_000L, text = "你"),
                LyricWord(begin = 1_050L, text = " "),
                LyricWord(begin = 1_100L, text = "好"),
            ),
            secondary = "伴 唱",
            secondaryWords = listOf(LyricWord(begin = 1_100L, text = "伴 唱")),
            translation = "翻 译",
            translationWords = listOf(LyricWord(begin = 1_000L, text = "翻 译")),
            roma = "ni hao",
            metadata = lyricMetadataOf(
                LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION to "伴 唱 翻 译",
                LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING to "重 叠 伴 唱",
                LyricMetadataKeys.GROUP_VOCALS to "true",
            ),
        )

        val transformed = CjkLyricWhitespacePolicy.transformLine(source)

        assertNotSame(source, transformed)
        assertEquals("你好", transformed.text)
        assertEquals(listOf("你", "", "好"), transformed.words.orEmpty().map { it.text })
        assertEquals("伴唱", transformed.secondary)
        assertEquals("伴唱", transformed.secondaryWords.orEmpty().single().text)
        assertEquals("翻译", transformed.translation)
        assertEquals("翻译", transformed.translationWords.orEmpty().single().text)
        assertEquals("伴唱翻译", transformed.metadata?.getString(
            LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION
        ))
        assertEquals("重叠伴唱", transformed.metadata?.getString(
            LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING
        ))
        assertEquals(true, transformed.metadata?.getBoolean(LyricMetadataKeys.GROUP_VOCALS))
        assertEquals("ni hao", transformed.roma)
        assertEquals("你 好", source.text)
    }
}
