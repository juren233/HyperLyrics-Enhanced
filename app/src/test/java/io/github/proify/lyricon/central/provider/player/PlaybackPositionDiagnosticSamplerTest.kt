/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackPositionDiagnosticSamplerTest {
    @Test fun `new state is sampled immediately within the periodic interval`() {
        val sampler = PlaybackPositionDiagnosticSampler()
        assertEquals("first_read", sampler.sample(0, 1, true))
        assertNull(sampler.sample(10, 1, true))
        assertEquals("state_changed", sampler.sample(11, 2, true))
    }

    @Test fun `switching from accepted anchor to shared memory is never hidden by sampling`() {
        val sampler = PlaybackPositionDiagnosticSampler()
        sampler.sample(0, 1, true)
        assertEquals("state_changed", sampler.sample(1, 1, false))
        assertEquals("state_changed", sampler.sample(2, 2, true))
    }

    @Test fun `unchanged reads are sampled once per five seconds`() {
        val sampler = PlaybackPositionDiagnosticSampler()
        sampler.sample(0, 1, true)
        for (now in 1L until 5_000L) assertNull(sampler.sample(now, 1, true))
        assertEquals("periodic", sampler.sample(5_000, 1, true))
    }

    @Test fun `separate players have independent first read evidence`() {
        val one = PlaybackPositionDiagnosticSampler()
        val two = PlaybackPositionDiagnosticSampler()
        assertEquals("first_read", one.sample(10, 1, false))
        assertEquals("first_read", two.sample(11, 1, false))
    }
}
