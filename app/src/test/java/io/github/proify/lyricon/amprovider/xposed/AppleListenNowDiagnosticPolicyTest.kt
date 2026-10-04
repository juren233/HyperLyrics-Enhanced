/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleListenNowDiagnosticPolicyTest {
    @Test
    fun `rebuilding a full feed does not churn diagnostic identities`() {
        val gate = createListenNowDiagnosticGate()
        // The reported feed had 650 distinct keys across the three noisy events.
        repeat(650) { index ->
            assertTrue(gate.shouldLog("event${index % 3}/card$index", "stable", 0))
        }
        repeat(10) { pass ->
            repeat(650) { index ->
                assertFalse(gate.shouldLog(
                    "event${index % 3}/card$index", "stable", (pass + 1) * 10_000L,
                ))
            }
        }
    }

    @Test
    fun `changed state survives the throttle on a later callback`() {
        val gate = createListenNowDiagnosticGate()
        assertTrue(gate.shouldLog("primed/card", "cache_miss", 0))
        assertFalse(gate.shouldLog("primed/card", "cache_hit", 1_000))
        assertTrue(gate.shouldLog("primed/card", "cache_hit", 5_000))
        assertFalse(gate.shouldLog("primed/card", "cache_hit", 60_000))
    }

    @Test
    fun `feed diagnostic identities stay bounded`() {
        val gate = createListenNowDiagnosticGate()
        repeat(1_024) { index ->
            assertTrue(gate.shouldLog("card$index", "stable", 0))
        }
        assertFalse(gate.shouldLog("card0", "stable", 10_000))
        assertTrue(gate.shouldLog("overflow", "stable", 10_000))
        assertTrue(gate.shouldLog("card0", "stable", 10_000))
    }
}
