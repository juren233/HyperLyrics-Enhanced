/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class OfficialProviderMediaSessionMetadataGateTest {
    private val gate = OfficialProviderMediaSessionMetadataGate()

    @Test
    fun `missing metadata remains retryable and later snapshot is delivered once`() {
        val session = Any()

        assertEquals(
            OfficialProviderMediaSessionMetadataGate.SnapshotDecision.MISSING_FIRST,
            gate.claimSnapshot(session, hasMetadata = false),
        )
        assertEquals(
            OfficialProviderMediaSessionMetadataGate.SnapshotDecision.MISSING_REPEATED,
            gate.claimSnapshot(session, hasMetadata = false),
        )
        assertEquals(
            OfficialProviderMediaSessionMetadataGate.SnapshotDecision.DELIVER,
            gate.claimSnapshot(session, hasMetadata = true),
        )
        assertEquals(
            OfficialProviderMediaSessionMetadataGate.SnapshotDecision.ALREADY_DELIVERED,
            gate.claimSnapshot(session, hasMetadata = true),
        )
    }

    @Test
    fun `real metadata callback suppresses playback snapshot`() {
        val session = Any()

        gate.recordExplicit(session)

        assertEquals(
            OfficialProviderMediaSessionMetadataGate.SnapshotDecision.ALREADY_DELIVERED,
            gate.claimSnapshot(session, hasMetadata = true),
        )
    }

    @Test
    fun `failed delivery can be released and retried`() {
        val session = Any()

        assertEquals(
            OfficialProviderMediaSessionMetadataGate.SnapshotDecision.DELIVER,
            gate.claimSnapshot(session, hasMetadata = true),
        )
        gate.release(session)

        assertEquals(
            OfficialProviderMediaSessionMetadataGate.SnapshotDecision.DELIVER,
            gate.claimSnapshot(session, hasMetadata = true),
        )
    }

    @Test
    fun `metadata delivery is tracked independently for each session`() {
        val first = Any()
        val second = Any()

        gate.recordExplicit(first)

        assertEquals(
            OfficialProviderMediaSessionMetadataGate.SnapshotDecision.ALREADY_DELIVERED,
            gate.claimSnapshot(first, hasMetadata = true),
        )
        assertEquals(
            OfficialProviderMediaSessionMetadataGate.SnapshotDecision.DELIVER,
            gate.claimSnapshot(second, hasMetadata = true),
        )
    }

    @Test
    fun `missing receiver cannot claim a snapshot`() {
        assertEquals(
            OfficialProviderMediaSessionMetadataGate.SnapshotDecision.NO_SESSION,
            gate.claimSnapshot(session = null, hasMetadata = true),
        )
    }
}
