/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleExoPlaybackIntentPolicyTest {
    @Test
    fun `buffering with retained play intent keeps surface and freezes timeline`() {
        val result = AppleExoPlaybackIntentPolicy.resolve(
            playWhenReady = true,
            state = AppleExoPlaybackIntentPolicy.STATE_BUFFERING,
        )

        assertEquals(AppleExoPlaybackIntentPolicy.Publication.BUFFERING, result.publication)
        assertTrue(result.playbackActive)
        assertFalse(result.advancesTimeline)
    }

    @Test
    fun `ready with play intent advances timeline`() {
        val result = AppleExoPlaybackIntentPolicy.resolve(
            playWhenReady = true,
            state = AppleExoPlaybackIntentPolicy.STATE_READY,
        )

        assertEquals(AppleExoPlaybackIntentPolicy.Publication.PLAYING, result.publication)
        assertTrue(result.playbackActive)
        assertTrue(result.advancesTimeline)
    }

    @Test
    fun `explicit pause always wins over engine state`() {
        listOf(
            AppleExoPlaybackIntentPolicy.STATE_IDLE,
            AppleExoPlaybackIntentPolicy.STATE_BUFFERING,
            AppleExoPlaybackIntentPolicy.STATE_READY,
        ).forEach { state ->
            val result = AppleExoPlaybackIntentPolicy.resolve(false, state)
            assertEquals(AppleExoPlaybackIntentPolicy.Publication.PAUSED, result.publication)
            assertFalse(result.playbackActive)
            assertFalse(result.advancesTimeline)
        }
    }

    @Test
    fun `prepare window retains play intent but does not advance`() {
        val result = AppleExoPlaybackIntentPolicy.resolve(
            playWhenReady = true,
            state = AppleExoPlaybackIntentPolicy.STATE_IDLE,
        )

        assertEquals(AppleExoPlaybackIntentPolicy.Publication.BUFFERING, result.publication)
        assertTrue(result.playbackActive)
        assertFalse(result.advancesTimeline)
    }

    @Test
    fun `ended state releases playback surface`() {
        val result = AppleExoPlaybackIntentPolicy.resolve(
            playWhenReady = true,
            state = AppleExoPlaybackIntentPolicy.STATE_ENDED,
        )

        assertEquals(AppleExoPlaybackIntentPolicy.Publication.PAUSED, result.publication)
        assertFalse(result.playbackActive)
        assertFalse(result.advancesTimeline)
    }

    @Test
    fun `platform paused state is rewritten while Exo retains playback surface`() {
        val buffering = AppleExoPlaybackIntentPolicy.resolve(
            playWhenReady = true,
            state = AppleExoPlaybackIntentPolicy.STATE_BUFFERING,
        )

        assertEquals(
            AppleExoPlaybackIntentPolicy.MediaSessionPauseDecision.REWRITE_BUFFERING,
            AppleExoPlaybackIntentPolicy.decideMediaSessionPause(
                incomingPaused = true,
                activeResolution = buffering,
            ),
        )
    }

    @Test
    fun `platform paused state remains paused after explicit Exo pause`() {
        val paused = AppleExoPlaybackIntentPolicy.resolve(
            playWhenReady = false,
            state = AppleExoPlaybackIntentPolicy.STATE_READY,
        )

        assertEquals(
            AppleExoPlaybackIntentPolicy.MediaSessionPauseDecision.KEEP,
            AppleExoPlaybackIntentPolicy.decideMediaSessionPause(
                incomingPaused = true,
                activeResolution = paused,
            ),
        )
        assertEquals(
            AppleExoPlaybackIntentPolicy.MediaSessionPauseDecision.KEEP,
            AppleExoPlaybackIntentPolicy.decideMediaSessionPause(
                incomingPaused = true,
                activeResolution = null,
            ),
        )
    }

    @Test
    fun `non paused platform state is never rewritten`() {
        val playing = AppleExoPlaybackIntentPolicy.resolve(
            playWhenReady = true,
            state = AppleExoPlaybackIntentPolicy.STATE_READY,
        )

        assertEquals(
            AppleExoPlaybackIntentPolicy.MediaSessionPauseDecision.KEEP,
            AppleExoPlaybackIntentPolicy.decideMediaSessionPause(
                incomingPaused = false,
                activeResolution = playing,
            ),
        )
    }
}
