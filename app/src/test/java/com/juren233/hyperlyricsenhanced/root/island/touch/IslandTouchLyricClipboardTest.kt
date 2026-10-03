/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.lyric.view.SongPreprocessor
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class IslandTouchLyricClipboardTest {
    private fun text(line: RichLyricLine?) = IslandTouchLyricClipboard.textFor("player", "player", line)

    @After fun clearBridge() {
        LyriconDataBridge.configureEarlyNextLinePreview(0, 100)
        LyriconDataBridge.clearState()
    }

    private fun prepareTimeline() {
        LyriconDataBridge.updateLyricPackage("player")
        LyriconDataBridge.configureEarlyNextLinePreview(2, 100)
        LyriconDataBridge.updateSong(Song(lyrics = listOf(
            RichLyricLine(begin = 1000, end = 2000, text = "Singing now", translation = "正在唱的句子"),
            RichLyricLine(begin = 4000, end = 5000, text = "Next line", translation = "下一句"),
        )))
    }

    @Test fun `copy follows the singing line even when the display has advanced to the next preview`() {
        prepareTimeline()
        LyriconDataBridge.updatePosition(1900)
        assertEquals("Next line", LyriconDataBridge.currentLyricLine?.text)
        assertEquals("Singing now\n正在唱的句子", IslandTouchLyricClipboard.currentText("player"))
        LyriconDataBridge.updatePosition(4000)
        assertEquals("Next line\n下一句", IslandTouchLyricClipboard.currentText("player"))
        LyriconDataBridge.updatePosition(1500)
        assertEquals("Singing now\n正在唱的句子", IslandTouchLyricClipboard.currentText("player"))
    }

    @Test fun `gaps intro and song end never copy a future preview or an expired line`() {
        prepareTimeline()
        for (position in listOf(500L, 3000L, 6000L)) {
            LyriconDataBridge.updatePosition(position)
            assertNull(IslandTouchLyricClipboard.currentText("player"))
        }
    }

    @Test fun `text providers use their latest line even if an old timeline is present`() {
        prepareTimeline()
        LyriconDataBridge.updatePosition(1500)
        LyriconDataBridge.updateLyric("Live line\n实时翻译")
        assertEquals("Live line\n实时翻译", IslandTouchLyricClipboard.currentText("player"))
    }

    @Test fun `timeline translation updates are read even before the display rebinds`() {
        prepareTimeline()
        LyriconDataBridge.updatePosition(1500)
        LyriconDataBridge.applyTranslation(Song(lyrics = listOf(
            RichLyricLine(begin = 1000, end = 2000, text = "Singing now", translation = "更新的翻译"),
        )))
        assertEquals("Singing now\n更新的翻译", IslandTouchLyricClipboard.currentText("player"))
    }

    @Test fun `full current original and translation are copied without backing vocals or pronunciation`() {
        assertEquals("Current complete line\n当前完整句", text(RichLyricLine(
            text = "Current complete line", translation = "当前完整句",
            secondary = "Backing vocals", roma = "Pronunciation",
        )))
    }

    @Test fun `missing or blank translation adds no empty line`() {
        for (translation in listOf(null, "", " \n ")) {
            assertEquals("Current line", text(RichLyricLine(text = " Current line ", translation = translation)))
        }
    }

    @Test fun `translation arriving after the original is included on the next copy`() {
        val line = RichLyricLine(text = "Current line")
        assertEquals("Current line", text(line))
        line.translation = "当前句"
        assertEquals("Current line\n当前句", text(line))
    }

    @Test fun `switching lines never retains the previous translation`() {
        assertEquals("First line\n第一句", text(RichLyricLine(text = "First line", translation = "第一句")))
        assertEquals("Second line", text(RichLyricLine(text = "Second line")))
    }

    @Test fun `another player or unknown lyric owner must not be copied`() {
        val line = RichLyricLine(text = "Old player line", translation = "旧播放器歌词")
        assertNull(IslandTouchLyricClipboard.textFor("new.player", "old.player", line))
        assertNull(IslandTouchLyricClipboard.textFor("player", null, line))
        assertNull(IslandTouchLyricClipboard.textFor("", "", line))
    }

    @Test fun `empty lyrics title and instrumental placeholders preserve the existing clipboard`() {
        assertNull(text(null))
        assertNull(text(RichLyricLine(text = " \n ", translation = "Translation only")))
        assertNull(text(RichLyricLine(text = "Track title",
            metadata = lyricMetadataOf(SongPreprocessor.KEY_TITLE_LINE to "true"))))
        assertNull(text(RichLyricLine(text = "•••",
            metadata = lyricMetadataOf(LyricMetadataKeys.INSTRUMENTAL to "true"))))
    }
}
