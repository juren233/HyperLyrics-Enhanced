/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class AodLyricPollCadencePolicyTest {

    @Test
    fun `no lyrics keeps preview refresh cadence`() {
        assertEquals(
            AodLyricPollCadencePolicy.NO_LYRIC_INTERVAL_MS,
            AodLyricPollCadencePolicy.nextIntervalMs(
                positionMs = 1_000L,
                nextBoundaryMs = 1_200L,
                hasLyrics = false,
            ),
        )
    }

    @Test
    fun `no future boundary with lyrics falls back to max interval`() {
        assertEquals(
            AodLyricPollCadencePolicy.MAX_INTERVAL_MS,
            AodLyricPollCadencePolicy.nextIntervalMs(
                positionMs = 1_000L,
                nextBoundaryMs = null,
                hasLyrics = true,
            ),
        )
    }

    @Test
    fun `sleeps until boundary minus guard`() {
        assertEquals(
            250L,
            AodLyricPollCadencePolicy.nextIntervalMs(
                positionMs = 1_000L,
                nextBoundaryMs = 1_300L,
                hasLyrics = true,
            ),
        )
    }

    @Test
    fun `far boundary is capped at max interval`() {
        assertEquals(
            AodLyricPollCadencePolicy.MAX_INTERVAL_MS,
            AodLyricPollCadencePolicy.nextIntervalMs(
                positionMs = 1_000L,
                nextBoundaryMs = 120_000L,
                hasLyrics = true,
            ),
        )
    }

    @Test
    fun `boundary inside guard clamps to min interval`() {
        assertEquals(
            AodLyricPollCadencePolicy.MIN_INTERVAL_MS,
            AodLyricPollCadencePolicy.nextIntervalMs(
                positionMs = 1_000L,
                nextBoundaryMs = 1_030L,
                hasLyrics = true,
            ),
        )
    }
}
