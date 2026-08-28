/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.integration

import com.juren233.hyperlyricsenhanced.root.mediacard.compatibility.TraceInvariantAssertions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSystemUiMediaHostTest {
    @Test
    fun `replayed native sequence preserves one root and releases resources`() {
        val trace = DeterministicMediaCardTrace(ManualFrameClock())
        val host = FakeSystemUiMediaHost(
            trace = trace,
            classLoaderId = "systemui-loader-1",
            controllerId = TraceControllerId(11L),
            playerId = TraceControllerId(12L),
            sessionId = TraceSessionId(100L),
        )

        host.bind(snapshotSequence = 7L)
        val token = host.begin(targetFullAod = true, snapshotSequence = 7L)
        host.update(token, 0f)
        host.update(token, 0.5f)
        host.update(token, 1f)
        assertTrue(host.complete(token))

        val replayedTypes = buildList {
            trace.replay { add(it.type) }
        }
        assertEquals(
            listOf(
                TraceEventType.MEDIA_BOUND,
                TraceEventType.NATIVE_BEGIN,
                TraceEventType.NATIVE_UPDATE,
                TraceEventType.NATIVE_UPDATE,
                TraceEventType.NATIVE_UPDATE,
                TraceEventType.NATIVE_COMPLETE,
            ),
            replayedTypes,
        )
        TraceInvariantAssertions.assertEventSequenceMonotonic(trace.events)
        TraceInvariantAssertions.assertSnapshotSequenceMonotonic(trace.events)
        TraceInvariantAssertions.assertAtMostOneVisibleRoot(trace.frames)
        TraceInvariantAssertions.assertFrameGeometry(trace.frames)
        TraceInvariantAssertions.assertTerminalResourcesReleased(trace.events)
        assertTrue(trace.events.all { it.classLoaderId == "systemui-loader-1" })
    }

    @Test
    fun `cancel is not completion and stale token cannot mutate a frame`() {
        val trace = DeterministicMediaCardTrace(ManualFrameClock())
        val host = FakeSystemUiMediaHost(
            trace = trace,
            classLoaderId = "systemui-loader-2",
            controllerId = TraceControllerId(21L),
            playerId = TraceControllerId(22L),
            sessionId = TraceSessionId(200L),
        )
        val token = host.begin(targetFullAod = false)
        assertTrue(host.update(token, 0.25f))
        val stale = token.copy(epoch = token.epoch + 1L)
        assertFalse(host.update(stale, 0.5f))
        assertTrue(host.cancel(token))

        val staleEvents = trace.events.filter {
            it.callbackDisposition == TraceCallbackDisposition.STALE_REJECTED
        }
        assertEquals(1, staleEvents.size)
        assertFalse(staleEvents.single().mutatesUi)
        assertTrue(trace.events.none { it.type == TraceEventType.NATIVE_COMPLETE })
        TraceInvariantAssertions.assertStaleCallbacksDoNotMutateUi(trace.events)
        TraceInvariantAssertions.assertTerminalResourcesReleased(trace.events)
    }

    @Test
    fun `systemui reload creates new listener and rejects the old token`() {
        val trace = DeterministicMediaCardTrace(ManualFrameClock())
        val host = FakeSystemUiMediaHost(
            trace = trace,
            classLoaderId = "systemui-loader-before",
            controllerId = TraceControllerId(31L),
            playerId = TraceControllerId(32L),
            sessionId = TraceSessionId(300L),
        )
        val oldToken = host.begin(targetFullAod = true)
        host.update(oldToken, 0.4f)
        host.reload()
        assertFalse(host.update(oldToken, 0.8f))
        val newToken = host.begin(targetFullAod = true)

        assertNotEquals(oldToken.listenerId, newToken.listenerId)
        assertNotEquals(oldToken.sessionId, newToken.sessionId)
        assertNotEquals(oldToken.epoch, newToken.epoch)
        val rejected = trace.events.last { it.callbackDisposition == TraceCallbackDisposition.STALE_REJECTED }
        assertFalse(rejected.mutatesUi)
        assertEquals("systemui-loader-before", rejected.classLoaderId)
    }

    @Test
    fun `screen events during transition record desired state without hiding the root`() {
        val trace = DeterministicMediaCardTrace(ManualFrameClock())
        val host = FakeSystemUiMediaHost(
            trace = trace,
            classLoaderId = "systemui-loader-screen",
            controllerId = TraceControllerId(41L),
            playerId = TraceControllerId(42L),
            sessionId = TraceSessionId(400L),
        )
        val token = host.begin(targetFullAod = true)
        host.screenInteractiveChanged(true)
        host.update(token, 0.6f)
        host.screenInteractiveChanged(false)

        val screenEvents = trace.events.filter { it.type == TraceEventType.SCREEN_INTERACTIVE_CHANGED }
        assertEquals(2, screenEvents.size)
        assertTrue(screenEvents.all { !it.mutatesUi })
        assertTrue(screenEvents.all { it.visibleRootCount == 1 })
        assertTrue(screenEvents.all { it.activeResourceCount == 1 })
        assertEquals(1, trace.frames.last().visibleRootCount)
    }

    @Test
    fun `two players keep independent controller listener and session identity`() {
        val trace = DeterministicMediaCardTrace(ManualFrameClock())
        val first = FakeSystemUiMediaHost(
            trace = trace,
            classLoaderId = "loader-a",
            controllerId = TraceControllerId(51L),
            playerId = TraceControllerId(52L),
            sessionId = TraceSessionId(500L),
        )
        val second = FakeSystemUiMediaHost(
            trace = trace,
            classLoaderId = "loader-b",
            controllerId = TraceControllerId(61L),
            playerId = TraceControllerId(62L),
            sessionId = TraceSessionId(600L),
        )
        val firstToken = first.begin(targetFullAod = true)
        val secondToken = second.begin(targetFullAod = true)
        assertTrue(first.update(firstToken, 0.5f))
        assertTrue(second.update(secondToken, 0.5f))
        assertTrue(first.complete(firstToken))
        assertTrue(second.update(secondToken, 0.75f))

        assertNotEquals(firstToken.sessionId, secondToken.sessionId)
        assertNotEquals(firstToken.listenerId, secondToken.listenerId)
        assertEquals(2, trace.frames.count { it.fraction == 0.5f })
        assertEquals(
            setOf(TraceControllerId(52L), TraceControllerId(62L)),
            trace.frames.filter { it.fraction == 0.5f }.mapNotNull { it.playerId }.toSet(),
        )
        assertEquals(1, trace.events.count { it.type == TraceEventType.NATIVE_COMPLETE })
        assertEquals(1, trace.events.count { it.activeResourceCount == 0 })
    }
}
