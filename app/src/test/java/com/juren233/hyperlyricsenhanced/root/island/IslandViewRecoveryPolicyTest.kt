/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertEquals
import org.junit.Test

class IslandViewRecoveryPolicyTest {
    @Test
    fun `missing host uses candidate reattach path`() {
        assertEquals(
            IslandViewRecoveryPolicy.Action.REATTACH_HOST,
            IslandViewRecoveryPolicy.decide(
                hasRegisteredHost = false,
                hasInjectedView = false,
            ),
        )
    }

    @Test
    fun `registered host without injection must reinject`() {
        assertEquals(
            IslandViewRecoveryPolicy.Action.REINJECT_REGISTERED_HOST,
            IslandViewRecoveryPolicy.decide(
                hasRegisteredHost = true,
                hasInjectedView = false,
            ),
        )
    }

    @Test
    fun `registered host with injection updates existing view`() {
        assertEquals(
            IslandViewRecoveryPolicy.Action.UPDATE_EXISTING_VIEW,
            IslandViewRecoveryPolicy.decide(
                hasRegisteredHost = true,
                hasInjectedView = true,
            ),
        )
    }
}
