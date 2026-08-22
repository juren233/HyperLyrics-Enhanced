/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceInfo
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceStatus
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.online.model.LyricsLine
import com.juren233.hyperlyricsenhanced.online.model.LyricsWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMissingLyricsSongMapperTest {

    private fun word(start: Long, end: Long, text: String) = LyricsWord(start, end, text)

    private fun line(start: Long, words: List<LyricsWord>) = LyricsLine(
        start = start,
        end = start + 1_000L,
        words = words,
    )

    @Test
    fun `map preserves per-word timelines and marks supplement payload`() {
        val baseSong = Song(
            id = "123",
            name = "无歌词歌曲",
            artist = "测试歌手",
            duration = 5_000L,
        )
        val mapped = AppleMissingLyricsSongMapper.map(
            baseSong = baseSong,
            lines = listOf(
                line(0L, listOf(word(0L, 300L, "第一"), word(300L, 700L, "句"))),
                line(1_000L, listOf(word(1_000L, 2_000L, "第二句"))),
            ),
        ) ?: error("expected mapped song")

        assertEquals("123", mapped.id)
        assertTrue(
            mapped.metadata?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
                .toBoolean()
        )
        assertEquals(2, mapped.lyrics?.size)
        val first = mapped.lyrics!![0]
        assertEquals("第一句", first.text)
        assertEquals(2, first.words?.size)
        assertEquals("第一", first.words!![0].text)
        assertEquals(0L, first.words!![0].begin)
        assertEquals(300L, first.words!![0].end)
        assertEquals(1_000L, first.end)
    }

    @Test
    fun `map preserves base metadata entries`() {
        val baseSong = Song(
            id = "123",
            name = "歌",
            duration = 5_000L,
            metadata = lyricMetadataOf("customKey" to "customValue"),
        )
        val mapped = AppleMissingLyricsSongMapper.map(
            baseSong = baseSong,
            lines = listOf(line(0L, listOf(word(0L, 1_000L, "词")))),
        ) ?: error("expected mapped song")

        assertEquals("customValue", mapped.metadata?.getString("customKey"))
    }

    @Test
    fun `map rejects blank or invalid lines`() {
        val baseSong = Song(id = "123", name = "歌", duration = 5_000L)
        assertNull(
            AppleMissingLyricsSongMapper.map(baseSong, emptyList())
        )
        assertNull(
            AppleMissingLyricsSongMapper.map(
                baseSong,
                listOf(line(0L, listOf(word(0L, 1_000L, "   ")))),
            )
        )
    }

    @Test
    fun `map fills last line end from duration`() {
        val baseSong = Song(id = "123", name = "歌", duration = 9_000L)
        val mapped = AppleMissingLyricsSongMapper.map(
            baseSong = baseSong,
            lines = listOf(line(1_000L, listOf(word(1_000L, 2_000L, "尾句")))),
        ) ?: error("expected mapped song")

        val last = mapped.lyrics!!.last()
        assertEquals(9_000L, last.end)
        assertEquals(8_000L, last.duration)
    }

    @Test
    fun `map preserves translation and source selection metadata`() {
        val baseSong = Song(id = "123", name = "歌", duration = 5_000L)
        val mapped = AppleMissingLyricsSongMapper.map(
            baseSong = baseSong,
            wordLines = listOf(line(0L, listOf(word(0L, 1_000L, "原句")))),
            lrcLines = listOf(LrcLine(0L, "原句", translation = "译句")),
            sourceInfo = AppleMissingLyricsSourceInfo(
                selectedSource = "KUWO",
                statuses = listOf(
                    AppleMissingLyricsSourceStatus("KUWO", true, true, false, 1),
                ),
            ),
        ) ?: error("expected mapped song")

        assertEquals("译句", mapped.lyrics!!.single().translation)
        assertEquals("KUWO", mapped.metadata?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE))
        assertEquals(
            "KUWO|true|true|false|1",
            mapped.metadata?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE_STATUSES),
        )
    }

    @Test
    fun `map reuses previous translation when switched source shifts line timing`() {
        val baseSong = Song(
            id = "123",
            name = "歌",
            duration = 5_000L,
            lyrics = listOf(
                RichLyricLine(
                    begin = 0L,
                    end = 1_000L,
                    text = "原句",
                    translation = "旧翻译",
                ),
            ),
        )
        val mapped = AppleMissingLyricsSongMapper.map(
            baseSong = baseSong,
            wordLines = listOf(line(240L, listOf(word(240L, 1_240L, "原句")))),
            lrcLines = listOf(LrcLine(240L, "原句")),
            sourceInfo = AppleMissingLyricsSourceInfo("KUGOU", emptyList()),
        ) ?: error("expected mapped song")

        assertEquals("旧翻译", mapped.lyrics!!.single().translation)
    }

    @Test
    fun `map falls back to line timed lrc when word timing is unavailable`() {
        val baseSong = Song(id = "123", name = "歌", duration = 5_000L)
        val mapped = AppleMissingLyricsSongMapper.map(
            baseSong = baseSong,
            wordLines = null,
            lrcLines = listOf(
                LrcLine(0L, "第一句", translation = "译句"),
                LrcLine(1_000L, "第二句"),
            ),
            sourceInfo = AppleMissingLyricsSourceInfo(
                selectedSource = "NE",
                statuses = listOf(
                    AppleMissingLyricsSourceStatus("NE", true, true, false, 2),
                ),
            ),
        ) ?: error("expected mapped song")

        assertEquals(listOf("第一句", "第二句"), mapped.lyrics!!.map { it.text })
        assertTrue(mapped.lyrics!!.all { it.words.isNullOrEmpty() })
        assertEquals("译句", mapped.lyrics!![0].translation)
        assertEquals("NE", mapped.metadata?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE))
    }
    @Test
    fun `LunaBeat map preserves source spaces timing and raw TTML metadata`() {
        val rawTtml = "<tt><p><span>I've</span> <span>said</span></p></tt>"
        val mapped = AppleMissingLyricsSongMapper.map(
            baseSong = Song(id = "123", name = "歌", duration = 5_000L),
            wordLines = listOf(
                LyricsLine(
                    start = 1_000L,
                    end = 2_877L,
                    words = listOf(
                        word(1_000L, 1_400L, "I've "),
                        word(1_400L, 1_800L, "said "),
                        word(1_800L, 2_100L, "it "),
                        word(2_100L, 2_877L, "all"),
                    ),
                )
            ),
            lrcLines = listOf(LrcLine(1_000L, "I've said it all")),
            sourceInfo = AppleMissingLyricsSourceInfo("LB", emptyList()),
            rawAppleTtml = rawTtml,
            sourceLyricId = "hub-id",
            sourceLyricSha256 = "A".repeat(64),
        ) ?: error("expected mapped song")

        val line = mapped.lyrics!!.single()
        assertEquals("I've said it all", line.text)
        assertEquals("I've said it all", line.words!!.joinToString("") { it.text.orEmpty() })
        assertEquals(2_877L, line.end)
        assertEquals(rawTtml, mapped.metadata?.getString(LyricMetadataKeys.LUNA_BEAT_RAW_TTML))
        assertEquals("hub-id", mapped.metadata?.getString(LyricMetadataKeys.LUNA_BEAT_HUB_ID))
        assertEquals("a".repeat(64), mapped.metadata?.getString(LyricMetadataKeys.LUNA_BEAT_TTML_SHA256))
    }

    @Test
    fun `non LunaBeat map removes stale raw TTML metadata`() {
        val mapped = AppleMissingLyricsSongMapper.map(
            baseSong = Song(
                id = "123",
                duration = 5_000L,
                metadata = lyricMetadataOf(
                    LyricMetadataKeys.LUNA_BEAT_RAW_TTML to "stale",
                    LyricMetadataKeys.LUNA_BEAT_HUB_ID to "stale-id",
                    LyricMetadataKeys.LUNA_BEAT_TTML_SHA256 to "f".repeat(64),
                ),
            ),
            wordLines = listOf(line(0L, listOf(word(0L, 1_000L, "普通歌词")))),
            lrcLines = null,
            sourceInfo = AppleMissingLyricsSourceInfo("NE", emptyList()),
        ) ?: error("expected mapped song")

        assertNull(mapped.metadata?.getString(LyricMetadataKeys.LUNA_BEAT_RAW_TTML))
        assertNull(mapped.metadata?.getString(LyricMetadataKeys.LUNA_BEAT_HUB_ID))
        assertNull(mapped.metadata?.getString(LyricMetadataKeys.LUNA_BEAT_TTML_SHA256))
    }

}
