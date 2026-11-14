/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online

import com.juren233.hyperlyricsenhanced.online.model.LyricsLine
import com.juren233.hyperlyricsenhanced.online.model.LyricsWord
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.source.ttml.TtmlLookupResult
import com.juren233.hyperlyricsenhanced.online.source.ttml.toSourceStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineLyricWordTimingTest {
    private fun line(start: Long, vararg words: String) = LyricsLine(
        start = start,
        end = start + 1_000L,
        words = words.mapIndexed { index, text ->
            LyricsWord(start + index * 100L, start + index * 100L + 100L, text)
        },
    )

    @Test
    fun `line-synced lyrics carry one whole-line word and are not word-timed`() {
        // 逐行 LRC 解析后每行也有一个整行分词；此前「有分词即逐字」把它标成了逐字。
        assertFalse(isWordTimedOnlineLyrics(listOf(line(0L, "第一句"), line(1_000L, "第二句"))))
    }

    @Test
    fun `a line split into two or more words is word-timed`() {
        assertTrue(isWordTimedOnlineLyrics(listOf(line(0L, "第一句"), line(1_000L, "第", "二", "句"))))
    }

    @Test
    fun `blank words are dropped by the Apple Music display and do not count`() {
        assertFalse(isWordTimedOnlineLyrics(listOf(line(0L, "第一句", " "), line(1_000L, "", "第二句"))))
    }

    @Test
    fun `words without durations still render word by word and count as word-timed`() {
        val zeroDuration = LyricsLine(
            start = 0L,
            end = 1_000L,
            words = listOf(LyricsWord(0L, 0L, "第"), LyricsWord(0L, 0L, "一句")),
        )
        assertTrue(isWordTimedOnlineLyrics(listOf(zeroDuration)))
    }

    @Test
    fun `empty or missing lyrics are not word-timed`() {
        assertFalse(isWordTimedOnlineLyrics(null))
        assertFalse(isWordTimedOnlineLyrics(emptyList()))
    }

    @Test
    fun `catalog miss is reported as not word-timed`() {
        assertFalse(TtmlLookupResult(match = null, searched = true).toSourceStatus(Source.AMLL).wordTimed)
    }
}
