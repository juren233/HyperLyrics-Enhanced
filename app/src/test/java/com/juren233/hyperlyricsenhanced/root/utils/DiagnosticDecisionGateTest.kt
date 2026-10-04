package com.juren233.hyperlyricsenhanced.root.utils

import org.junit.Assert.*
import org.junit.Test

class DiagnosticDecisionGateTest {
    @Test fun `repeat positions do not flood and latest changed outcome remains observable`() {
        val gate = DiagnosticDecisionGate(minIntervalMs = 1_000)
        assertTrue(gate.shouldLog("island", "shown", 0))
        repeat(10_000) { assertFalse(gate.shouldLog("island", "shown", it.toLong())) }
        assertFalse(gate.shouldLog("island", "no_view", 500))
        assertTrue(gate.shouldLog("island", "no_view", 1_000))
        assertFalse(gate.shouldLog("island", "no_view", 10_000))
    }

    @Test fun `key budget evicts old entries rather than retaining views forever`() {
        val gate = DiagnosticDecisionGate(minIntervalMs = 0, maxKeys = 2)
        assertTrue(gate.shouldLog("one", "shown", 0))
        assertTrue(gate.shouldLog("two", "shown", 0))
        assertTrue(gate.shouldLog("three", "shown", 0))
        assertTrue(gate.shouldLog("one", "shown", 0))
    }

    @Test fun `clear re-arms only the selected channel and children`() {
        val gate = DiagnosticDecisionGate()
        gate.shouldLog("ISLAND/view", "shown", 0)
        gate.shouldLog("BRIDGE", "applied", 0)
        gate.clear("ISLAND")
        assertTrue(gate.shouldLog("ISLAND/view", "shown", 1))
        assertFalse(gate.shouldLog("BRIDGE", "applied", 1))
        gate.clear()
        assertTrue(gate.shouldLog("BRIDGE", "applied", 2))
    }

    @Test fun `scheduled refresh does not suppress the asynchronous outcome`() {
        val gate = DiagnosticDecisionGate(minIntervalMs = 2_000)
        assertTrue(gate.shouldLog("ISLAND/refresh_pending", "scheduled", 0))
        assertTrue(gate.shouldLog("ISLAND/refresh", "injected_view_present", 1))
        assertFalse(gate.shouldLog("ISLAND/refresh", "injected_view_present", 2))
    }
}
