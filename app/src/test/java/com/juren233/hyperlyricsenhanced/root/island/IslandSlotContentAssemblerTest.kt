package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandSlotContentAssemblerTest {

    @Test
    fun `auto switch shows next line for a line without translation or backing vocals`() {
        val line = RichLyricLine(text = "Current lyric")

        assertTrue(IslandSlotContentAssembler.shouldUseNextLinePreview(true, line))
    }

    @Test
    fun `auto switch keeps translation or backing vocals in the second line`() {
        assertFalse(
            IslandSlotContentAssembler.shouldUseNextLinePreview(
                true,
                RichLyricLine(text = "Current lyric", translation = "当前歌词")
            )
        )
        assertFalse(
            IslandSlotContentAssembler.shouldUseNextLinePreview(
                true,
                RichLyricLine(
                    text = "Current lyric",
                    secondaryWords = listOf(LyricWord(begin = 0, end = 1_000, text = "Backing"))
                )
            )
        )
    }

    @Test
    fun `next line mode without auto switch always occupies the second line`() {
        assertTrue(
            IslandSlotContentAssembler.shouldUseNextLinePreview(
                false,
                RichLyricLine(text = "Current lyric", translation = "当前歌词", secondary = "Backing")
            )
        )
    }
}
