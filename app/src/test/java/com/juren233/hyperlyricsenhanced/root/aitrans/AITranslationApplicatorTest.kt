package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class AITranslationApplicatorTest {

    @Test
    fun `preserves online translations while filling unmatched lines`() {
        val song = Song(
            name = "Song",
            lyrics = listOf(
                RichLyricLine(text = "First", translation = "在线译文"),
                RichLyricLine(text = "Second")
            )
        )

        val translated = AITranslationApplicator.apply(
            song = song,
            transItems = listOf(
                TranslationItem(index = 0, trans = "AI 覆盖译文"),
                TranslationItem(index = 1, trans = "AI 补充译文")
            ),
            forceOverride = false
        )

        assertEquals(
            listOf("在线译文", "AI 补充译文"),
            translated.lyrics?.map { it.translation }
        )
    }
}
