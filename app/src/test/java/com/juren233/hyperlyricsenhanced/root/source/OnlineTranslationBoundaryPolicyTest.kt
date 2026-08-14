/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineTranslationBoundaryPolicyTest {
    @Test
    fun `commits at the next lyric begin while a line is playing`() {
        val lines = listOf(
            RichLyricLine(begin = 1_000L, end = 2_000L, text = "Current"),
            RichLyricLine(begin = 2_500L, end = 3_500L, text = "Next"),
        )

        assertEquals(
            2_500L,
            OnlineTranslationBoundaryPolicy.nextCommitPosition(lines, currentPosition = 1_500L),
        )
    }

    @Test
    fun `uses the first lyric begin before playback has entered lyrics`() {
        val lines = listOf(
            RichLyricLine(begin = 1_000L, end = 2_000L, text = "First"),
            RichLyricLine(begin = 2_500L, end = 3_500L, text = "Next"),
        )

        assertEquals(
            1_000L,
            OnlineTranslationBoundaryPolicy.nextCommitPosition(lines, currentPosition = 0L),
        )
    }

    @Test
    fun `allows immediate commit when the result arrives exactly at a lyric boundary`() {
        val lines = listOf(
            RichLyricLine(begin = 1_000L, end = 2_000L, text = "First"),
            RichLyricLine(begin = 2_500L, end = 3_500L, text = "Next"),
        )

        assertEquals(
            2_500L,
            OnlineTranslationBoundaryPolicy.nextCommitPosition(lines, currentPosition = 2_500L),
        )
    }

    @Test
    fun `returns null when no future boundary exists`() {
        val lines = listOf(RichLyricLine(begin = 1_000L, end = 2_000L, text = "Last"))

        assertNull(
            OnlineTranslationBoundaryPolicy.nextCommitPosition(lines, currentPosition = 2_000L),
        )
    }
}
