/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.*
import org.junit.Test

class IslandAnchorReloadTest {
    @Test fun `reload preserves frozen geometry and rejects different display widths`() {
        val spans = listOf(IslandDynamicLimitPolicy.Span(12, 120, true))
        val old = IslandAnchorSnapshot().apply { update(spans, 1080) }
        val restored = IslandAnchorSnapshot().apply { restore(old.save()) }
        assertEquals(spans, restored.fallbackFor(1080))
        assertNull(restored.fallbackFor(1920))
        restored.restore(intArrayOf(1080, 12))
        assertEquals(spans, restored.fallbackFor(1080))
    }
}
