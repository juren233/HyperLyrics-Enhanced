/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiMusicMetadataRefreshPolicyTest {
    private val song = Song(
        name = "天空没有极限",
        artist = "G.E.M.邓紫棋",
        lyrics = listOf(RichLyricLine(begin = 0, end = 1_000, text = "歌词")),
    )

    @Test
    fun `car lyric title and combined artist cannot rebind active Xiaomi lyrics`() {
        assertFalse(forward(title = "当我躲在房间乐极忘形地表演"))
        assertFalse(forward(title = "天空没有极限", artist = "天空没有极限-G.E.M.邓紫棋"))
    }

    @Test
    fun `matching song metadata can refresh without blocking album or display corrections`() {
        assertTrue(forward(title = " 天空没有极限 ", artist = "g.e.m.邓紫棋"))
    }

    @Test
    fun `track transitions fallback and other players still receive metadata`() {
        assertTrue(forward(title = "新曲", sameAppliedTrack = false))
        assertTrue(forward(title = "歌词行", activeSong = song.copy(lyrics = emptyList())))
        assertTrue(forward(title = "歌词行", packageName = "com.other.player"))
    }

    private fun forward(
        title: String,
        artist: String = song.artist.orEmpty(),
        packageName: String = "com.miui.player",
        sameAppliedTrack: Boolean = true,
        activeSong: Song? = song,
    ) = XiaomiMusicMetadataRefreshPolicy.shouldForward(
        packageName = packageName,
        sameAppliedTrack = sameAppliedTrack,
        activeSong = activeSong,
        title = title,
        artist = artist,
    )
}
