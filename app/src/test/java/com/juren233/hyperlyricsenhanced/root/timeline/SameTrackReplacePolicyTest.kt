/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameTrackReplacePolicyTest {

    private fun defer(
        streaming: Boolean = false,
        sameTrack: Boolean = true,
        playing: Boolean = true,
        displayedHasLyrics: Boolean = true,
        lineShowing: Boolean = true,
    ) = SameTrackReplacePolicy.shouldDefer(streaming, sameTrack, playing, displayedHasLyrics, lineShowing)

    @Test
    fun `same track replacement mid line while playing waits for the next line`() {
        assertTrue(defer())
    }

    @Test
    fun `new track applies immediately`() {
        assertFalse(defer(sameTrack = false))
    }

    @Test
    fun `paused replacement applies immediately`() {
        assertFalse(defer(playing = false))
    }

    @Test
    fun `replacement over title fallback or empty display applies immediately`() {
        assertFalse(defer(displayedHasLyrics = false))
        assertFalse(defer(lineShowing = false))
    }

    @Test
    fun `streaming line content is never deferred`() {
        assertFalse(defer(streaming = true))
    }
}
