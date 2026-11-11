/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandLegacyBackgroundCommitPolicyTest {
    private val before = IslandLegacyBigIslandBounds(width = 668, x = 244)

    @Test
    fun `commits when width changed in big island state and not temp hidden`() {
        assertTrue(
            IslandLegacyBackgroundCommitPolicy.shouldCommit(
                before, IslandLegacyBigIslandBounds(397, 244), { true }, { false },
            ),
        )
    }

    @Test
    fun `commits when only x changed`() {
        assertTrue(
            IslandLegacyBackgroundCommitPolicy.shouldCommit(
                before, IslandLegacyBigIslandBounds(668, 379), { true }, { false },
            ),
        )
    }

    @Test
    fun `does not commit when width and x are unchanged and never queries state`() {
        var queries = 0
        assertFalse(
            IslandLegacyBackgroundCommitPolicy.shouldCommit(
                before, before.copy(), { queries++; true }, { queries++; false },
            ),
        )
        assertEquals(0, queries)
    }

    @Test
    fun `does not commit outside big island state and skips temp hidden query`() {
        var tempHiddenQueries = 0
        assertFalse(
            IslandLegacyBackgroundCommitPolicy.shouldCommit(
                before,
                IslandLegacyBigIslandBounds(397, 379),
                { false },
                { tempHiddenQueries++; false },
            ),
        )
        assertEquals(0, tempHiddenQueries)
    }

    @Test
    fun `does not commit while temp hidden`() {
        assertFalse(
            IslandLegacyBackgroundCommitPolicy.shouldCommit(
                before, IslandLegacyBigIslandBounds(397, 379), { true }, { true },
            ),
        )
    }
}
