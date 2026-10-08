/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import org.junit.Assert.*
import org.junit.Test

class IslandTouchContinuousVolumeTest {
    @Test fun `level follows total travel and returns when dragged back`() {
        val applied = mutableListOf<Int>()
        val drag = IslandTouchContinuousVolume(base = 7, min = 0, max = 15, stepPx = 10f) { applied += it }
        drag.update(-9f)
        drag.update(-25f)
        drag.update(-200f)
        drag.update(-5f)
        drag.update(35f)
        drag.update(500f)
        assertEquals(listOf(5, 0, 7, 10, 15), applied)
    }

    @Test fun `reports every ten percent boundary crossed even within one update`() {
        val ticks = mutableListOf<Int>()
        val drag = IslandTouchContinuousVolume(base = 50, min = 0, max = 100, stepPx = 1f, tick = { ticks += it }) {}
        drag.update(5f)    // 55: same band
        drag.update(10f)   // 60: crosses 60%
        drag.update(35f)   // 85: crosses 70% and 80% in one move
        drag.update(9f)    // 59: back across 80%, 70%, 60%
        drag.update(500f)  // 100: crosses 60% through 100%
        assertEquals(listOf(1, 2, 3, 5), ticks)
        assertEquals(listOf(0, 0, 1, 2, 2, 3, 4, 4, 5, 6, 6, 7, 8, 8, 9, 10),
            (0..15).map { IslandTouchContinuousVolume.decile(it, 0, 15) })
    }

    @Test fun `step keeps a full sweep within reach and a usable minimum`() {
        assertEquals(200f / 15, IslandTouchContinuousVolume.stepPx(0, 15, 1f), 0.001f)
        assertEquals(6f, IslandTouchContinuousVolume.stepPx(0, 150, 3f), 0.001f)
        assertEquals(200f, IslandTouchContinuousVolume.stepPx(3, 3, 1f), 0.001f)
        assertEquals(4, IslandTouchContinuousVolume.level(4, 1, 8, -9.9f, 10f))
        assertEquals(1, IslandTouchContinuousVolume.level(4, 1, 8, -100f, 10f))
    }
}
