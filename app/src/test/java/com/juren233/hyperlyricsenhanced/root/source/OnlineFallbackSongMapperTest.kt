package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineFallbackSongMapperTest {

    @Test
    fun `maps sorted lrc lines to rich lyric timing`() {
        val song = Song(id = "apple-id", name = "Song", artist = "Artist", duration = 10_000L)

        val mapped = OnlineFallbackSongMapper.map(
            song,
            listOf(
                LrcLine(4_000L, "Second"),
                LrcLine(1_000L, "First")
            )
        )

        assertNotNull(mapped)
        assertEquals("apple-id", mapped?.id)
        assertEquals(1_000L, mapped?.lyrics?.get(0)?.begin)
        assertEquals(4_000L, mapped?.lyrics?.get(0)?.end)
        assertEquals(4_000L, mapped?.lyrics?.get(1)?.begin)
        assertEquals(10_000L, mapped?.lyrics?.get(1)?.end)
    }

    @Test
    fun `drops blank and duplicate timestamp lines`() {
        val mapped = OnlineFallbackSongMapper.map(
            Song(name = "Song"),
            listOf(
                LrcLine(1_000L, ""),
                LrcLine(2_000L, "First"),
                LrcLine(2_000L, "Duplicate")
            )
        )

        assertEquals(1, mapped?.lyrics?.size)
        assertEquals("First", mapped?.lyrics?.single()?.text)
        assertEquals(7_000L, mapped?.lyrics?.single()?.end)
    }

    @Test
    fun `carries online translation into rich lyric line`() {
        val mapped = OnlineFallbackSongMapper.map(
            Song(name = "Song", duration = 10_000L),
            listOf(
                LrcLine(1_000L, "First", "第一句"),
                LrcLine(4_000L, "Second", "   ")
            )
        )

        assertEquals("第一句", mapped?.lyrics?.get(0)?.translation)
        assertNull(mapped?.lyrics?.get(1)?.translation)
    }

    @Test
    fun `returns null when no usable lines exist`() {
        assertNull(
            OnlineFallbackSongMapper.map(
                Song(name = "Song"),
                listOf(LrcLine(-1L, "Invalid"), LrcLine(0L, " "))
            )
        )
    }
}
