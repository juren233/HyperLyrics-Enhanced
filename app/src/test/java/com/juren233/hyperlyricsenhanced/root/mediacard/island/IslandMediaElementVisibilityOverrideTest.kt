/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandMediaElementVisibilityOverrideTest {
    @Test fun `cold holder honors saved hidden preference without a settings toggle`() {
        val state = IslandMediaElementVisibilityOverride(0)
        assertEquals(8, state.apply(true, 0))
        assertTrue(state.hidden)
        assertEquals(8, state.apply(true, 8))
    }

    @Test fun `native rebind after initial hiding is corrected before drawing`() {
        val state = IslandMediaElementVisibilityOverride(0)
        assertEquals(8, state.apply(true, 0))
        repeat(3) { assertEquals(8, state.apply(true, 0)) }
        assertEquals(0, state.apply(false, 8))
        assertFalse(state.hidden)
    }

    @Test fun `latest native invisible state is restored instead of forcing visible`() {
        val state = IslandMediaElementVisibilityOverride(0)
        state.apply(true, 0)
        assertEquals(8, state.apply(true, 4))
        assertEquals(8, state.apply(true, 8))
        assertEquals(4, state.apply(false, 8))
    }

    @Test fun `native hidden control stays hidden when override is disabled`() {
        val state = IslandMediaElementVisibilityOverride(8)
        assertEquals(8, state.apply(true, 8))
        assertEquals(8, state.apply(false, 8))
    }

    @Test fun `disabling override preserves a later native write`() {
        val state = IslandMediaElementVisibilityOverride(0)
        state.apply(true, 0)
        assertEquals(4, state.apply(false, 4))
        assertEquals(0, state.apply(false, 0))
    }

    @Test fun `new holders and repeated toggles do not share restoration state`() {
        val first = IslandMediaElementVisibilityOverride(0)
        val second = IslandMediaElementVisibilityOverride(4)
        first.apply(true, 0)
        second.apply(true, 4)
        assertEquals(0, first.apply(false, 8))
        assertTrue(second.hidden)
        assertEquals(8, first.apply(true, 4))
        assertEquals(4, first.apply(false, 8))
        assertEquals(4, second.apply(false, 8))
    }
}
