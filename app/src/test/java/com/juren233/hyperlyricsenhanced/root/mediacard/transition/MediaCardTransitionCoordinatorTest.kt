/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.transition

import com.juren233.hyperlyricsenhanced.root.mediacard.MediaCardFullAodTransitionMode
import com.juren233.hyperlyricsenhanced.root.mediacard.host.NativeHeightLease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCardTransitionCoordinatorTest {
    @Test
    fun `stale token and rewound native fraction never mutate session`() {
        val coordinator = MediaCardTransitionCoordinator(
            MediaCardControllerIdentity(controllerIdentity = 10, playerIdentity = 20),
        )
        coordinator.attach(1L)
        val first = coordinator.begin(
            listener = Any(),
            targetFullAod = true,
            mode = MediaCardFullAodTransitionMode.DEFAULT,
            snapshotSequence = 1L,
        ).token
        assertNotNull(first)
        assertTrue(coordinator.update(first, 0.5f).accepted)
        assertFalse(coordinator.update(first, 0.4f).accepted)
        assertEquals(0.5f, coordinator.lastFraction(), 0.0001f)

        val second = coordinator.begin(
            listener = Any(),
            targetFullAod = false,
            mode = MediaCardFullAodTransitionMode.DEFAULT,
            snapshotSequence = 2L,
        ).token
        assertNotNull(second)
        assertFalse(coordinator.update(first, 0.8f).accepted)
        assertTrue(coordinator.update(second, 0.1f).accepted)
        assertEquals(MediaCardSessionState.TRANSITIONING_TO_NOTIFICATION, coordinator.state)
    }

    @Test
    fun `identity mismatch is rejected even when token fields look similar`() {
        val coordinator = MediaCardTransitionCoordinator(
            MediaCardControllerIdentity(controllerIdentity = 1, playerIdentity = 2),
        )
        coordinator.attach(1L)
        val token = coordinator.begin(
            listener = Any(),
            targetFullAod = true,
            mode = MediaCardFullAodTransitionMode.DEFAULT,
            snapshotSequence = 1L,
        ).token!!
        val forged = token.copy(
            identity = MediaCardControllerIdentity(controllerIdentity = 999, playerIdentity = 2),
        )
        val result = coordinator.update(forged, 0.5f)
        assertFalse(result.accepted)
        assertEquals("stale_token", result.reason)
    }

    @Test
    fun `complete and cancel release height lease and are idempotent`() {
        val closeCount = intArrayOf(0)
        val coordinator = MediaCardTransitionCoordinator(
            MediaCardControllerIdentity(3, 4),
        )
        coordinator.attach(4L)
        coordinator.attachHeightLease(TestLease { closeCount[0]++ })
        val token = coordinator.begin(
            listener = Any(),
            targetFullAod = true,
            mode = MediaCardFullAodTransitionMode.PAUSED_KEEP_LYRICS,
            snapshotSequence = 4L,
        ).token!!
        val complete = coordinator.complete(token)
        assertTrue(complete.accepted)
        assertTrue(complete.releaseHeightLease)
        assertEquals(1, closeCount[0])
        assertFalse(coordinator.complete(token).accepted)
        assertFalse(coordinator.cancel(token).accepted)

        coordinator.attachHeightLease(TestLease { closeCount[0]++ })
        val cancelToken = coordinator.begin(
            listener = Any(),
            targetFullAod = false,
            mode = MediaCardFullAodTransitionMode.DEFAULT,
            snapshotSequence = 5L,
        ).token!!
        assertTrue(coordinator.cancel(cancelToken).accepted)
        assertEquals(2, closeCount[0])
        assertEquals(MediaCardSessionState.STABLE_FULL_AOD, coordinator.state)
    }

    @Test
    fun `detach and recover invalidate old callbacks`() {
        val coordinator = MediaCardTransitionCoordinator(
            MediaCardControllerIdentity(7, 8),
        )
        coordinator.attach(10L)
        val token = coordinator.begin(
            listener = Any(),
            targetFullAod = true,
            mode = MediaCardFullAodTransitionMode.DEFAULT,
            snapshotSequence = 10L,
        ).token!!
        assertTrue(coordinator.detach().accepted)
        assertFalse(coordinator.update(token, 1f).accepted)
        assertEquals(MediaCardSessionState.DETACHED, coordinator.state)
        assertTrue(coordinator.recover(11L, stableFullAod = false).accepted)
        assertEquals(MediaCardSessionState.STABLE_NOTIFICATION, coordinator.state)
    }

    private class TestLease(private val onClose: () -> Unit) : NativeHeightLease {
        override val classLoader: ClassLoader = TestLease::class.java.classLoader!!
        override val originalHeights: IntArray = intArrayOf(1)
        private var closed = false

        override fun setTargetHeight(index: Int, height: Int): Boolean = !closed && index == 0

        override fun restore(): Boolean {
            if (closed) return true
            closed = true
            onClose()
            return true
        }
    }
}
