/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import android.view.ViewTreeObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsScrollPresentationStateTest {
    private class Listener : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean = true
    }

    private fun snapshot(firstPosition: Int, offset: Int = 0) =
        AppleLyricsScrollPresentationState.ScrollSnapshot(
            firstPosition = firstPosition,
            firstOffset = offset,
            activeAdapterPosition = null,
            activeAdapterOffset = null,
            playbackPositionMs = null,
        )

    @Test
    fun `snapshot is addressed by song and cleared on track change`() {
        val state = AppleLyricsScrollPresentationState()
        state.acceptSnapshot("a", snapshot(3))
        assertEquals(3, state.snapshotFor("a")?.firstPosition)
        assertNull(state.snapshotFor("b"))
        state.clearSnapshot()
        assertNull(state.snapshotFor("a"))
    }

    @Test
    fun `failed restore preserves the last good anchor until real scrolling returns`() {
        val state = AppleLyricsScrollPresentationState()
        state.acceptSnapshot("a", snapshot(7))
        state.markRestoreFailed("a")

        // Apple's transient top layout must not overwrite the preserved anchor.
        assertTrue(state.shouldPreserveTopSnapshot("a", 0))
        assertFalse(state.shouldPreserveTopSnapshot("a", 4))
        // A different song is not protected by this song's failed restore.
        assertFalse(state.shouldPreserveTopSnapshot("b", 0))

        state.clearPreservedTopSnapshotIfScrolled("a", 4)
        assertFalse(state.shouldPreserveTopSnapshot("a", 0))
    }

    @Test
    fun `top preservation needs an existing positive anchor`() {
        val state = AppleLyricsScrollPresentationState()
        state.markRestoreFailed("a")
        assertFalse(state.shouldPreserveTopSnapshot("a", 0))
        state.acceptSnapshot("a", snapshot(0))
        assertFalse(state.shouldPreserveTopSnapshot("a", 0))
    }

    @Test
    fun `pending restore tracks liveness and clears by listener identity`() {
        val state = AppleLyricsScrollPresentationState()
        val host = Any()
        val first = Listener()
        val second = Listener()

        state.setPendingRestore(host, first)
        assertTrue(state.isRestorePending())
        assertSame(first, state.takePendingRestore()?.listener)
        assertFalse(state.isRestorePending())
        assertNull(state.takePendingRestore())

        state.setPendingRestore(host, first)
        // A newer restore must not be cancelled by an older listener finishing.
        assertFalse(state.clearPendingRestoreIf(second))
        assertTrue(state.isRestorePending())
        assertTrue(state.clearPendingRestoreIf(first))
        assertFalse(state.isRestorePending())
    }

    @Test
    fun `a newer restore replaces the previously pending one`() {
        val state = AppleLyricsScrollPresentationState()
        val host = Any()
        val first = Listener()
        val second = Listener()

        state.setPendingRestore(host, first)
        state.setPendingRestore(host, second)
        // The older listener no longer owns the pending entry.
        assertFalse(state.clearPendingRestoreIf(first))
        assertTrue(state.isRestorePending())
        assertSame(second, state.takePendingRestore()?.listener)
    }

    @Test
    fun `tracked hosts are registered once per instance`() {
        val state = AppleLyricsScrollPresentationState()
        val host = Any()
        assertTrue(state.markTrackedIfNew(host))
        assertFalse(state.markTrackedIfNew(host))
        assertTrue(state.markTrackedIfNew(Any()))
    }

    @Test
    fun `presentation in flight survives until an explicit finish`() {
        val state = AppleLyricsScrollPresentationState()
        assertFalse(state.isPresentationInFlight())
        state.beginPresentation()
        state.beginPresentation()
        assertTrue(state.isPresentationInFlight())
        state.finishPresentation()
        assertFalse(state.isPresentationInFlight())
    }

    @Test
    fun `active line scheduling is de-duplicated and interval bounded`() {
        val posted = mutableListOf<Pair<Runnable, Long>>()
        var updates = 0
        val controller = AppleLyricsActiveLineUpdateController(
            post = { task, delayMs -> posted += task to delayMs },
            intervalMs = 500L,
            update = { updates++ },
        )

        controller.schedule()
        controller.schedule()
        assertEquals(1, posted.size)
        assertEquals(500L, posted.single().second)

        posted.single().first.run()
        assertEquals(1, updates)
        // A finished turn allows the next reschedule instead of wedging the loop.
        controller.schedule()
        assertEquals(2, posted.size)
    }

    @Test
    fun `stop invalidates only the applied row so a queued turn can still finish`() {
        val posted = mutableListOf<Pair<Runnable, Long>>()
        var updates = 0
        val controller = AppleLyricsActiveLineUpdateController(
            post = { task, delayMs -> posted += task to delayMs },
            intervalMs = 500L,
            update = { updates++ },
        )
        controller.schedule()
        controller.markApplied(4)
        assertEquals(4, controller.lastAppliedIndex())

        controller.stop()
        assertEquals(-1, controller.lastAppliedIndex())
        // The already queued callback is not cancelled by the original contract.
        posted.single().first.run()
        assertEquals(1, updates)
    }
}
