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
    @Test
    fun `new track anchor replaces old cursor while ticker is stopped`() {
        assertEquals(4L, PlaybackStatePositionPolicy.positionAt(
            PlaybackState.STATE_PLAYING, 0L, 47_516_983L, 1f, 47_516_987L,
        ))
        assertEquals(356L, PlaybackStatePositionPolicy.positionAt(
            PlaybackState.STATE_PLAYING, 0L, 47_516_983L, 1f, 47_517_339L,
        ))
    }

    @Test
    fun `seek and paused anchors use the received position without ticking`() {
        assertEquals(90_200L, PlaybackStatePositionPolicy.positionAt(
            PlaybackState.STATE_PLAYING, 90_000L, 10_000L, 2f, 10_100L,
        ))
        assertEquals(90_000L, PlaybackStatePositionPolicy.positionAt(
            PlaybackState.STATE_PAUSED, 90_000L, 10_000L, 1f, 20_000L,
        ))
    }

}
