/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.integration

import com.juren233.hyperlyricsenhanced.root.mediacard.compatibility.TraceInvariantAssertions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSystemUiMediaHostRound7Test {
    @Test
    fun `complete is scoped terminal and cannot be followed by cancel`() {
        val trace = DeterministicMediaCardTrace(ManualFrameClock())
        val host = FakeSystemUiMediaHost(
            trace = trace,
            classLoaderId = "loader-round7-complete",
            controllerId = TraceControllerId(71L),
            playerId = TraceControllerId(72L),
            sessionId = TraceSessionId(700L),
        )
        val token = host.begin(targetFullAod = true)
        assertTrue(host.update(token, 0.5f))
        assertTrue(host.complete(token))
        assertFalse(host.cancel(token))

        assertEquals(
            listOf(
                TraceEventType.NATIVE_BEGIN,
                TraceEventType.NATIVE_UPDATE,
                TraceEventType.NATIVE_COMPLETE,
                TraceEventType.NATIVE_CANCEL,
            ),
            trace.events.map { it.type },
        )
        assertEquals(1, trace.events.count { it.type == TraceEventType.NATIVE_COMPLETE && it.mutatesUi })
        assertEquals(0, trace.events.last().activeResourceCount)
        TraceInvariantAssertions.assertStaleCallbacksDoNotMutateUi(trace.events)
        TraceInvariantAssertions.assertTerminalResourcesReleased(trace.events)
    }

    @Test
    fun `cancel is scoped terminal and stale update cannot mutate the single root`() {
        val trace = DeterministicMediaCardTrace(ManualFrameClock())
        val host = FakeSystemUiMediaHost(
            trace = trace,
            classLoaderId = "loader-round7-cancel",
            controllerId = TraceControllerId(81L),
            playerId = TraceControllerId(82L),
            sessionId = TraceSessionId(800L),
        )
        val token = host.begin(targetFullAod = false)
        assertTrue(host.update(token, 0.25f))
        val stale = token.copy(epoch = token.epoch + 1L)
        assertFalse(host.update(stale, 0.75f))
        assertTrue(host.cancel(token))
        assertFalse(host.complete(token))

        assertEquals(1, trace.frames.size)
        assertEquals(1, trace.frames.single().visibleRootCount)
        assertEquals(0, trace.events.last().activeResourceCount)
        TraceInvariantAssertions.assertStaleCallbacksDoNotMutateUi(trace.events)
        TraceInvariantAssertions.assertAtMostOneVisibleRoot(trace.frames)
        TraceInvariantAssertions.assertFrameGeometry(trace.frames)
        TraceInvariantAssertions.assertTerminalResourcesReleased(trace.events)
    }
}
