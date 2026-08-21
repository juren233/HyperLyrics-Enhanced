/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider

import android.media.session.PlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateActivityPolicyTest {
    @Test
    fun `playing and buffering keep the lyric surface active`() {
        assertTrue(PlaybackStateActivityPolicy.keepsSessionActive(PlaybackState.STATE_PLAYING))
        assertTrue(PlaybackStateActivityPolicy.keepsSessionActive(PlaybackState.STATE_BUFFERING))
    }

    @Test
    fun `paused stopped and missing states are inactive`() {
        assertFalse(PlaybackStateActivityPolicy.keepsSessionActive(PlaybackState.STATE_PAUSED))
        assertFalse(PlaybackStateActivityPolicy.keepsSessionActive(PlaybackState.STATE_STOPPED))
        assertFalse(PlaybackStateActivityPolicy.keepsSessionActive(null))
    }
}
