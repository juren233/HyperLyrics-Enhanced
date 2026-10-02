/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.*
import org.junit.Test

class IslandRightHandoffPolicyTest {
    @Test
    fun `only a stable short to short handoff keeps metadata live`() {
        assertTrue(IslandRightHandoffPolicy.preservesLeftMetadata(false, false, true))
        assertFalse(IslandRightHandoffPolicy.preservesLeftMetadata(false, false, false))
        assertFalse(IslandRightHandoffPolicy.preservesLeftMetadata(true, false, true))
        assertFalse(IslandRightHandoffPolicy.preservesLeftMetadata(false, true, true))
        assertFalse(IslandRightHandoffPolicy.preservesLeftMetadata(true, true, true))
    }

    @Test
    fun `rapid short sentence replacements retain the already live metadata`() {
        var previous: Boolean? = null
        repeat(4) {
            previous = IslandRightHandoffPolicy.preservesLeftMetadata(false, false, true, previous)
            assertEquals(true, previous)
        }
        // A third sentence can arrive before the flight finishes and no longer match its text.
        assertTrue(IslandRightHandoffPolicy.preservesLeftMetadata(false, false, false, previous))
        assertFalse(IslandRightHandoffPolicy.preservesLeftMetadata(false, true, true, previous))
    }

    @Test
    fun `interrupted role changes do not instantly reveal partially appearing metadata`() {
        val enteringShort = IslandRightHandoffPolicy.preservesLeftMetadata(true, false, true)
        assertFalse(IslandRightHandoffPolicy.preservesLeftMetadata(false, false, true, enteringShort))
        // After completion or cancellation the next transition starts from a stable live view.
        assertTrue(IslandRightHandoffPolicy.preservesLeftMetadata(false, false, true, null))
    }

    @Test
    fun `only horizontal right handoff gets the longer duration`() {
        assertEquals(360L, IslandRightHandoffPolicy.durationMillis(220L, true))
        assertEquals(500L, IslandRightHandoffPolicy.durationMillis(500L, true))
        assertEquals(220L, IslandRightHandoffPolicy.durationMillis(220L, false))
        assertEquals(300L, IslandRightHandoffPolicy.durationMillis(300L, false))
    }
}
