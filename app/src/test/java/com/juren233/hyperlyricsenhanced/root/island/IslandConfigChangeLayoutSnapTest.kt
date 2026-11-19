/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandConfigChangeLayoutSnapTest {
    @Test fun `only config change noAnim transition snaps content`() {
        assertTrue(IslandConfigChangeLayoutSnap.shouldSnap(true))
        assertFalse(IslandConfigChangeLayoutSnap.shouldSnap(false))
        assertFalse(IslandConfigChangeLayoutSnap.shouldSnap(null))
    }
}
