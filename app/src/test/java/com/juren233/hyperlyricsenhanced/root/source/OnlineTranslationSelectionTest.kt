/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.online.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineTranslationSelectionTest {
    @Test
    fun `ordered sources keep the first enabled translation authoritative`() {
        val nativeSong = song(
            line(1_000L, "First"),
            line(2_000L, "Second"),
        )
        val selection = OnlineTranslationSelection(
            onlineLinesBySource = mapOf(
                Source.NE to listOf(
                    LrcLine(1_000L, "First", translation = "网易第一句"),
                ),
                Source.KUGOU to listOf(
                    LrcLine(1_000L, "First", translation = "酷狗第一句"),
                    LrcLine(2_000L, "Second", translation = "酷狗第二句"),
                ),
            ),
            sourceOrder = listOf(Source.NE, Source.KUGOU),
        )

        val result = selection.compose(nativeSong, currentPublishedSong = null)

        assertEquals("网易第一句", result?.song?.lyrics?.get(0)?.translation)
        assertEquals("酷狗第二句", result?.song?.lyrics?.get(1)?.translation)
        assertEquals(
            Source.NE.name,
            result?.song?.metadata?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE),
        )
    }
    @Test
    fun `rematches fetched lines when native line structure changes during request`() {
        val requestSong = song(
            line(1_000L, "First"),
            line(2_000L, "Second"),
        )
        val latestNativeSong = song(
            line(500L, "New intro"),
            line(1_000L, "First"),
            line(2_000L, "Second"),
        )
        val onlineLines = listOf(
            LrcLine(1_000L, "First", translation = "第一行"),
            LrcLine(2_000L, "Second", translation = "第二行"),
        )
        val staleResult = OnlineTranslationMatcher.apply(requestSong, onlineLines)
        val selection = OnlineTranslationSelection(
            onlineLinesBySource = mapOf(Source.NE to onlineLines),
            defaultTranslationSource = Source.NE,
        )

        val result = selection.compose(latestNativeSong, currentPublishedSong = null)

        assertEquals(2, staleResult.song.lyrics.orEmpty().size)
        assertEquals(3, result?.song?.lyrics.orEmpty().size)
        assertNull(result?.song?.lyrics?.get(0)?.translation)
        assertEquals("第一行", result?.song?.lyrics?.get(1)?.translation)
        assertEquals("第二行", result?.song?.lyrics?.get(2)?.translation)
    }

    @Test
    fun `late Apple translation wins while online source fills remaining lines`() {
        val latestNativeSong = song(
            line(1_000L, "First", translation = "Apple 官方翻译"),
            line(2_000L, "Second"),
        )
        val selection = OnlineTranslationSelection(
            onlineLinesBySource = mapOf(
                Source.QM to listOf(
                    LrcLine(1_000L, "First", translation = "第三方第一行"),
                    LrcLine(2_000L, "Second", translation = "第三方第二行"),
                )
            ),
            defaultTranslationSource = Source.QM,
        )

        val result = selection.compose(latestNativeSong, currentPublishedSong = null)

        assertEquals("Apple 官方翻译", result?.song?.lyrics?.get(0)?.translation)
        assertEquals("第三方第二行", result?.song?.lyrics?.get(1)?.translation)
        assertEquals(
            "QM",
            result?.song?.metadata
                ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE),
        )
    }

    @Test
    fun `request miss keeps published enrichment without replacing late Apple content`() {
        val latestNativeSong = song(
            line(1_000L, "First", translation = "Apple 新翻译"),
            line(2_000L, "Second"),
        )
        val publishedSong = song(
            line(1_000L, "First", translation = "旧在线第一行"),
            line(2_000L, "Second", translation = "旧在线第二行"),
        ).copy(
            metadata = lyricMetadataOf(
                LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE to Source.NE.name,
            )
        )

        val result = OnlineTranslationSelection().compose(
            latestNativeSong = latestNativeSong,
            currentPublishedSong = publishedSong,
        )

        assertEquals("Apple 新翻译", result?.song?.lyrics?.get(0)?.translation)
        assertEquals("旧在线第二行", result?.song?.lyrics?.get(1)?.translation)
        assertEquals(
            "NE",
            result?.song?.metadata
                ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE),
        )
    }

    private fun song(vararg lyrics: RichLyricLine): Song = Song(
        id = "123",
        name = "Race Song",
        artist = "Artist",
        duration = 180_000L,
        lyrics = lyrics.toList(),
    )

    private fun line(
        begin: Long,
        text: String,
        translation: String? = null,
    ): RichLyricLine = RichLyricLine(
        begin = begin,
        end = begin + 900L,
        text = text,
        translation = translation,
    )
}
