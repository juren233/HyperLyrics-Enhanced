/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleAtmosVolumeDiagnosticsTest {
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
