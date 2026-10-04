package com.juren233.hyperlyricsenhanced.root.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCallbackGateTest {
    @Test
    fun `stop makes already queued work inert`() {
        val gate = RuntimeCallbackGate()
        val generation = gate.begin()
        var writes = 0
        val queued = gate.guard(generation) { writes++ }
        gate.invalidate()
        queued.run()
        assertEquals(0, writes)
        assertFalse(gate.active)
    }

    @Test
    fun `starting replacement cannot reactivate old callbacks`() {
        val gate = RuntimeCallbackGate()
        val old = gate.begin()
        val writes = mutableListOf<String>()
        val stale = gate.guard(old) { writes += "old" }
        gate.invalidate()
        val replacement = gate.begin()
        stale.run()
        gate.guard(replacement) { writes += "replacement" }.run()
        assertEquals(listOf("replacement"), writes)
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(replacement))
    }

    @Test
    fun `unstarted and invalidated generations are never accepted`() {
        val gate = RuntimeCallbackGate()
        assertFalse(gate.isCurrent(null))
        val active = gate.begin()
        assertTrue(gate.active)
        assertTrue(gate.isCurrent(active))
        gate.invalidate()
        gate.invalidate()
        assertFalse(gate.isCurrent(active))
        assertFalse(gate.isCurrent(null))
    }
}
