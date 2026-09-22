/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricLineAssemblerTest {

    @Test
    fun `text unit progress keeps English translation as complete words`() {
        val result = LyricLineAssembler(
            displayMode = com.juren233.hyperlyricsenhanced.common.RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION,
            secondaryTextUnitProgress = true,
        ).buildSecondary(
            RichLyricLine(
                begin = 1_000,
                end = 4_000,
                text = "Main",
                translation = "Hello, brave world!",
            )
        )

        val words = result.line.normalize().words.orEmpty()
        assertEquals(listOf("Hello, ", "brave ", "world!"), words.map { it.text })
        assertEquals(listOf(1_000L, 2_000L, 3_000L), words.map { it.begin })
        assertEquals(listOf(2_000L, 3_000L, 4_000L), words.map { it.end })
        assertTrue(result.isScrollOnly)
    }

    @Test
    fun `text unit progress preserves source timing while splitting pronunciation glyphs`() {
        val result = LyricLineAssembler(
            displayMode = com.juren233.hyperlyricsenhanced.common.RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION,
            secondaryTextUnitProgress = true,
        ).buildSecondary(
            RichLyricLine(
                begin = 2_000,
                end = 4_000,
                text = "Main",
                roma = "かな",
            )
        )

        val words = result.line.normalize().words.orEmpty()
        assertEquals(listOf("か", "な"), words.map { it.text })
        assertEquals(2_000L, words.first().begin)
        assertEquals(4_000L, words.last().end)
    }

    @Test
    fun `text unit progress keeps Latin words intact in mixed translation`() {
        val result = LyricLineAssembler(
            displayMode = com.juren233.hyperlyricsenhanced.common.RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION,
            secondaryTextUnitProgress = true,
        ).buildSecondary(
            RichLyricLine(
                begin = 1_000,
                end = 4_000,
                text = "Main",
                translation = "我 love 你",
            )
        )

        val words = result.line.normalize().words.orEmpty()
        assertEquals(listOf("我", " love ", "你"), words.map { it.text })
        assertEquals("我 love 你", words.joinToString("") { it.text.orEmpty() })
    }

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
    fun `uses the concurrent lyric direction for a simultaneous duet secondary line`() {
        val result = LyricLineAssembler().buildSecondary(
            RichLyricLine(
                text = "Left duet",
                secondary = "Right duet",
                isAlignedRight = false,
                metadata = lyricMetadataOf(
                    LyricMetadataKeys.CONCURRENT_SECONDARY_ALIGNED_RIGHT to "true"
                )
            )
        )

        assertTrue(result.alwaysShow)
        assertTrue(result.line.isAlignedRight)
    }

    @Test
    fun `promotes an existing preview even when the new line has other secondary content`() {
        assertTrue(
            shouldPromoteNextLinePreview(
                wasPreview = true,
                currentMainText = "Current",
                previewText = "Next",
                nextMainText = "Next",
                lineAdvanced = true
            )
        )
        assertFalse(
            shouldPromoteNextLinePreview(
                wasPreview = true,
                currentMainText = "Current",
                previewText = "Different",
                nextMainText = "Next",
                lineAdvanced = true
            )
        )
    }

    @Test
    fun `identical consecutive lyrics still promote when the timeline advances`() {
        val previous = RichLyricLine(begin = 1_000, end = 2_000, text = "Again")
        val target = RichLyricLine(begin = 2_000, end = 3_000, text = "Again")

        assertTrue(hasLyricLineAdvanced(previous, target))
        assertTrue(
            shouldPromoteNextLinePreview(
                wasPreview = true,
                currentMainText = "Again",
                previewText = "Again",
                nextMainText = "Again",
                lineAdvanced = hasLyricLineAdvanced(previous, target)
            )
        )
    }

    @Test
    fun `same lyric refresh does not trigger preview promotion`() {
        val previous = RichLyricLine(begin = 1_000, end = 2_000, text = "Again")
        val target = RichLyricLine(begin = 1_000, end = 2_000, text = "Again")

        assertFalse(hasLyricLineAdvanced(previous, target))
        assertFalse(
            shouldPromoteNextLinePreview(
                wasPreview = true,
                currentMainText = "Again",
                previewText = "Again",
                nextMainText = "Again",
                lineAdvanced = hasLyricLineAdvanced(previous, target)
            )
        )
    }

    @Test
    fun `interlude indicator uses outer transition instead of preview promotion`() {
        assertFalse(
            canAnimateNextLinePromotion(
                wasPreview = true,
                currentMainText = "Current lyric",
                previewText = "Next lyric",
                nextMainText = "•••",
                lineAdvanced = true,
                attached = true,
                mainHeight = 40,
                secondaryHeight = 24
            )
        )
    }

    @Test
    fun `matching laid out preview uses internal promotion`() {
        assertTrue(
            canAnimateNextLinePromotion(
                wasPreview = true,
                currentMainText = "•••",
                previewText = "Next lyric",
                nextMainText = "Next lyric",
                lineAdvanced = true,
                attached = true,
                mainHeight = 40,
                secondaryHeight = 24
            )
        )
    }

    @Test
    fun `unmeasured preview falls back to outer transition`() {
        assertFalse(
            canAnimateNextLinePromotion(
                wasPreview = true,
                currentMainText = "Current lyric",
                previewText = "Next lyric",
                nextMainText = "Next lyric",
                lineAdvanced = true,
                attached = true,
                mainHeight = 0,
                secondaryHeight = 0
            )
        )
    }

    @Test
    fun `assembler respects displayMode and fallback behavior`() {
        val lineWithBoth = RichLyricLine(
            text = "Main",
            translation = "Translation",
            roma = "Roma"
        )
        val lineWithRomaOnly = RichLyricLine(
            text = "Main",
            translation = null,
            roma = "Roma"
        )
        val lineWithTransOnly = RichLyricLine(
            text = "Main",
            translation = "Translation",
            roma = null
        )

        // Translation mode, fallback = false
        val transNoFallback = LyricLineAssembler(
            displayMode = com.juren233.hyperlyricsenhanced.common.RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION,
            fallback = false
        )
        assertEquals("Translation", transNoFallback.buildSecondary(lineWithBoth).line.text)
        assertFalse(transNoFallback.buildSecondary(lineWithRomaOnly).alwaysShow)

        // Translation mode, fallback = true
        val transWithFallback = LyricLineAssembler(
            displayMode = com.juren233.hyperlyricsenhanced.common.RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION,
            fallback = true
        )
        assertEquals("Translation", transWithFallback.buildSecondary(lineWithBoth).line.text)
        assertEquals("Roma", transWithFallback.buildSecondary(lineWithRomaOnly).line.text)

        // Pronunciation mode, fallback = false
        val romaNoFallback = LyricLineAssembler(
            displayMode = com.juren233.hyperlyricsenhanced.common.RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION,
            fallback = false
        )
        assertEquals("Roma", romaNoFallback.buildSecondary(lineWithBoth).line.text)
        assertFalse(romaNoFallback.buildSecondary(lineWithTransOnly).alwaysShow)

        // Pronunciation mode, fallback = true
        val romaWithFallback = LyricLineAssembler(
            displayMode = com.juren233.hyperlyricsenhanced.common.RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION,
            fallback = true
        )
        assertEquals("Roma", romaWithFallback.buildSecondary(lineWithBoth).line.text)
        assertEquals("Translation", romaWithFallback.buildSecondary(lineWithTransOnly).line.text)

        // Off mode
        val offAssembler = LyricLineAssembler(
            displayMode = com.juren233.hyperlyricsenhanced.common.RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
            fallback = true
        )
        assertFalse(offAssembler.buildSecondary(lineWithBoth).alwaysShow)
    }
    @Test
    fun `new timeline line preempts a running preview promotion`() {
        val promoted = RichLyricLine(begin = 1_000, end = 1_180, text = "Second")
        val incoming = RichLyricLine(begin = 1_180, end = 1_360, text = "Third")

        assertTrue(
            shouldFinishRunningPromotionBeforeApplying(
                promotionRunning = true,
                promotedLine = promoted,
                incomingLine = incoming,
            )
        )
    }

    @Test
    fun `same timeline line refresh does not preempt promotion`() {
        val promoted = RichLyricLine(begin = 1_000, end = 2_000, text = "Second")
        val refreshed = RichLyricLine(begin = 1_000, end = 2_000, text = "Second updated")

        assertFalse(
            shouldFinishRunningPromotionBeforeApplying(
                promotionRunning = true,
                promotedLine = promoted,
                incomingLine = refreshed,
            )
        )
        assertFalse(
            shouldFinishRunningPromotionBeforeApplying(
                promotionRunning = false,
                promotedLine = promoted,
                incomingLine = RichLyricLine(begin = 2_000, end = 3_000, text = "Third"),
            )
        )
    }

}
