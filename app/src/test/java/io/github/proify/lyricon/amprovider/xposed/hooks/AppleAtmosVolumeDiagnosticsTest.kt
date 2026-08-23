/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleAtmosVolumeDiagnosticsTest {
    @Test
    fun `PCM accumulator reports RMS peak and crest factor in dBFS`() {
        val accumulator = ApplePcmLevelAccumulator()
        accumulator.addNormalizedSamples(0.5, -0.5, 0.5, -0.5)

        val snapshot = requireNotNull(accumulator.snapshotAndReset())

        assertEquals(-6.0206, snapshot.rmsDbfs, 0.001)
        assertEquals(-6.0206, snapshot.peakDbfs, 0.001)
        assertEquals(0.0, snapshot.crestFactorDb, 0.001)
        assertEquals(4L, snapshot.sampledValues)
        assertEquals(null, accumulator.snapshotAndReset())
    }

    @Test
    fun `PCM accumulator preserves crest factor for transient material`() {
        val accumulator = ApplePcmLevelAccumulator()
        accumulator.addNormalizedSamples(1.0, 0.0, 0.0, 0.0)

        val snapshot = requireNotNull(accumulator.snapshotAndReset())

        assertEquals(-6.0206, snapshot.rmsDbfs, 0.001)
        assertEquals(0.0, snapshot.peakDbfs, 0.001)
        assertEquals(6.0206, snapshot.crestFactorDb, 0.001)
    }

    @Test
    fun `records only the first handled non decode only output after format change`() {
        assertTrue(
            shouldRecordFirstRendererOutput(
                pendingFormat = true,
                outputHandled = true,
                decodeOnly = false,
            )
        )
        assertFalse(
            shouldRecordFirstRendererOutput(
                pendingFormat = false,
                outputHandled = true,
                decodeOnly = false,
            )
        )
        assertFalse(
            shouldRecordFirstRendererOutput(
                pendingFormat = true,
                outputHandled = false,
                decodeOnly = false,
            )
        )
        assertFalse(
            shouldRecordFirstRendererOutput(
                pendingFormat = true,
                outputHandled = true,
                decodeOnly = true,
            )
        )
    }
}
