/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandPauseTransitionGuardTest {
    @Test
    fun `resume during grace period cancels native restore`() {
        val guard = IslandPauseTransitionGuard()

        guard.onPlaybackStateChanged(isPlaying = false, pauseBehavior = 0)
        guard.onPlaybackStateChanged(isPlaying = true, pauseBehavior = 0)

        assertFalse(guard.nativeRestorePending)
        assertFalse(guard.consumeNativeRestore(playbackActive = true))
    }

    @Test
    fun `pause beyond grace period restores native island`() {
        val guard = IslandPauseTransitionGuard()

        guard.onPlaybackStateChanged(isPlaying = false, pauseBehavior = 0)

        assertTrue(guard.nativeRestorePending)
        assertTrue(guard.consumeNativeRestore(playbackActive = false))
        assertFalse(guard.nativeRestorePending)
        assertEquals(
            IslandPauseTransitionGuard.Transition.NATIVE_RESTORE_ALREADY_COMMITTED,
            guard.onPlaybackStateChanged(isPlaying = false, pauseBehavior = 0),
        )
        assertFalse(guard.nativeRestorePending)
    }

    @Test
    fun `keep lyrics behavior never schedules native restore`() {
        val guard = IslandPauseTransitionGuard()

        guard.onPlaybackStateChanged(isPlaying = false, pauseBehavior = 1)

        assertFalse(guard.nativeRestorePending)
        assertFalse(guard.consumeNativeRestore(playbackActive = false))
    }
}
