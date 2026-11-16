/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.online.OnlineLyricTargeter
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheLastUsed
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheUsage
import com.juren233.hyperlyricsenhanced.online.model.LyricsLine
import com.juren233.hyperlyricsenhanced.online.model.LyricsWord
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.root.timeline.TimelineContentPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionSearchSongMapperTest {
    private val base = Song(id = "pkg|petal", name = "petal", artist = "Ariana Grande", duration = 10_000)
    private val lines = listOf(
        LrcLine(1_000, "夜に駆ける", translation = "奔向夜晚", romanization = "yoru ni kakeru"),
        LrcLine(4_000, "To cry again", translation = "再次哭泣"),
    )
    private val wordLines = listOf(
        LyricsLine(1_000, 3_500, listOf(LyricsWord(1_000, 1_400, "夜に"), LyricsWord(1_400, 2_000, "駆ける"))),
        LyricsLine(
            4_000, 6_000,
            listOf(LyricsWord(4_000, 4_300, "To "), LyricsWord(4_300, 5_000, "cry")),
            secondaryWords = listOf(LyricsWord(5_000, 5_500, "again")),
        ),
    )

    @Test fun `word timed results keep their words and everything a line result had`() {
        val song = MediaSessionSearchSongMapper.map(
            base, "song:x", OnlineLyricTargeter.FetchOutcome(lines = lines, wordLines = wordLines, selectedSource = Source.QM),
        )!!
        assertTrue(TimelineContentPolicy.isWordTimed(song))
        val first = song.lyrics!!.first()
        assertEquals(listOf("夜に", "駆ける"), first.words!!.map { it.text })
        assertEquals("夜に駆ける", first.text)
        assertEquals("奔向夜晚", first.translation)
        assertEquals("yoru ni kakeru", first.roma)
        assertEquals("again", song.lyrics!![1].secondary)
        assertEquals(
            "the body is filed under its search source",
            "song:x" to OnlineLyricCacheLastUsed(Source.QM.name, Source.QM.name, Source.QM.name),
            OnlineLyricCacheUsage.usedSources(song),
        )
    }

    @Test fun `line results stay line timed`() {
        val lineOnly = listOf(LyricsLine(1_000, 3_500, listOf(LyricsWord(1_000, 3_500, "夜に駆ける"))))
        val song = MediaSessionSearchSongMapper.map(
            base, null, OnlineLyricTargeter.FetchOutcome(lines = lines, wordLines = lineOnly, selectedSource = Source.NE),
        )!!
        assertFalse(TimelineContentPolicy.isWordTimed(song))
        assertTrue(song.lyrics!!.all { it.words.isNullOrEmpty() })
        assertNull(MediaSessionSearchSongMapper.map(base, null, OnlineLyricTargeter.FetchOutcome()))
    }
}
