/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineCadencePolicyTest {

    private val settled = TimelineCadencePolicy.SETTLE_WINDOW_MS + 100L

    @Test
    fun `word sync line keeps smooth cadence`() {
        assertEquals(
            TimelineCadencePolicy.WORD_SYNC_INTERVAL_MS,
            TimelineCadencePolicy.nextIntervalMs(
                positionMs = 1_000L,
                currentLineWordSync = true,
                nextBoundaryMs = 6_000L,
                nextLineWordSync = false,
                msSinceLineChange = settled,
            ),
        )
    }

    @Test
    fun `plain line right after line change stays in settle window`() {
        assertEquals(
            TimelineCadencePolicy.WORD_SYNC_INTERVAL_MS,
            TimelineCadencePolicy.nextIntervalMs(
                positionMs = 1_000L,
                currentLineWordSync = false,
                nextBoundaryMs = 6_000L,
                nextLineWordSync = false,
                msSinceLineChange = 500L,
            ),
        )
    }

    @Test
    fun `no recorded line change is treated as settled`() {
        assertEquals(
            TimelineCadencePolicy.MID_LINE_INTERVAL_MS,
            TimelineCadencePolicy.nextIntervalMs(
                positionMs = 1_000L,
                currentLineWordSync = false,
                nextBoundaryMs = 60_000L,
                nextLineWordSync = false,
                msSinceLineChange = null,
            ),
        )
    }

    @Test
    fun `plain line sleeps until boundary minus guard`() {
        assertEquals(
            170L,
            TimelineCadencePolicy.nextIntervalMs(
                positionMs = 1_000L,
                currentLineWordSync = false,
                nextBoundaryMs = 1_200L,
                nextLineWordSync = false,
                msSinceLineChange = settled,
            ),
        )
    }

    @Test
    fun `plain line far from boundary is capped at mid-line interval`() {
        assertEquals(
            TimelineCadencePolicy.MID_LINE_INTERVAL_MS,
            TimelineCadencePolicy.nextIntervalMs(
                positionMs = 1_000L,
                currentLineWordSync = false,
                nextBoundaryMs = 60_000L,
                nextLineWordSync = false,
                msSinceLineChange = settled,
            ),
        )
    }

    @Test
    fun `boundary closer than guard clamps to min interval`() {
        assertEquals(
            TimelineCadencePolicy.MIN_INTERVAL_MS,
            TimelineCadencePolicy.nextIntervalMs(
                positionMs = 1_000L,
                currentLineWordSync = false,
                nextBoundaryMs = 1_020L,
                nextLineWordSync = false,
                msSinceLineChange = settled,
            ),
        )
    }

    @Test
    fun `upcoming word sync line prearms fast cadence`() {
        assertEquals(
            TimelineCadencePolicy.WORD_SYNC_INTERVAL_MS,
            TimelineCadencePolicy.nextIntervalMs(
                positionMs = 1_000L,
                currentLineWordSync = false,
                nextBoundaryMs = 1_400L,
                nextLineWordSync = true,
                msSinceLineChange = settled,
            ),
        )
    }

    @Test
    fun `word sync line beyond prearm window still capped at mid-line interval`() {
        assertEquals(
            TimelineCadencePolicy.MID_LINE_INTERVAL_MS,
            TimelineCadencePolicy.nextIntervalMs(
                positionMs = 1_000L,
                currentLineWordSync = false,
                nextBoundaryMs = 2_000L,
                nextLineWordSync = true,
                msSinceLineChange = settled,
            ),
        )
    }

    @Test
    fun `no future boundary falls back to mid-line interval`() {
        assertEquals(
            TimelineCadencePolicy.MID_LINE_INTERVAL_MS,
            TimelineCadencePolicy.nextIntervalMs(
                positionMs = 1_000L,
                currentLineWordSync = false,
                nextBoundaryMs = null,
                nextLineWordSync = false,
                msSinceLineChange = settled,
            ),
        )
    }

    @Test
    fun `already passed boundary clamps to min interval`() {
        assertEquals(
            TimelineCadencePolicy.MIN_INTERVAL_MS,
            TimelineCadencePolicy.nextIntervalMs(
                positionMs = 2_000L,
                currentLineWordSync = false,
                nextBoundaryMs = 1_500L,
                nextLineWordSync = false,
                msSinceLineChange = settled,
            ),
        )
    }
}
