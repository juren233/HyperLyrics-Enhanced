/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyriconSourceMetadataTest {

    @Test
    fun `central same-track callback inherits supplement source metadata`() {
        val previous = Song(
            id = "1810905308",
            name = "Shapeshifter",
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT to "true",
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE to "NE",
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE_STATUSES to
                    "NE|true|true|false|60",
            ),
        )
        val incoming = Song(
            id = "1810905308",
            name = "Shapeshifter",
            metadata = lyricMetadataOf("appleLyricsCacheSource" to "apple"),
        )

        val merged = mergeMissingLyricsSupplementMetadata(previous, incoming, sameTrack = true)

        assertTrue(
            merged!!.metadata
                ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
                .toBoolean()
        )
        assertEquals(
            "NE",
            merged.metadata?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE),
        )
        assertEquals(
            "NE|true|true|false|60",
            merged.metadata?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE_STATUSES),
        )
        assertEquals("apple", merged.metadata?.getString("appleLyricsCacheSource"))
    }

    @Test
    fun `confirmed native callback replaces previous same-track supplement metadata`() {
        val previous = Song(
            id = "1395620514",
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT to "true",
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE to "NE",
            ),
            lyrics = listOf(RichLyricLine(begin = 0L, end = 1_000L, text = "补充歌词")),
        )
        val native = Song(
            id = "1395620514",
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE to "apple",
                LyricMetadataKeys.APPLE_NATIVE_LYRICS_CONFIRMED to "true",
            ),
            lyrics = listOf(RichLyricLine(begin = 0L, end = 1_000L, text = "原生歌词")),
        )

        val merged = mergeMissingLyricsSupplementMetadata(previous, native, sameTrack = true)

        assertEquals(native, merged)
        assertTrue(hasConfirmedAppleNativeLyrics(merged))
        assertFalse(
            merged?.metadata
                ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
                .toBoolean()
        )
    }

    @Test
    fun `authoritative LunaBeat selection keeps word lyrics when native lyrics arrive late`() {
        val lunaBeat = Song(
            id = "1395620514",
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT to "true",
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE to "LB",
            ),
            lyrics = listOf(RichLyricLine(begin = 0L, end = 1_000L, text = "LB逐字歌词")),
        )
        val native = Song(
            id = "1395620514",
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE to "apple",
                LyricMetadataKeys.APPLE_NATIVE_LYRICS_CONFIRMED to "true",
            ),
            lyrics = listOf(RichLyricLine(begin = 0L, end = 1_000L, text = "原生歌词")),
        )

        val merged = mergeMissingLyricsSupplementMetadata(
            previousSong = lunaBeat,
            incomingSong = native,
            sameTrack = true,
            authoritativeSource = "LB",
        )

        assertEquals(lunaBeat, merged)
    }

    @Test
    fun `native metadata is not overwritten when incoming model is already supplement`() {
        val incoming = Song(
            id = "1",
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT to "true",
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE to "QM",
            ),
        )
        val merged = mergeMissingLyricsSupplementMetadata(
            previousSong = incoming,
            incomingSong = incoming,
            sameTrack = true,
        )
        assertEquals(incoming, merged)
    }

    @Test
    fun `confirmed manual lyrics source rejects a stale same-track supplement`() {
        val selected = Song(
            id = "1810905308",
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT to "true",
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE to "NE",
            ),
            lyrics = listOf(RichLyricLine(begin = 0L, end = 1_000L, text = "网易正文")),
        )
        val stale = Song(
            id = "1810905308",
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT to "true",
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE to "KUGOU",
            ),
            lyrics = listOf(RichLyricLine(begin = 0L, end = 1_000L, text = "酷狗旧正文")),
        )

        val merged = mergeMissingLyricsSupplementMetadata(
            previousSong = selected,
            incomingSong = stale,
            sameTrack = true,
            authoritativeSource = "NE",
        )

        assertEquals(selected, merged)
    }

    @Test
    fun `confirmed manual lyrics source accepts a current same-track supplement`() {
        val previous = Song(
            id = "1810905308",
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT to "true",
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE to "NE",
            ),
        )
        val current = previous.copy(
            lyrics = listOf(RichLyricLine(begin = 0L, end = 1_000L, text = "网易新正文")),
        )

        val merged = mergeMissingLyricsSupplementMetadata(
            previousSong = previous,
            incomingSong = current,
            sameTrack = true,
            authoritativeSource = "NE",
        )

        assertEquals(current, merged)
    }

    private fun supplementSong(id: String, withLyrics: Boolean) = Song(
        id = id,
        metadata = lyricMetadataOf(
            LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT to "true",
        ),
        lyrics = if (withLyrics) {
            listOf(RichLyricLine(begin = 0L, end = 1_000L, text = "补充歌词"))
        } else {
            emptyList()
        },
    )

    @Test
    fun `supplement lyrics are a valid base for online translation matching`() {
        assertTrue(
            hasAppleLyricsForOnlineEnrichment(
                song = supplementSong(id = "1", withLyrics = true),
                confirmedNativeLyrics = false,
            )
        )
    }

    @Test
    fun `empty supplement payload is not treated as matchable native lyrics`() {
        assertFalse(
            hasAppleLyricsForOnlineEnrichment(
                song = supplementSong(id = "1", withLyrics = false),
                confirmedNativeLyrics = false,
            )
        )
    }

    @Test
    fun `unmarked fallback lyrics are not treated as confirmed Apple native lyrics`() {
        assertFalse(
            hasAppleLyricsForOnlineEnrichment(
                song = Song(
                    id = "1",
                    lyrics = listOf(
                        RichLyricLine(begin = 0L, end = 1_000L, text = "普通兜底歌词"),
                    ),
                ),
                confirmedNativeLyrics = false,
            )
        )
    }

    @Test
    fun `confirmed native lyrics remain matchable without a supplement marker`() {
        assertTrue(
            hasAppleLyricsForOnlineEnrichment(
                song = Song(id = "1", lyrics = emptyList()),
                confirmedNativeLyrics = true,
            )
        )
    }
}
