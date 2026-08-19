/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandLyricPositionTest {

    @Test
    fun `legacy center switch migrates to center when no position is stored`() {
        assertEquals(
            RootConstants.ISLAND_LYRIC_POSITION_CENTER,
            IslandLyricPosition.resolve(storedPosition = null, legacyCenterEnabled = true)
        )
    }

    @Test
    fun `stored position takes precedence over the legacy center switch`() {
        assertEquals(
            RootConstants.ISLAND_LYRIC_POSITION_RIGHT,
            IslandLyricPosition.resolve(
                storedPosition = RootConstants.ISLAND_LYRIC_POSITION_RIGHT,
                legacyCenterEnabled = true
            )
        )
    }

    @Test
    fun `side position falls back to the legacy global position`() {
        assertEquals(
            RootConstants.ISLAND_LYRIC_POSITION_CENTER,
            IslandLyricPosition.resolveSide(
                storedSidePosition = null,
                legacyGlobalPosition = RootConstants.ISLAND_LYRIC_POSITION_CENTER,
                legacyCenterEnabled = false
            )
        )
    }

    @Test
    fun `side position overrides the legacy global position independently`() {
        assertEquals(
            RootConstants.ISLAND_LYRIC_POSITION_RIGHT,
            IslandLyricPosition.resolveSide(
                storedSidePosition = RootConstants.ISLAND_LYRIC_POSITION_RIGHT,
                legacyGlobalPosition = RootConstants.ISLAND_LYRIC_POSITION_CENTER,
                legacyCenterEnabled = false
            )
        )
    }

    @Test
    fun `group vocal setting requires verbatim mode and at least one lyric slot`() {
        assertTrue(
            IslandLyricPosition.supportsGroupVocalCentering(
                lyricMode = RootConstants.DEFAULT_HOOK_LYRIC_MODE,
                leftContent = 7,
                rightContent = 5
            )
        )
        assertTrue(
            IslandLyricPosition.supportsGroupVocalCentering(
                lyricMode = RootConstants.DEFAULT_HOOK_LYRIC_MODE,
                leftContent = 5,
                rightContent = 7
            )
        )
        assertFalse(
            IslandLyricPosition.supportsGroupVocalCentering(
                lyricMode = 1,
                leftContent = 7,
                rightContent = 7
            )
        )
        assertFalse(
            IslandLyricPosition.supportsGroupVocalCentering(
                lyricMode = RootConstants.DEFAULT_HOOK_LYRIC_MODE,
                leftContent = 5,
                rightContent = 6
            )
        )
    }
}
