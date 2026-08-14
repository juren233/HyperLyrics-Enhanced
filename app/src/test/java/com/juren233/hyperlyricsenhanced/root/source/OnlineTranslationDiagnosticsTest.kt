/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.online.model.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineTranslationDiagnosticsTest {
    @Test
    fun `attributes a filled line to the source that actually supplied it`() {
        val base = song(RichLyricLine(begin = 1_000L, end = 2_000L, text = "First"))
        val result = song(
            RichLyricLine(
                begin = 1_000L,
                end = 2_000L,
                text = "First",
                translation = "QQ 翻译",
            ),
        )
        val qq = OnlineTranslationMatcher.apply(
            base,
            listOf(LrcLine(1_000L, "First", translation = "QQ 翻译")),
        )

        val contribution = OnlineTranslationDiagnostics.contributions(
            baseSong = base,
            resultSong = result,
            candidates = mapOf(Source.QM to qq),
            translationOrder = listOf(Source.QM),
            pronunciationOrder = listOf(Source.QM),
        ).single()

        assertEquals(Source.QM, contribution.translationSource)
    }

    @Test
    fun `explains a slash placeholder and a missing candidate separately`() {
        val base = song(
            RichLyricLine(begin = 1_000L, end = 2_000L, text = "Placeholder"),
            RichLyricLine(begin = 3_000L, end = 4_000L, text = "Absent"),
        )
        val result = base
        val qqLines = listOf(LrcLine(1_000L, "Placeholder", translation = "///"))
        val qqResult = OnlineTranslationMatcher.apply(base, qqLines)

        val missing = OnlineTranslationDiagnostics.missingLines(
            resultSong = result,
            requestedSources = listOf(Source.QM, Source.NE),
            onlineLinesBySource = mapOf(Source.QM to qqLines),
            candidates = mapOf(Source.QM to qqResult),
            pronunciationRequested = false,
        )

        assertEquals("placeholder_sanitized", missing[0].reasonsBySource[Source.QM]?.single())
        assertEquals("candidate_unavailable", missing[0].reasonsBySource[Source.NE]?.single())
        assertEquals("match_miss", missing[1].reasonsBySource[Source.QM]?.single())
    }

    private fun song(vararg lines: RichLyricLine): Song = Song(
        id = "diagnostic-song",
        name = "Diagnostic Song",
        artist = "Artist",
        lyrics = lines.toList(),
    )
}
