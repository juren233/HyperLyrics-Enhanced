/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandWaveColorWatchdogPolicyTest {

    @Test
    fun `arms while feature enabled`() {
        assertTrue(IslandWaveColorWatchdogPolicy.shouldArm(featureEnabled = true, overrideApplied = false))
    }

    @Test
    fun `arms while override still dangles after feature off`() {
        assertTrue(IslandWaveColorWatchdogPolicy.shouldArm(featureEnabled = false, overrideApplied = true))
    }

    @Test
    fun `never arms when feature off and native colors restored`() {
        assertFalse(IslandWaveColorWatchdogPolicy.shouldArm(featureEnabled = false, overrideApplied = false))
    }

    @Test
    fun `keeps running inside window and stops at deadline`() {
        assertTrue(
            IslandWaveColorWatchdogPolicy.shouldKeepRunning(
                nowMs = 14_999L,
                deadlineMs = 15_000L,
            )
        )
        assertFalse(
            IslandWaveColorWatchdogPolicy.shouldKeepRunning(
                nowMs = 15_000L,
                deadlineMs = 15_000L,
            )
        )
        assertFalse(
            IslandWaveColorWatchdogPolicy.shouldKeepRunning(
                nowMs = 15_001L,
                deadlineMs = 15_000L,
            )
        )
    }
}
