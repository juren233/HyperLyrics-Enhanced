/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.island.IslandDynamicLimitPolicy.Geometry
import org.junit.Assert.*
import org.junit.Test

class IslandFullIslandWidthPolicyTest {
    private val phone = Geometry(720, 320, 320, 240)

    @Test fun `short independent content follows longer side including cover and rhythm`() {
        val left = demand(area = 500, text = 390, content = 72) // cover plus padding: 110
        val right = demand(area = 480, text = 450, content = 90) // rhythm plus padding: 30
        assertEquals(Geometry(444, 182, 182, 378), shrink(left, right))
    }

    @Test fun `long content cannot enlarge the already limited native result`() {
        assertEquals(phone, shrink(1000, 1500))
        assertEquals(Geometry(720, 320, 320, 240), shrink(100, 320))
    }

    @Test fun `empty content keeps cutout and pill floor`() {
        assertEquals(Geometry(110, 15, 15, 545), shrink(0, 0))
        assertEquals(Geometry(112, 16, 16, 544), shrink(0, 0, floor = 111))
    }

    @Test fun `native floor below requested minimum is never enlarged`() {
        val native = Geometry(100, 10, 10, 550)
        assertEquals(native, IslandFullIslandWidthPolicy.shrink(native, 0, 0, 1200, 110, false))
    }

    @Test fun `tablet keeps independent side widths and native centering formula`() {
        val original = Geometry(900, 380, 500, 150)
        assertEquals(Geometry(290, 90, 180, 455),
            IslandFullIslandWidthPolicy.shrink(original, 90, 180, 1200, 110, true))
        val empty = IslandFullIslandWidthPolicy.shrink(original, 0, 0, 1200, 110, true)
        assertEquals(110, empty.width)
        assertEquals(20, empty.width - empty.left - empty.right)
        assertEquals((1200 - empty.width) / 2, empty.x)
    }

    @Test fun `asymmetric phone alternative and invalid native geometry are retained`() {
        for (native in listOf(Geometry(344, 158, 106, 402), Geometry(80, 60, 60, 560))) {
            assertEquals(native, IslandFullIslandWidthPolicy.shrink(native, 20, 30, 1200, 110, false))
        }
    }

    @Test fun `capacity excludes nontext overhead and custom content cap`() {
        assertEquals(290, capacity(areaLimit = 320, area = 480, text = 450))
        assertEquals(100, capacity(areaLimit = 320, area = 130, text = 100))
        assertEquals(0, capacity(areaLimit = 20, area = 480, text = 450))
        assertEquals(130, demand(area = 130, text = 100, content = 800))
    }

    @Test fun `shrunk layout does not reject a longer line that fits original capacity`() {
        val nativeCapacity = capacity(320, 480, 450)
        val short = shrink(100, demand(480, 450, 90))
        val shortViewport = short.right - 30
        assertEquals(90, shortViewport)
        assertTrue(230 > shortViewport)
        assertTrue(isShort(230, nativeCapacity))
        val longer = shrink(100, demand(480, 450, 230))
        assertEquals(260, longer.right)
        assertEquals(600, longer.width)
        assertFalse(isShort(291, nativeCapacity))
        // Repeating a width refresh from the unshrunk native calculation is idempotent.
        repeat(5) { assertEquals(longer, shrink(100, demand(480, 450, 230))) }
    }

    @Test fun `native limit change reclassifies current line and recovers when space returns`() {
        assertTrue(isShort(230, capacity(320, 480, 450)))
        assertFalse(isShort(230, capacity(240, 480, 450)))
        assertTrue(isShort(230, capacity(320, 480, 450)))
    }

    @Test fun `all tested demands preserve bounds floor symmetry cutout and centered x`() {
        for (left in 0..400 step 13) for (right in 0..400 step 17) {
            val result = shrink(left, right)
            assertTrue(result.width in 110..phone.width)
            assertEquals(result.left, result.right)
            assertEquals(80, result.width - result.left - result.right)
            assertEquals((1200 - result.width) / 2, result.x)
            assertTrue(result.left >= minOf(320, maxOf(left, right)))
        }
    }

    private fun shrink(left: Int, right: Int, floor: Int = 110) =
        IslandFullIslandWidthPolicy.shrink(phone, left, right, 1200, floor, false)
    private fun demand(area: Int, text: Int, content: Int) =
        IslandFullIslandWidthPolicy.requiredArea(area, text, content)
    private fun capacity(areaLimit: Int, area: Int, text: Int) =
        IslandFullIslandWidthPolicy.contentCapacity(areaLimit, area, text)
    private fun isShort(content: Int, capacity: Int) = IslandShortLyricPolicy.usesSongInfo(
        RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND, true, content, capacity)
}
