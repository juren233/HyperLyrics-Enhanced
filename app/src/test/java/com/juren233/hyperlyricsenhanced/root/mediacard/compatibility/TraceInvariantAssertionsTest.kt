/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.compatibility

import com.juren233.hyperlyricsenhanced.root.mediacard.integration.DeterministicMediaCardTrace
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.ManualFrameClock
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.TraceCallbackDisposition
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.TraceControllerId
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.TraceEventType
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.TraceRect
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.TraceSessionId
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.TraceTransitionToken
import org.junit.Assert.assertThrows
import org.junit.Test

class TraceInvariantAssertionsTest {

    @Test
    fun `event and snapshot sequence assertions accept ordered trace`() {
        val clock = ManualFrameClock()
        val trace = DeterministicMediaCardTrace(clock)
        val session = TraceSessionId(1L)
        val controller = TraceControllerId(2L)

        trace.record(
            sessionId = session,
            controllerId = controller,
            type = TraceEventType.MEDIA_BOUND,
            snapshotSequence = 10L,
        )
        clock.advanceBy(1_000L)
        trace.record(
            sessionId = session,
            controllerId = controller,
            type = TraceEventType.SNAPSHOT_UPDATED,
            snapshotSequence = 11L,
        )
        trace.record(
            sessionId = session,
            controllerId = controller,
            type = TraceEventType.NATIVE_COMPLETE,
            activeResourceCount = 0,
        )

        TraceInvariantAssertions.assertEventSequenceMonotonic(trace.events)
        TraceInvariantAssertions.assertSnapshotSequenceMonotonic(trace.events)
        TraceInvariantAssertions.assertTerminalResourcesReleased(trace.events)
    }
    @Test
    fun `invariant assertions accept a valid single-root frame sequence`() {
        val clock = ManualFrameClock()
        val trace = DeterministicMediaCardTrace(clock)
        val session = TraceSessionId(1L)
        val controller = TraceControllerId(2L)
        val player = TraceRect(0, 0, 400, 500)

        trace.recordFrame(
            sessionId = session,
            controllerId = controller,
            token = null,
            fraction = 0f,
            playerBounds = player,
            lyricBounds = TraceRect(20, 100, 380, 180),
            progressBounds = TraceRect(20, 250, 380, 270),
            actionBounds = listOf(TraceRect(20, 300, 80, 360)),
            safeBottomInset = 20,
            visibleRootCount = 1,
            activeResourceCount = 1,
        )
        trace.recordFrame(
            sessionId = session,
            controllerId = controller,
            token = null,
            fraction = 1f,
            playerBounds = player,
            lyricBounds = TraceRect(20, 100, 380, 180),
            progressBounds = TraceRect(20, 250, 380, 270),
            actionBounds = listOf(TraceRect(20, 300, 80, 360)),
            safeBottomInset = 20,
            visibleRootCount = 1,
            activeResourceCount = 0,
        )

        TraceInvariantAssertions.assertAtMostOneVisibleRoot(trace.frames)
        TraceInvariantAssertions.assertFrameGeometry(trace.frames)
    }

    @Test
    fun `stale callback and terminal cleanup assertions reject unsafe traces`() {
        val clock = ManualFrameClock()
        val trace = DeterministicMediaCardTrace(clock)
        val session = TraceSessionId(1L)
        val controller = TraceControllerId(2L)
        val token = TraceTransitionToken(
            sessionId = session,
            epoch = 1L,
            listenerId = com.juren233.hyperlyricsenhanced.root.mediacard.integration.TraceListenerId(3L),
            targetFullAod = true,
        )

        trace.record(
            sessionId = session,
            controllerId = controller,
            type = TraceEventType.NATIVE_UPDATE,
            token = token,
            callbackDisposition = TraceCallbackDisposition.STALE_REJECTED,
            mutatesUi = true,
        )
        trace.record(
            sessionId = session,
            controllerId = controller,
            type = TraceEventType.NATIVE_CANCEL,
            token = token,
            activeResourceCount = 1,
        )

        assertThrows(AssertionError::class.java) {
            TraceInvariantAssertions.assertStaleCallbacksDoNotMutateUi(trace.events)
        }
        assertThrows(AssertionError::class.java) {
            TraceInvariantAssertions.assertTerminalResourcesReleased(trace.events)
        }
    }

    @Test
    fun `geometry assertion rejects double roots and overlap`() {
        val clock = ManualFrameClock()
        val trace = DeterministicMediaCardTrace(clock)
        val session = TraceSessionId(1L)
        val controller = TraceControllerId(2L)
        val player = TraceRect(0, 0, 400, 500)

        trace.recordFrame(
            sessionId = session,
            controllerId = controller,
            token = null,
            fraction = 0.5f,
            playerBounds = player,
            lyricBounds = TraceRect(20, 100, 380, 260),
            progressBounds = TraceRect(20, 240, 380, 280),
            actionBounds = listOf(TraceRect(20, 270, 80, 330)),
            safeBottomInset = 20,
            visibleRootCount = 2,
            activeResourceCount = 1,
        )

        assertThrows(AssertionError::class.java) {
            TraceInvariantAssertions.assertAtMostOneVisibleRoot(trace.frames)
        }
        assertThrows(AssertionError::class.java) {
            TraceInvariantAssertions.assertFrameGeometry(trace.frames)
        }
    }
}
