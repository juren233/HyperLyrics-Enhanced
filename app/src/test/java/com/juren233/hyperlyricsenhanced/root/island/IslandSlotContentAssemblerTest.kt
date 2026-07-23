package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandSlotContentAssemblerTest {

    @Test
    fun `equal content in different line instances is not a transition`() {
        assertFalse(
            IslandSlotContentAssembler.hasLineContentChanged(
                RichLyricLine(text = "Same title", secondary = "Same artist"),
                RichLyricLine(text = "Same title", secondary = "Same artist")
            )
        )
    }

    @Test
    fun `changed secondary content is a transition`() {
        assertTrue(
            IslandSlotContentAssembler.hasLineContentChanged(
                RichLyricLine(text = "Same title", secondary = "Old artist"),
                RichLyricLine(text = "Same title", secondary = "New artist")
            )
        )
    }

    @Test
    fun `same content forced refresh suppresses transition animation`() {
        assertFalse(
            IslandSlotContentAssembler.shouldAnimateContentUpdate(
                animationEnabled = true,
                suppressAnimation = false,
                contentChanged = false,
                attached = true
            )
        )
    }

    @Test
    fun `changed attached content uses configured transition animation`() {
        assertTrue(
            IslandSlotContentAssembler.shouldAnimateContentUpdate(
                animationEnabled = true,
                suppressAnimation = false,
                contentChanged = true,
                attached = true
            )
        )
    }

    @Test
    fun `lyrics becoming available is an empty to populated transition`() {
        assertTrue(
            IslandSlotContentAssembler.isEmptyToPopulatedLyricTransition(
                currentLine = null,
                targetLine = RichLyricLine(text = "First lyric")
            )
        )
        assertTrue(
            IslandSlotContentAssembler.isEmptyToPopulatedLyricTransition(
                currentLine = RichLyricLine(),
                targetLine = RichLyricLine(text = "First lyric")
            )
        )
    }

    @Test
    fun `song title placeholder is not treated as available lyrics`() {
        assertFalse(
            IslandSlotContentAssembler.isActualLyricAvailable(
                sourceLine = null,
                targetLine = RichLyricLine(text = "Song title")
            )
        )
        assertTrue(
            IslandSlotContentAssembler.isActualLyricAvailable(
                sourceLine = RichLyricLine(text = "First lyric"),
                targetLine = RichLyricLine(text = "First lyric")
            )
        )
    }

    @Test
    fun `preprocessed title line is not treated as available lyrics`() {
        val titleLine = RichLyricLine(
            text = "Song title - Artist",
            metadata = com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf(
                "TitleLine" to "true"
            )
        )

        assertFalse(
            IslandSlotContentAssembler.isActualLyricAvailable(
                sourceLine = titleLine,
                targetLine = titleLine
            )
        )
        assertTrue(
            IslandSlotContentAssembler.isEmptyToPopulatedLyricTransition(
                currentLine = titleLine,
                targetLine = RichLyricLine(text = "First lyric")
            )
        )
    }

    @Test
    fun `normal lyric changes are not treated as lyrics becoming available`() {
        assertFalse(
            IslandSlotContentAssembler.isEmptyToPopulatedLyricTransition(
                currentLine = RichLyricLine(text = "Current lyric"),
                targetLine = RichLyricLine(text = "Next lyric")
            )
        )
        assertFalse(
            IslandSlotContentAssembler.isEmptyToPopulatedLyricTransition(
                currentLine = RichLyricLine(text = "Current lyric"),
                targetLine = null
            )
        )
    }

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

    @Test
    fun `display options switch back to translation when the next line has translation`() {
        val previewOptions = IslandSlotContentAssembler.resolveLyricDisplayOptions(
            translationDisabled = false,
            translationOnly = false,
            nextLinePreview = IslandSlotContentAssembler.shouldUseNextLinePreview(
                autoSwitchTranslation = true,
                currentLine = RichLyricLine(text = "No translation")
            )
        )
        val translationOptions = IslandSlotContentAssembler.resolveLyricDisplayOptions(
            translationDisabled = false,
            translationOnly = false,
            nextLinePreview = IslandSlotContentAssembler.shouldUseNextLinePreview(
                autoSwitchTranslation = true,
                currentLine = RichLyricLine(text = "Translated", translation = "有翻译")
            )
        )

        assertFalse(previewOptions.showTranslation)
        assertFalse(previewOptions.showRoma)
        assertTrue(translationOptions.showTranslation)
        assertTrue(translationOptions.showRoma)
    }

    @Test
    fun `background vocal translation follows the backing vocal time range`() {
        val source = RichLyricLine(
            begin = 1_000,
            end = 9_000,
            secondary = "Backing vocal",
            secondaryWords = listOf(
                LyricWord(begin = 5_200, end = 5_800, text = "Backing"),
                LyricWord(begin = 6_100, end = 6_900, text = " vocal")
            )
        )

        val words = IslandSlotContentAssembler.buildBackgroundTranslationWords(
            source,
            "伴唱翻译"
        )

        assertEquals(1, words.size)
        assertEquals("伴唱翻译", words.single().text)
        assertEquals(5_200L, words.single().begin)
        assertEquals(6_900L, words.single().end)
        assertEquals(1_700L, words.single().duration)
    }

    @Test
    fun `background vocal translation without backing timing keeps the existing fallback`() {
        val words = IslandSlotContentAssembler.buildBackgroundTranslationWords(
            RichLyricLine(begin = 1_000, end = 9_000, secondary = "Backing vocal"),
            "伴唱翻译"
        )

        assertTrue(words.isEmpty())
    }
}
