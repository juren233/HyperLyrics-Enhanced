/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceMetadata
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceStatus
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineSourceContentStatesCodec
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationMatchStatsCodec
import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.online.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `publishes per-source translation match stats against current lyric lines`() {
        val nativeSong = song(
            line(1_000L, "First"),
            line(2_000L, "Second"),
            line(3_000L, "Third"),
        )
        val selection = OnlineTranslationSelection(
            onlineLinesBySource = mapOf(
                Source.NE to listOf(
                    LrcLine(1_000L, "First", translation = "网易第一句"),
                    LrcLine(2_000L, "Second", translation = "网易第二句"),
                ),
                Source.QM to listOf(
                    LrcLine(1_000L, "First", translation = "QQ第一句"),
                ),
            ),
            sourceOrder = listOf(Source.NE, Source.QM),
        )

        val result = selection.compose(nativeSong, currentPublishedSong = null)
        val stats = OnlineTranslationMatchStatsCodec.decode(
            result?.song?.metadata
                ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_MATCH_STATS)
        )

        assertEquals(2, stats[Source.NE.name]?.matchedLines)
        assertEquals(3, stats[Source.NE.name]?.totalLines)
        assertEquals(1, stats[Source.QM.name]?.matchedLines)
        assertEquals(33, stats[Source.QM.name]?.percentage)
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

    @Test
    fun `hand-picked translation source replaces Apple's own translation lines`() {
        val nativeSong = song(
            line(1_000L, "First", translation = "Apple 官方翻译"),
            line(2_000L, "Second"),
        )
        val selection = OnlineTranslationSelection(
            onlineLinesBySource = mapOf(
                Source.NE to listOf(
                    LrcLine(1_000L, "First", translation = "网易第一句"),
                    LrcLine(2_000L, "Second", translation = "网易第二句"),
                ),
            ),
            forcedTranslationSource = Source.NE,
            control = OnlineSourceControl(automaticFill = false),
        )

        val result = selection.compose(nativeSong, currentPublishedSong = null)

        assertEquals("网易第一句", result?.song?.lyrics?.get(0)?.translation)
        assertEquals("网易第二句", result?.song?.lyrics?.get(1)?.translation)
        assertEquals(
            "NE",
            result?.song?.metadata?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE),
        )
        assertEquals(
            "true",
            result?.song?.metadata
                ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_OVERRIDES_NATIVE),
        )
    }

    @Test
    fun `selecting Apple native restores native content over published online content`() {
        val nativeSong = song(
            line(1_000L, "First", translation = "Apple 官方翻译"),
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
        val selection = OnlineTranslationSelection(
            onlineLinesBySource = mapOf(
                Source.NE to listOf(
                    LrcLine(1_000L, "First", translation = "旧在线第一行"),
                    LrcLine(2_000L, "Second", translation = "旧在线第二行"),
                ),
            ),
            control = OnlineSourceControl(
                automaticFill = false,
                translationNativeOnly = true,
            ),
        )

        val result = selection.compose(nativeSong, currentPublishedSong = publishedSong)

        assertEquals("Apple 官方翻译", result?.song?.lyrics?.get(0)?.translation)
        assertNull(result?.song?.lyrics?.get(1)?.translation)
        assertNull(
            result?.song?.metadata?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE),
        )
        assertNull(
            result?.song?.metadata
                ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_OVERRIDES_NATIVE),
        )
    }

    @Test
    fun `source status search keeps native content and publishes full stats`() {
        val nativeSong = song(
            line(1_000L, "First", translation = "Apple 官方翻译"),
            line(2_000L, "Second"),
        )
        val selection = OnlineTranslationSelection(
            onlineLinesBySource = mapOf(
                Source.NE to listOf(
                    LrcLine(1_000L, "First", translation = "网易第一句"),
                ),
            ),
            control = OnlineSourceControl(automaticFill = false),
            lyricsSourceStatuses = listOf(
                AppleMissingLyricsSourceStatus(
                    source = Source.NE.name,
                    searched = true,
                    found = true,
                    lineCount = 1,
                ),
            ),
        )

        val result = selection.compose(nativeSong, currentPublishedSong = null)

        // Without automatic fill and without a hand-picked source the result still exists,
        // carries Apple's own content untouched, and describes every source for the dialogs.
        assertTrue(result != null)
        assertEquals("Apple 官方翻译", result?.song?.lyrics?.get(0)?.translation)
        assertNull(result?.song?.lyrics?.get(1)?.translation)
        val translationStats = OnlineTranslationMatchStatsCodec.decode(
            result?.song?.metadata?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_MATCH_STATS),
        )
        assertEquals(1, translationStats[Source.NE.name]?.matchedLines)
        assertEquals(2, translationStats[Source.NE.name]?.totalLines)
        assertEquals(1, translationStats[OnlineSourceOverride.APPLE_NATIVE]?.matchedLines)
        assertEquals(2, translationStats[OnlineSourceOverride.APPLE_NATIVE]?.totalLines)
        val sourceStates = OnlineSourceContentStatesCodec.decode(
            result?.song?.metadata?.getString(LyricMetadataKeys.ONLINE_SOURCE_CONTENT_STATES),
        )
        assertTrue(sourceStates[OnlineSourceOverride.APPLE_NATIVE]?.hasTranslation == true)
        val sourceInfo = AppleMissingLyricsSourceMetadata.decode(
            selectedSource = null,
            encodedStatuses = result?.song?.metadata
                ?.getString(LyricMetadataKeys.ONLINE_LYRICS_SOURCE_STATUSES),
        )
        assertEquals(1, sourceInfo?.statuses?.size)
        assertEquals(Source.NE.name, sourceInfo?.statuses?.single()?.source)
        assertEquals(true, sourceInfo?.statuses?.single()?.found)
    }

    @Test
    fun `supplement-based song reports Apple as contentless in stats`() {
        val supplementSong = song(
            line(1_000L, "First", translation = "补充翻译"),
            line(2_000L, "Second", translation = "补充翻译二"),
        ).copy(
            metadata = lyricMetadataOf(
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT to "true",
            ),
        )
        val selection = OnlineTranslationSelection(
            onlineLinesBySource = mapOf(
                Source.NE to listOf(
                    LrcLine(1_000L, "First", translation = "网易第一句"),
                ),
            ),
            control = OnlineSourceControl(automaticFill = false),
        )

        val result = selection.compose(supplementSong, currentPublishedSong = null)

        val translationStats = OnlineTranslationMatchStatsCodec.decode(
            result?.song?.metadata?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_MATCH_STATS),
        )
        assertEquals(0, translationStats[OnlineSourceOverride.APPLE_NATIVE]?.matchedLines)
        val sourceStates = OnlineSourceContentStatesCodec.decode(
            result?.song?.metadata?.getString(LyricMetadataKeys.ONLINE_SOURCE_CONTENT_STATES),
        )
        assertFalse(sourceStates[OnlineSourceOverride.APPLE_NATIVE]?.hasTranslation == true)
    }

    @Test
    fun `partial progress reports answered sources only`() {
        val nativeSong = song(
            line(1_000L, "First"),
            line(2_000L, "Second"),
        )
        // 渐进版：网易已答且命中，QQ 已答但没查到，酷狗还没返回。
        val selection = OnlineTranslationSelection(
            onlineLinesBySource = mapOf(
                Source.NE to listOf(
                    LrcLine(1_000L, "First", translation = "网易第一句"),
                ),
            ),
            requestedSources = listOf(Source.NE, Source.QM),
            control = OnlineSourceControl(automaticFill = false),
        )

        val result = selection.compose(nativeSong, currentPublishedSong = null)

        val sourceStates = OnlineSourceContentStatesCodec.decode(
            result?.song?.metadata?.getString(LyricMetadataKeys.ONLINE_SOURCE_CONTENT_STATES),
        )
        assertTrue(sourceStates[Source.NE.name]?.hasTranslation == true)
        assertTrue(sourceStates[Source.QM.name]?.searchFailed == true)
        assertNull(sourceStates[Source.KUGOU.name])
        // 没有手选来源也没有自动补全时，渐进版不动歌词内容。
        assertNull(result?.song?.lyrics?.get(0)?.translation)
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
