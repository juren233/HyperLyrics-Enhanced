/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplePlaybackPositionStateTest {
    private fun resolution(position: Long?) = AppleCentralPositionPolicy.Resolution(
        position = position,
        mediaPosition = null,
        reason = AppleCentralPositionPolicy.Reason.CENTRAL_ACCEPTED,
    )

    @Test
    fun `begin song generation advances and clears position references but keeps direct id`() {
        val state = ApplePlaybackPositionState()
        state.setMediaReference(mediaReference(generation = 1))
        state.setDirectReference(directReference(generation = 1))
        state.setDirectSongId("a")
        state.lastAdjustedPosition = 1234L

        val generation = state.beginSongGeneration()

        assertEquals(1, generation)
        assertNull(state.mediaReference())
        assertNull(state.directReference())
        assertEquals(0L, state.lastAdjustedPosition)
        assertEquals(1, state.songGeneration())
        // 与原实现一致：切歌不重置直连歌曲身份，等待下一次 onDirectSongChanged 覆写。
        assertEquals("a", state.directSongId())
    }

    @Test
    fun `clearReferences keeps the generation for a stop that is not a track change`() {
        val state = ApplePlaybackPositionState()
        state.beginSongGeneration()
        state.setMediaReference(mediaReference(generation = 1))
        state.setDirectReference(directReference(generation = 1))
        state.setDirectSongId("a")

        state.clearReferences()

        assertNull(state.mediaReference())
        assertNull(state.directReference())
        assertNull(state.directSongId())
        // A stop does not invalidate the current song generation.
        assertEquals(1, state.songGeneration())
    }

    @Test
    fun `snapshot reads generation and references together`() {
        val state = ApplePlaybackPositionState()
        state.beginSongGeneration()
        val media = mediaReference(generation = 1)
        val direct = directReference(generation = 1)
        state.setMediaReference(media)
        state.setDirectReference(direct)
        state.lastAdjustedPosition = 42L

        val snapshot = state.snapshot()
        assertEquals(1, snapshot.songGeneration)
        assertSame(media, snapshot.mediaReference)
        assertSame(direct, snapshot.directReference)
        assertEquals(42L, snapshot.lastAdjustedPosition)
    }

    @Test
    fun `restorable position delegates to the policy and stores the result`() {
        val state = ApplePlaybackPositionState()
        state.lastAdjustedPosition = 500L
        // A null resolution keeps the previous position, exactly like the policy defines.
        val kept = state.applyRestorablePosition(resolution(null))
        assertEquals(500L, kept)
        assertEquals(500L, state.lastAdjustedPosition)

        val moved = state.applyRestorablePosition(resolution(900L))
        assertEquals(900L, moved)
        assertEquals(900L, state.lastAdjustedPosition)
    }

    @Test
    fun `media playback state and observed key are nullable and clearable`() {
        val state = ApplePlaybackPositionState()
        assertNull(state.mediaPlaybackState())
        assertNull(state.observedMediaKey())
        assertFalse(state.hasDirectSongId)

        state.setMediaPlaybackState(true)
        state.setObservedMediaKey("key")
        state.setDirectSongId("a")
        assertEquals(true, state.mediaPlaybackState())
        assertEquals("key", state.observedMediaKey())
        assertTrue(state.hasDirectSongId)

        state.setMediaPlaybackState(null)
        state.setObservedMediaKey(null)
        assertNull(state.mediaPlaybackState())
        assertNull(state.observedMediaKey())
    }

    private fun mediaReference(generation: Int) = AppleCentralPositionPolicy.MediaReference(
        songGeneration = generation,
        title = "t",
        artist = "a",
        duration = 1000L,
        position = 0L,
        isPlaying = true,
        playbackSpeed = 1f,
        observedAtMs = 0L,
    )

    private fun directReference(generation: Int) = AppleCentralPositionPolicy.DirectReference(
        songGeneration = generation,
        position = 0L,
        observedAtMs = 0L,
    )
}
