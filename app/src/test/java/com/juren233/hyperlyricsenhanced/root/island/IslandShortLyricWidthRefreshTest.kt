/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandShortLyricWidthRefreshTest {
    @Test
    fun `a native width animation does not schedule content updates for its individual frames`() {
        val refresh = IslandShortLyricWidthRefresh()
        repeat(30) { assertFalse(refresh.onWidthChanged(morphRunning = true)) }
        assertTrue(refresh.onMorphFinished())
        assertFalse(refresh.onMorphFinished())
    }

    @Test
    fun `ordinary width changes still request immediate classification`() {
        val refresh = IslandShortLyricWidthRefresh()
        assertTrue(refresh.onWidthChanged(morphRunning = false))
        assertFalse(refresh.onMorphFinished())
    }

    @Test
    fun `detach cancels pending classification instead of refreshing an old host`() {
        val refresh = IslandShortLyricWidthRefresh()
        assertFalse(refresh.onWidthChanged(morphRunning = true))
        refresh.clear()
        assertFalse(refresh.onMorphFinished())
        assertTrue(refresh.onWidthChanged(morphRunning = false))
    }

    @Test
    fun `reversal can collect a fresh width change after the preceding morph is released`() {
        val refresh = IslandShortLyricWidthRefresh()
        assertFalse(refresh.onWidthChanged(morphRunning = true))
        assertTrue(refresh.onMorphFinished())
        assertFalse(refresh.onWidthChanged(morphRunning = true))
        assertTrue(refresh.onMorphFinished())
        assertFalse(refresh.onMorphFinished())
    }
}
