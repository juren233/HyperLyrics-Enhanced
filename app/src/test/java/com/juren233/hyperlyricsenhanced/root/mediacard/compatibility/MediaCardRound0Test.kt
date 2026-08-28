/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.compatibility

import com.juren233.hyperlyricsenhanced.root.mediacard.integration.DeterministicMediaCardTrace
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.ManualFrameClock
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.MediaLyricTraceSnapshot
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.TraceCallbackDisposition
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.TraceControllerId
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.TraceEventType
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.TraceListenerId
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.TraceRect
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.TraceSessionId
import com.juren233.hyperlyricsenhanced.root.mediacard.integration.TraceTransitionToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCardRound0Test {
    @Test
    fun `manual frame clock and event sequence are deterministic`() {
        val clock = ManualFrameClock()
        val trace = DeterministicMediaCardTrace(clock)
        val session = TraceSessionId(1L)
        val controller = TraceControllerId(2L)
        val listener = TraceListenerId(3L)
        val token = TraceTransitionToken(session, epoch = 7L, listenerId = listener, targetFullAod = true)

        trace.record(
            sessionId = session,
            controllerId = controller,
            listenerId = listener,
            token = token,
            type = TraceEventType.NATIVE_BEGIN,
        )
        val firstFrame = trace.recordFrame(
            sessionId = session,
            controllerId = controller,
            token = token,
            fraction = 0f,
            playerBounds = TraceRect(0, 0, 400, 500),
            lyricBounds = TraceRect(20, 120, 380, 220),
            progressBounds = TraceRect(20, 300, 380, 320),
            actionBounds = listOf(TraceRect(20, 350, 80, 410)),
            safeBottomInset = 20,
            visibleRootCount = 1,
            activeResourceCount = 1,
        )
        val secondEvent = trace.record(
            sessionId = session,
            controllerId = controller,
            listenerId = listener,
            token = token,
            type = TraceEventType.NATIVE_UPDATE,
            fraction = 0.5f,
            callbackDisposition = TraceCallbackDisposition.ACCEPTED,
            mutatesUi = true,
        )

        assertEquals(1L, trace.events.first().eventSequence)
        assertEquals(2L, secondEvent.eventSequence)
        assertEquals(1L, firstFrame.frameId)
        assertEquals(16_666_667L, firstFrame.timestampNanos)
        assertEquals(firstFrame.timestampNanos, secondEvent.timestampNanos)
        assertEquals(token, secondEvent.token)
    }

    @Test
    fun `same timestamp preserves callback ordering without using wall clock`() {
        val clock = ManualFrameClock()
        val trace = DeterministicMediaCardTrace(clock)
        val session = TraceSessionId(10L)
        val controller = TraceControllerId(20L)

        trace.record(session, controller, TraceEventType.FULL_AOD_STATE_CHANGED, detail = "false")
        trace.record(session, controller, TraceEventType.NATIVE_BEGIN, detail = "false")

        assertEquals(listOf(1L, 2L), trace.events.map { it.eventSequence })
        assertEquals(listOf(0L, 0L), trace.events.map { it.timestampNanos })
        assertEquals(
            listOf(TraceEventType.FULL_AOD_STATE_CHANGED, TraceEventType.NATIVE_BEGIN),
            trace.events.map { it.type },
        )
    }

    @Test
    fun `snapshot fixture keeps line identity independent from repeated text`() {
        val first = MediaLyricTraceSnapshot(
            sequence = 11L,
            songKey = "song",
            currentLineId = "line@1000",
            nextLineId = "line@2000",
            positionMs = 1_500L,
            playing = true,
        )
        val second = first.copy(
            sequence = 12L,
            currentLineId = "line@2000",
            nextLineId = "line@3000",
            positionMs = 2_100L,
        )

        assertEquals(first.songKey, second.songKey)
        assertNotEquals(first.sequence, second.sequence)
        assertNotEquals(first.currentLineId, second.currentLineId)
        assertTrue(second.positionMs > first.positionMs)
    }

    @Test
    fun `runtime profile accepts optional missing capability but rejects missing required capability`() {
        val profile = RuntimeProfileFixture(
            profileId = "os4.full-aod",
            classLoaderId = "loader-1",
            identifiers = listOf(
                RuntimeIdentifierFixture(
                    classDescriptor = "Lcom/android/systemui/statusbar/notification/fullaod/NotifiFullAodController;",
                    memberName = "onBegin",
                    memberDescriptor = "(Ljava/lang/Object;)V",
                    requirement = ContractRequirement.REQUIRED,
                ),
            ),
            capabilities = listOf(
                CapabilityFixture("full-aod", available = true, requirement = ContractRequirement.REQUIRED),
                CapabilityFixture("native-cancel", available = false, requirement = ContractRequirement.OPTIONAL),
            ),
        )

        RuntimeContractAssertions.assertValidProfile(profile)
        RuntimeContractAssertions.assertRequiredCapabilitiesAvailable(profile)
        RuntimeContractAssertions.assertOptionalCapabilityMayBeUnavailable(profile, "native-cancel")

        val missingRequired = profile.copy(
            capabilities = profile.capabilities.map {
                if (it.name == "full-aod") it.copy(available = false) else it
            },
        )
        assertThrows(AssertionError::class.java) {
            RuntimeContractAssertions.assertRequiredCapabilitiesAvailable(missingRequired)
        }
    }

    @Test
    fun `profile cache namespace is isolated by class loader`() {
        val base = RuntimeProfileFixture(
            profileId = "os4.full-aod",
            classLoaderId = "loader-a",
            identifiers = listOf(
                RuntimeIdentifierFixture("Lexample/Target;", "method", "()V", ContractRequirement.REQUIRED),
            ),
            capabilities = emptyList(),
        )

        assertNotEquals(
            base.cacheNamespace(),
            base.copy(classLoaderId = "loader-b").cacheNamespace(),
        )
    }
}
