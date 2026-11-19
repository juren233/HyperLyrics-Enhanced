/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.common.RootConstants.DEFAULT_HOOK_ISLAND_KEEP_VISIBLE
import com.juren233.hyperlyricsenhanced.common.RootConstants.ISLAND_KEEP_VISIBLE_ALL
import com.juren233.hyperlyricsenhanced.common.RootConstants.ISLAND_KEEP_VISIBLE_OFF
import com.juren233.hyperlyricsenhanced.common.RootConstants.ISLAND_KEEP_VISIBLE_STATUS_BAR_HIDDEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandKeepVisiblePolicyTest {
    private val hooker = IslandKeepVisibleHooker

    @Test fun `off is the default and forwards native status bar state`() {
        assertEquals(ISLAND_KEEP_VISIBLE_OFF, DEFAULT_HOOK_ISLAND_KEEP_VISIBLE)
        assertTrue(hooker.forwardedStatusBarHidden(true, ISLAND_KEEP_VISIBLE_OFF))
        assertFalse(hooker.forwardedStatusBarHidden(false, ISLAND_KEEP_VISIBLE_OFF))
    }

    @Test fun `status bar option and all scenarios keep the island when status bar hides`() {
        assertFalse(hooker.forwardedStatusBarHidden(true, ISLAND_KEEP_VISIBLE_STATUS_BAR_HIDDEN))
        assertFalse(hooker.forwardedStatusBarHidden(true, ISLAND_KEEP_VISIBLE_ALL))
    }

    @Test fun `only all scenarios keeps the music island out of its app`() {
        assertTrue(hooker.blocksAppState(ISLAND_KEEP_VISIBLE_ALL, isMediaIsland = true))
        assertFalse(hooker.blocksAppState(ISLAND_KEEP_VISIBLE_ALL, isMediaIsland = false))
        assertFalse(hooker.blocksAppState(ISLAND_KEEP_VISIBLE_STATUS_BAR_HIDDEN, isMediaIsland = true))
        assertFalse(hooker.blocksAppState(ISLAND_KEEP_VISIBLE_OFF, isMediaIsland = true))
    }
}
