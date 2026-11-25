package com.juren233.hyperlyricsenhanced.lyric.view

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.LyricWordsMetadataCodec
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongPreprocessorBackingWordsTest {
    @Test
    fun `overlapping group keeps each line's backing word timings`() {
        val primaryBacking = listOf(
            LyricWord(begin = 1_200, end = 1_500, text = "ooh "),
            LyricWord(begin = 1_500, end = 1_900, text = "yeah"),
        )
        val song = Song(
            id = "1",
            name = "song",
            artist = "artist",
            duration = 10_000,
            lyrics = listOf(
                RichLyricLine(
                    begin = 1_000, end = 3_000, text = "first line",
                    words = listOf(LyricWord(begin = 1_000, end = 3_000, text = "first line")),
                    secondary = "ooh yeah",
                    secondaryWords = primaryBacking,
                ),
                RichLyricLine(
                    begin = 2_000, end = 4_000, text = "second line",
                    words = listOf(LyricWord(begin = 2_000, end = 4_000, text = "second line")),
                ),
            ),
        )

        val group = SongPreprocessor(TitleSlot.NAME_ARTIST).prepare(song)
            .single { it.metadata?.getBoolean(LyricMetadataKeys.OVERLAPPING_LYRICS_GROUP) == true }
        val metadata = group.metadata!!

        assertEquals(
            primaryBacking,
            LyricWordsMetadataCodec.decode(
                metadata.getString(LyricMetadataKeys.OVERLAPPING_PRIMARY_BACKING_WORDS)
            ),
        )
        assertNull(metadata.getString(LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING_WORDS))
    }

    @Test
    fun `codec ignores empty and broken values`() {
        assertNull(LyricWordsMetadataCodec.encode(emptyList()))
        assertNull(LyricWordsMetadataCodec.decode(""))
        assertNull(LyricWordsMetadataCodec.decode("not json"))
    }
}
