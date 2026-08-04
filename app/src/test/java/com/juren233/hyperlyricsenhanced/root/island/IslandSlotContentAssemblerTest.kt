package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandSlotContentAssemblerTest {

    @Test
    fun `artwork is kept when lyric and media titles differ only by spacing or suffix`() {
        assertFalse(
            IslandSlotContentAssembler.shouldRejectArtworkForTitleMismatch(
                lyricTitle = "Michi Teyu Ku (Overflowing)",
                mediaTitle = "Michi Teyu Ku"
            )
        )
        assertFalse(
            IslandSlotContentAssembler.shouldRejectArtworkForTitleMismatch(
                lyricTitle = "満ちてゆく",
                mediaTitle = "満ちて ゆく"
            )
        )
    }

    @Test
    fun `artwork rejection identifies a real lyric media title mismatch`() {
        assertTrue(
            IslandSlotContentAssembler.shouldRejectArtworkForTitleMismatch(
                lyricTitle = "Completely Different Song",
                mediaTitle = "Current Media Song"
            )
        )
    }

    @Test
    fun `artwork is kept when media title is a lyric line with matching artist`() {
        assertFalse(
            IslandSlotContentAssembler.shouldRejectArtworkForTitleMismatch(
                lyricTitle = "NIGHT DANCER",
                mediaTitle = "響めき煌めきと君も 但身边有着那声响 光芒 还有你",
                lyricArtist = "imase",
                mediaArtist = "imase - NIGHT DANCER",
                mediaAlbum = "NIGHT DANCER"
            )
        )
    }

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
    fun `stable lyric title prevents notification lyric title from refreshing song info`() {
        val beforeCorrection = IslandSlotContentAssembler.buildMetadataLine(
            mode = 5,
            songName = IslandSlotContentAssembler.resolveMetadataSongName(
                lyricSongName = "NIGHT DANCER",
                currentSongName = "The current lyric line",
                mediaTitle = "The current lyric line"
            ),
            artistName = "imase",
            albumName = "NIGHT DANCER"
        )
        val afterCorrection = IslandSlotContentAssembler.buildMetadataLine(
            mode = 5,
            songName = IslandSlotContentAssembler.resolveMetadataSongName(
                lyricSongName = "NIGHT DANCER",
                currentSongName = "NIGHT DANCER",
                mediaTitle = "NIGHT DANCER"
            ),
            artistName = "imase",
            albumName = "NIGHT DANCER"
        )

        assertFalse(
            IslandSlotContentAssembler.hasLineContentChanged(beforeCorrection, afterCorrection)
        )
    }

    @Test
    fun `real song title change still refreshes song info`() {
        val current = IslandSlotContentAssembler.buildMetadataLine(
            mode = 5,
            songName = "Current song",
            artistName = "Artist",
            albumName = "Album"
        )
        val next = IslandSlotContentAssembler.buildMetadataLine(
            mode = 5,
            songName = "Next song",
            artistName = "Artist",
            albumName = "Album"
        )

        assertTrue(IslandSlotContentAssembler.hasLineContentChanged(current, next))
    }

    @Test
    fun `volatile notification title does not change final style cache identity`() {
        val stableMediaKey = "player\u001FNIGHT DANCER\u001Fimase\u001FNIGHT DANCER"
        val before = IslandSlotContentAssembler.buildStyleCacheSignature(
            styleSignature = "configured-style",
            mode = 5,
            mediaColorKey = stableMediaKey,
            artworkContentKey = 42
        )
        val afterNotificationLyric = IslandSlotContentAssembler.buildStyleCacheSignature(
            styleSignature = "configured-style",
            mode = 5,
            mediaColorKey = stableMediaKey,
            artworkContentKey = 42
        )
        val nextSong = IslandSlotContentAssembler.buildStyleCacheSignature(
            styleSignature = "configured-style",
            mode = 5,
            mediaColorKey = "player\u001FNext song\u001Fimase\u001FNext album",
            artworkContentKey = 84
        )

        assertEquals(before, afterNotificationLyric)
        assertNotEquals(before, nextSong)
    }

    @Test
    fun `half preview puts song info on primary line by default`() {
        val line = IslandSlotContentAssembler.buildHalfNextSongPreviewLine(
            title = "Song",
            artist = "Artist",
            label = "下一首",
            weight = RootConstants.ISLAND_NEXT_SONG_PREVIEW_WEIGHT_TOP
        )

        assertEquals("Song-Artist", line.text)
        assertEquals("下一首", line.secondary)
    }

    @Test
    fun `half preview bottom weight puts label on primary line`() {
        val line = IslandSlotContentAssembler.buildHalfNextSongPreviewLine(
            title = "Song",
            artist = "Artist",
            label = "下一首",
            weight = RootConstants.ISLAND_NEXT_SONG_PREVIEW_WEIGHT_BOTTOM
        )

        assertEquals("下一首", line.text)
        assertEquals("Song-Artist", line.secondary)
    }

    @Test
    fun `half preview omits separator when artist is blank`() {
        val line = IslandSlotContentAssembler.buildHalfNextSongPreviewLine(
            title = "Song",
            artist = "",
            label = "下一首",
            weight = RootConstants.ISLAND_NEXT_SONG_PREVIEW_WEIGHT_TOP
        )

        assertEquals("Song", line.text)
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
    fun `next line is shown when there is no translation or backing vocal`() {
        val line = RichLyricLine(text = "Current lyric")

        assertTrue(IslandSlotContentAssembler.shouldUseNextLinePreview(true, line))
    }

    @Test
    fun `translation display keeps translation or backing vocals in the second line`() {
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
    fun `disabled translation display lets the next line replace translation`() {
        assertTrue(
            IslandSlotContentAssembler.shouldUseNextLinePreview(
                false,
                RichLyricLine(text = "Current lyric", translation = "当前歌词")
            )
        )
    }

    @Test
    fun `backing vocals keep priority over the next line when translations are hidden`() {
        assertFalse(
            IslandSlotContentAssembler.shouldUseNextLinePreview(
                false,
                RichLyricLine(text = "Current lyric", secondary = "Backing")
            )
        )
    }

    @Test
    fun `overlapping lyrics keep their timed second line instead of using next line preview`() {
        val overlappingLine = RichLyricLine(
            begin = 1_000,
            end = 3_000,
            text = "Main lyric",
            secondary = "Overlapping lyric",
            secondaryWords = listOf(
                LyricWord(begin = 1_800, end = 2_800, text = "Overlapping lyric")
            )
        )

        assertFalse(
            IslandSlotContentAssembler.shouldUseNextLinePreview(
                false,
                overlappingLine
            )
        )
    }

    @Test
    fun `display options switch back to translation when the next line has translation`() {
        val previewOptions = IslandSlotContentAssembler.resolveLyricDisplayOptions(
            translationDisplayed = true,
            translationOnly = false,
            nextLinePreview = IslandSlotContentAssembler.shouldUseNextLinePreview(
                translationDisplayed = true,
                currentLine = RichLyricLine(text = "No translation")
            )
        )
        val translationOptions = IslandSlotContentAssembler.resolveLyricDisplayOptions(
            translationDisplayed = true,
            translationOnly = false,
            nextLinePreview = IslandSlotContentAssembler.shouldUseNextLinePreview(
                translationDisplayed = true,
                currentLine = RichLyricLine(text = "Translated", translation = "有翻译")
            )
        )

        assertFalse(previewOptions.showTranslation)
        assertFalse(previewOptions.showRoma)
        assertTrue(translationOptions.showTranslation)
        assertTrue(translationOptions.showRoma)
    }

    @Test
    fun `disabled translation display hides translation and romanization`() {
        val options = IslandSlotContentAssembler.resolveLyricDisplayOptions(
            translationDisplayed = false,
            translationOnly = false,
            nextLinePreview = false
        )

        assertFalse(options.showTranslation)
        assertFalse(options.showRoma)
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
