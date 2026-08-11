/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStatePositionPolicyTest {
    @Test
    fun `playing advances from the authoritative anchor`() {
        assertEquals(
            1_500L,
            PlaybackStatePositionPolicy.positionAt(
                state = PlaybackState.STATE_PLAYING,
                basePosition = 1_000L,
                lastUpdateTime = 10_000L,
                playbackSpeed = 1.0f,
                now = 10_500L,
            ),
        )
    }

    @Test
    fun `buffering keeps the session active but freezes its position`() {
        assertTrue(PlaybackStatePositionPolicy.keepsSessionActive(PlaybackState.STATE_BUFFERING))
        assertFalse(PlaybackStatePositionPolicy.advancesTimeline(PlaybackState.STATE_BUFFERING))
        assertEquals(
            1_000L,
            PlaybackStatePositionPolicy.positionAt(
                state = PlaybackState.STATE_BUFFERING,
                basePosition = 1_000L,
                lastUpdateTime = 10_000L,
                playbackSpeed = 1.0f,
                now = 30_000L,
            ),
        )
    }

    @Test
    fun `paused is inactive and remains frozen`() {
        assertFalse(PlaybackStatePositionPolicy.keepsSessionActive(PlaybackState.STATE_PAUSED))
        assertEquals(
            2_000L,
            PlaybackStatePositionPolicy.positionAt(
                state = PlaybackState.STATE_PAUSED,
                basePosition = 2_000L,
                lastUpdateTime = 10_000L,
                playbackSpeed = 1.0f,
                now = 30_000L,
            ),
        )
    }
}
