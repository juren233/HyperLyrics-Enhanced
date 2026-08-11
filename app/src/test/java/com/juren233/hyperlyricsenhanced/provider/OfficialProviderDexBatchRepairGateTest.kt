/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialProviderDexRepairGateTest {
    @Test
    fun `allows exactly one repair attempt`() {
        val gate = OfficialProviderDexRepairGate()

        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        assertFalse(gate.tryStart())
    }

    @Test
    fun `deactivates the previous single hook generation on replacement`() {
        val activation = OfficialProviderDexHookActivation()
        val first = activation.current()

        assertTrue(activation.isActive(first))
        val second = activation.replace()
        assertFalse(activation.isActive(first))
        assertTrue(activation.isActive(second))
    }
}
