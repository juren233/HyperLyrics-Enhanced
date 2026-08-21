/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CentralPlaybackPositionWitnessTest {
    @Test
    fun `continuous active positions recover a stale stopped sink`() {
        val witness = CentralPlaybackPositionWitness()
        witness.onSinkStopped()

        assertFalse(witness.observeActivePosition(1_000L))
        assertFalse(witness.observeActivePosition(1_033L))
        assertTrue(witness.observeActivePosition(1_066L))
        assertFalse(witness.observeActivePosition(1_099L))
    }

    @Test
    fun `one or two in-flight positions after pause do not resume playback`() {
        val witness = CentralPlaybackPositionWitness()
        witness.onSinkPlaybackState(false)

        assertFalse(witness.observeActivePosition(2_000L))
        assertFalse(witness.observeActivePosition(2_033L))
    }

    @Test
    fun `large gaps restart the witness sequence`() {
        val witness = CentralPlaybackPositionWitness()
        witness.onSinkStopped()

        assertFalse(witness.observeActivePosition(3_000L))
        assertFalse(witness.observeActivePosition(3_033L))
        assertFalse(witness.observeActivePosition(4_000L))
        assertFalse(witness.observeActivePosition(4_033L))
        assertTrue(witness.observeActivePosition(4_066L))
    }

    @Test
    fun `known active sink ignores position witnesses`() {
        val witness = CentralPlaybackPositionWitness()
        witness.onSinkPlaybackState(true)

        assertFalse(witness.observeActivePosition(5_000L))
        assertFalse(witness.observeActivePosition(5_033L))
        assertFalse(witness.observeActivePosition(5_066L))
    }
}
