/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import com.juren233.hyperlyricsenhanced.common.IslandTouchAction.*
import com.juren233.hyperlyricsenhanced.common.IslandTouchBinding
import org.junit.Assert.*
import org.junit.Test

class IslandTouchSeekPolicyTest {
    @Test fun `seeks use per binding seconds and clamp to track ends`() {
        assertEquals(32_000L, IslandTouchSeekPolicy.target(20_000, 60_000, IslandTouchBinding(FORWARD, 12)))
        assertEquals(8_000L, IslandTouchSeekPolicy.target(20_000, 60_000, IslandTouchBinding(BACKWARD, 12)))
        assertEquals(60_000L, IslandTouchSeekPolicy.target(58_000, 60_000, IslandTouchBinding(FORWARD)))
        assertEquals(0L, IslandTouchSeekPolicy.target(2_000, 60_000, IslandTouchBinding(BACKWARD)))
    }
    @Test fun `unknown position does not seek but restart always targets zero`() {
        assertNull(IslandTouchSeekPolicy.target(-1, 60_000, IslandTouchBinding(FORWARD)))
        assertEquals(0L, IslandTouchSeekPolicy.target(-1, 60_000, IslandTouchBinding(RESTART)))
    }
    @Test fun `unknown duration still allows seek without arithmetic overflow`() {
        assertEquals(30_000L, IslandTouchSeekPolicy.target(20_000, 0, IslandTouchBinding(FORWARD)))
        assertEquals(Long.MAX_VALUE, IslandTouchSeekPolicy.target(Long.MAX_VALUE, 0, IslandTouchBinding(FORWARD)))
    }
}
