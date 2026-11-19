/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.common

import com.juren233.hyperlyricsenhanced.common.LyricModeUsage.Scope
import com.juren233.hyperlyricsenhanced.common.RootConstants.HOOK_LYRIC_MODE_FOLLOW_PORTRAIT
import com.juren233.hyperlyricsenhanced.common.RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND
import com.juren233.hyperlyricsenhanced.common.RootConstants.HOOK_LYRIC_MODE_SEPARATED
import com.juren233.hyperlyricsenhanced.common.RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricModeResolverTest {
    @Test fun `follow portrait is the default and keeps the portrait mode in landscape`() {
        assertEquals(HOOK_LYRIC_MODE_FOLLOW_PORTRAIT, RootConstants.DEFAULT_HOOK_LYRIC_MODE_LANDSCAPE)
        assertEquals(HOOK_LYRIC_MODE_FULL_ISLAND,
            LyricModeResolver.effective(HOOK_LYRIC_MODE_FULL_ISLAND, HOOK_LYRIC_MODE_FOLLOW_PORTRAIT, isLandscape = true))
    }

    @Test fun `landscape mode applies only in landscape`() {
        assertEquals(HOOK_LYRIC_MODE_SINGLE_SIDE,
            LyricModeResolver.effective(HOOK_LYRIC_MODE_FULL_ISLAND, HOOK_LYRIC_MODE_SINGLE_SIDE, isLandscape = true))
        assertEquals(HOOK_LYRIC_MODE_FULL_ISLAND,
            LyricModeResolver.effective(HOOK_LYRIC_MODE_FULL_ISLAND, HOOK_LYRIC_MODE_SINGLE_SIDE, isLandscape = false))
    }

    @Test fun `unknown stored landscape value falls back to portrait`() {
        assertNull(LyricModeResolver.landscapeOverride(7))
        assertEquals(HOOK_LYRIC_MODE_SEPARATED,
            LyricModeResolver.effective(HOOK_LYRIC_MODE_SEPARATED, 7, isLandscape = true))
    }

    @Test fun `following portrait never labels a setting with one orientation`() {
        val usage = LyricModeUsage(HOOK_LYRIC_MODE_FULL_ISLAND, HOOK_LYRIC_MODE_FOLLOW_PORTRAIT)
        assertEquals(Scope.BOTH, usage.scopeOf(HOOK_LYRIC_MODE_FULL_ISLAND))
        assertNull(usage.scopeOf(HOOK_LYRIC_MODE_SINGLE_SIDE))
        assertFalse(usage.uses(HOOK_LYRIC_MODE_SINGLE_SIDE))
    }

    @Test fun `different landscape mode labels each mode's settings with its orientation`() {
        val usage = LyricModeUsage(HOOK_LYRIC_MODE_FULL_ISLAND, HOOK_LYRIC_MODE_SINGLE_SIDE)
        assertEquals(Scope.PORTRAIT, usage.scopeOf(HOOK_LYRIC_MODE_FULL_ISLAND))
        assertEquals(Scope.LANDSCAPE, usage.scopeOf(HOOK_LYRIC_MODE_SINGLE_SIDE))
        assertTrue(usage.uses(HOOK_LYRIC_MODE_SINGLE_SIDE))
        assertNull(usage.scopeOf(HOOK_LYRIC_MODE_SEPARATED))
    }

    @Test fun `setting shared by both used modes is not labelled`() {
        val usage = LyricModeUsage(HOOK_LYRIC_MODE_SINGLE_SIDE, HOOK_LYRIC_MODE_SEPARATED)
        assertEquals(Scope.BOTH, usage.scopeOf(HOOK_LYRIC_MODE_SINGLE_SIDE, HOOK_LYRIC_MODE_SEPARATED))
        assertEquals(Scope.LANDSCAPE, usage.scope { it == HOOK_LYRIC_MODE_SEPARATED })
    }
}
