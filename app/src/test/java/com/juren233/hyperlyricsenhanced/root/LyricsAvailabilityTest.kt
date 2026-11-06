package com.juren233.hyperlyricsenhanced.root

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsAvailabilityTest {
    private val probes = mutableListOf<() -> Boolean>()
    private var notifications = 0
    private val listener: () -> Unit = { notifications++ }

    private fun probe(pending: () -> Boolean): () -> Boolean = pending.also {
        probes += it
        LyricsAvailability.addThirdPartyProbe(it)
    }

    @After
    fun tearDown() {
        probes.forEach(LyricsAvailability::removeThirdPartyProbe)
        LyricsAvailability.removeListener(listener)
        LyricsAvailability.reset()
    }

    @Test
    fun `first party report without third party is final`() {
        LyricsAvailability.reset()
        LyricsAvailability.addListener(listener)
        LyricsAvailability.onFirstPartyReported()
        assertTrue(LyricsAvailability.finalUnavailable)
        assertTrue(notifications == 1)
    }

    @Test
    fun `waits for a pending third party lookup and settles afterwards`() {
        LyricsAvailability.reset()
        var pending = true
        probe { pending }
        LyricsAvailability.onFirstPartyReported()
        assertFalse(LyricsAvailability.finalUnavailable)
        pending = false
        LyricsAvailability.onThirdPartySettled()
        assertTrue(LyricsAvailability.finalUnavailable)
    }

    @Test
    fun `third party settling alone never makes it final`() {
        LyricsAvailability.reset()
        LyricsAvailability.onThirdPartySettled()
        assertFalse(LyricsAvailability.finalUnavailable)
    }

    @Test
    fun `final state is latched until reset and reset notifies`() {
        LyricsAvailability.reset()
        LyricsAvailability.addListener(listener)
        var pending = false
        probe { pending }
        LyricsAvailability.onFirstPartyReported()
        pending = true // a later retry must not flip the island back
        LyricsAvailability.onThirdPartySettled()
        assertTrue(LyricsAvailability.finalUnavailable)
        LyricsAvailability.reset()
        assertFalse(LyricsAvailability.finalUnavailable)
        assertTrue(notifications == 2)
    }

    @Test
    fun `reset before a report clears a stale report`() {
        LyricsAvailability.reset()
        LyricsAvailability.onFirstPartyReported()
        LyricsAvailability.reset()
        LyricsAvailability.onThirdPartySettled()
        assertFalse(LyricsAvailability.finalUnavailable)
    }
}
