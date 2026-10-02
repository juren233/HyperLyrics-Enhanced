/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.view.LyricLineAssembler
import com.juren233.hyperlyricsenhanced.lyric.view.METADATA_NEXT_LINE_PREVIEW
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.lyric.view.line.PositionUpdateConsumer
import com.juren233.hyperlyricsenhanced.lyric.view.line.PositionUpdateDemand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionUpdateDemandTest {
    private class Consumer(override var needsFrequentPositionUpdates: Boolean) : PositionUpdateConsumer

    private fun interval(source: RichLyricLine, demand: PositionUpdateDemand): Long =
        TimelineCadencePolicy.nextIntervalMs(
            positionMs = 2_500, currentLineWordSync = !source.words.isNullOrEmpty(),
            nextBoundaryMs = 5_000, nextLineWordSync = false, msSinceLineChange = 2_000,
            activeConsumerWordSync = demand.requiresFrequentUpdates(),
        )

    @Test fun `relative main progress stays fast after settle window despite empty source words`() {
        val source = RichLyricLine(begin = 0, end = 5_000, text = "Main lyric")
        val result = LyricLineAssembler(enableRelativeProgress = true).buildMain(source)
        assertTrue(source.words.isNullOrEmpty())
        assertFalse(result.line.words.isNullOrEmpty())
        val demand = PositionUpdateDemand()
        val consumer = Consumer(!result.line.words.isNullOrEmpty())
        demand.attach(consumer)
        assertEquals(33L, interval(source, demand))
    }

    @Test fun `secondary generated words retain fast updates when raw main has no words`() {
        val source = RichLyricLine(begin = 0, end = 5_000, text = "Main", translation = "你好世界")
        val result = LyricLineAssembler(
            displayMode = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION,
            secondaryTextUnitProgress = true,
        ).buildSecondary(source)
        assertTrue(source.words.isNullOrEmpty())
        assertTrue(result.line.words.orEmpty().size > 1)
        val demand = PositionUpdateDemand()
        val consumer = Consumer(!result.line.words.isNullOrEmpty())
        demand.attach(consumer)
        assertEquals(33L, interval(source, demand))
        consumer.needsFrequentPositionUpdates = false
        assertEquals(250L, interval(source, demand))
    }

    @Test fun `preview contributes no generated word demand`() {
        val source = RichLyricLine(
            begin = 0, end = 5_000, text = "Main", secondary = "Next line",
            metadata = lyricMetadataOf(METADATA_NEXT_LINE_PREVIEW to "true"),
        )
        val result = LyricLineAssembler(secondaryTextUnitProgress = true).buildSecondary(source)
        assertTrue(result.isNextLinePreview)
        val demand = PositionUpdateDemand()
        val consumer = Consumer(!result.line.words.isNullOrEmpty())
        demand.attach(consumer)
        assertEquals(250L, interval(source, demand))
    }

    @Test fun `demand follows current consumers across detach reattach and content changes`() {
        val demand = PositionUpdateDemand()
        val first = Consumer(true)
        val second = Consumer(true)
        demand.attach(first)
        demand.attach(first)
        demand.attach(second)
        demand.detach(first)
        assertTrue(demand.requiresFrequentUpdates())
        second.needsFrequentPositionUpdates = false
        assertFalse(demand.requiresFrequentUpdates())
        demand.attach(first)
        assertTrue(demand.requiresFrequentUpdates())
        demand.detach(first)
        demand.detach(second)
        assertFalse(demand.requiresFrequentUpdates())
    }
}
