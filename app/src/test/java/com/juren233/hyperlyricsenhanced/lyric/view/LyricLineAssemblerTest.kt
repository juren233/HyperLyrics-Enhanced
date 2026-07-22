/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricLineAssemblerTest {

    @Test
    fun `keeps delayed secondary vocals visible with their original timing`() {
        val source = RichLyricLine(
            begin = 1000,
            end = 8000,
            text = "Main lyric",
            secondary = "Yeah",
            secondaryWords = listOf(
                LyricWord(begin = 6500, end = 7200, text = "Yeah")
            )
        )

        val result = LyricLineAssembler().buildSecondary(source)

        assertTrue(result.alwaysShow)
        assertEquals("Yeah", result.line.text)
        assertEquals(6500L, result.line.words.orEmpty().single().begin)
        assertEquals(7200L, result.line.words.orEmpty().single().end)
    }

    @Test
    fun `keeps an empty secondary line hidden`() {
        val result = LyricLineAssembler().buildSecondary(
            RichLyricLine(begin = 1000, end = 8000, text = "Main lyric")
        )

        assertFalse(result.alwaysShow)
    }

    @Test
    fun `uses the next lyric direction for a next line preview`() {
        val result = LyricLineAssembler().buildSecondary(
            RichLyricLine(
                text = "Current",
                secondary = "Next",
                isAlignedRight = false,
                metadata = lyricMetadataOf(
                    METADATA_NEXT_LINE_PREVIEW to "true",
                    METADATA_NEXT_LINE_PREVIEW_ALIGNED_RIGHT to "true"
                )
            )
        )

        assertTrue(result.isNextLinePreview)
        assertTrue(result.line.isAlignedRight)
    }

    @Test
    fun `promotes an existing preview even when the new line has other secondary content`() {
        assertTrue(
            shouldPromoteNextLinePreview(
                wasPreview = true,
                currentMainText = "Current",
                previewText = "Next",
                nextMainText = "Next"
            )
        )
        assertFalse(
            shouldPromoteNextLinePreview(
                wasPreview = true,
                currentMainText = "Current",
                previewText = "Different",
                nextMainText = "Next"
            )
        )
    }
}
