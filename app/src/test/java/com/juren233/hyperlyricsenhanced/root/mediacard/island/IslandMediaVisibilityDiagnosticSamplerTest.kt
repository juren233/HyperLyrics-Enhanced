/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.island

import org.junit.Assert.*
import org.junit.Test

class IslandMediaVisibilityDiagnosticSamplerTest {
    @Test fun `first draw proves the installed callback is hit even while hidden`() {
        val sampler = IslandMediaVisibilityDiagnosticSampler()
        assertEquals("visibility_guard_first_predraw", sampler.record(0, false, false))
        assertNull(sampler.record(10_000, false, false))
        assertEquals(2L, sampler.draws)
    }

    @Test fun `first correction bypasses periodic limit and later corrections aggregate`() {
        val sampler = IslandMediaVisibilityDiagnosticSampler()
        sampler.record(0, true, false)
        assertEquals("visibility_guard_first_correction", sampler.record(1, true, true))
        repeat(100) { assertNull(sampler.record(it + 2L, true, true)) }
        assertEquals(101L, sampler.corrections)
        assertEquals("visibility_guard_status", sampler.record(5_001, true, false))
    }

    @Test fun `visible steady frames produce at most one snapshot per five seconds`() {
        val sampler = IslandMediaVisibilityDiagnosticSampler()
        sampler.record(0, true, false)
        for (now in 1L until 5_000L) assertNull(sampler.record(now, true, false))
        assertEquals("visibility_guard_status", sampler.record(5_000, true, false))
        assertNull(sampler.record(5_001, true, false))
    }

    @Test fun `show and hide transitions are logged immediately`() {
        val sampler = IslandMediaVisibilityDiagnosticSampler()
        sampler.record(0, false, false)
        assertEquals("visibility_guard_shown_changed", sampler.record(1, true, false))
        assertEquals("visibility_guard_shown_changed", sampler.record(2, false, false))
        assertNull(sampler.record(10_000, false, false))
    }

    @Test fun `first frame correction remains explicit and each holder has independent evidence`() {
        val one = IslandMediaVisibilityDiagnosticSampler()
        val two = IslandMediaVisibilityDiagnosticSampler()
        assertEquals("visibility_guard_first_correction", one.record(0, true, true))
        assertEquals("visibility_guard_first_predraw", two.record(0, true, false))
        assertEquals(1L, one.corrections)
        assertEquals(0L, two.corrections)
    }
}
