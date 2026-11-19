/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.common

import org.junit.Assert.assertEquals
import org.junit.Test

class IslandSideLengthTest {

    @Test
    fun `legacy widths migrate to the wider side plus the icon allowance`() {
        assertEquals(106, IslandSideLength.fromLegacy(left = 45, right = 80))
        assertEquals(126, IslandSideLength.fromLegacy(left = 80, right = 100))
    }

    @Test
    fun `an unlimited legacy side defers to the other side and both unlimited stays unlimited`() {
        assertEquals(126, IslandSideLength.fromLegacy(left = 0, right = 100))
        assertEquals(IslandSideLength.UNLIMITED, IslandSideLength.fromLegacy(left = 0, right = 0))
    }

    @Test
    fun `migrated value never exceeds the slider maximum`() {
        assertEquals(IslandSideLength.MAX, IslandSideLength.fromLegacy(left = 500, right = 500))
    }

    @Test
    fun `stored side length wins over legacy values and is clamped`() {
        assertEquals(
            90,
            IslandSideLength.resolve(stored = 90, legacyLeft = 200, legacyRight = 300),
        )
        assertEquals(IslandSideLength.MAX, IslandSideLength.resolve(stored = 900, legacyLeft = null, legacyRight = null))
    }

    @Test
    fun `a single legacy side uses the old default for the other side`() {
        assertEquals(126, IslandSideLength.resolve(stored = null, legacyLeft = 30, legacyRight = null))
        assertEquals(176, IslandSideLength.resolve(stored = null, legacyLeft = null, legacyRight = 150))
    }

    @Test
    fun `nothing stored falls back to the default that matches the old defaults`() {
        assertEquals(IslandSideLength.DEFAULT, IslandSideLength.resolve(null, null, null))
        assertEquals(
            IslandSideLength.fromLegacy(
                RootConstants.DEFAULT_HOOK_ISLAND_LEFT_CONTENT_MAX_WIDTH,
                RootConstants.DEFAULT_HOOK_ISLAND_RIGHT_CONTENT_MAX_WIDTH,
            ),
            IslandSideLength.DEFAULT,
        )
    }
}
