/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionGateRecoveryTrackerTest {

    @Test
    fun `same player is only stopped once while blocked`() {
        val tracker = MediaSessionGateRecoveryTracker()

        assertTrue(tracker.shouldStop("netease"))
        assertFalse(tracker.shouldStop("netease"))
    }

    @Test
    fun `recovery without any stop does not replay`() {
        val tracker = MediaSessionGateRecoveryTracker()

        assertFalse(tracker.shouldReplayAfterRecovery("netease"))
    }

    @Test
    fun `recovery of the stopped player replays exactly once`() {
        val tracker = MediaSessionGateRecoveryTracker()
        assertTrue(tracker.shouldStop("netease"))

        assertTrue(tracker.shouldReplayAfterRecovery("netease"))
        assertFalse(tracker.shouldReplayAfterRecovery("netease"))
    }

    @Test
    fun `a blocked player can be stopped again after its recovery`() {
        val tracker = MediaSessionGateRecoveryTracker()
        assertTrue(tracker.shouldStop("netease"))
        assertTrue(tracker.shouldReplayAfterRecovery("netease"))

        assertTrue(tracker.shouldStop("netease"))
    }

    @Test
    fun `switching player while blocked stops the new player separately`() {
        val tracker = MediaSessionGateRecoveryTracker()
        assertTrue(tracker.shouldStop("netease"))

        assertTrue(tracker.shouldStop("kugou"))
        assertFalse(tracker.shouldStop("kugou"))
    }

    @Test
    fun `recovery at a never-stopped player clears the record without replay`() {
        val tracker = MediaSessionGateRecoveryTracker()
        assertTrue(tracker.shouldStop("netease"))

        // The active player switched to an allowed player: its own switch path delivers
        // the snapshot, so the zombie record is just dropped.
        assertFalse(tracker.shouldReplayAfterRecovery("kugou"))
        assertTrue(tracker.shouldStop("kugou"))
    }

    @Test
    fun `recovery of the latest stopped player replays after an earlier player stop`() {
        val tracker = MediaSessionGateRecoveryTracker()
        assertTrue(tracker.shouldStop("netease"))
        assertTrue(tracker.shouldStop("kugou"))

        // Both players had their one-shot events dropped while blocked; the latest one
        // must still be replayed when its session recovers.
        assertTrue(tracker.shouldReplayAfterRecovery("kugou"))
    }
}
