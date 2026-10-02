/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowKeepAlivePolicyTest {

    @Test
    fun `tick is not needed while screen on`() {
        assertFalse(FlowKeepAlivePolicy.tickNeeded(screenOn = true))
    }

    @Test
    fun `tick is needed once screen off`() {
        assertTrue(FlowKeepAlivePolicy.tickNeeded(screenOn = false))
    }
}
