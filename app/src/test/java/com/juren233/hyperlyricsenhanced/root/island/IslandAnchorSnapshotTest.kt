/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.root.island.IslandDynamicLimitPolicy.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IslandAnchorSnapshotTest {
    private val spans = listOf(
        Span(130, 250, text = true),
        Span(800, 980)
    )

    @Test fun `fallback rejected when nothing recorded yet`() {
        assertNull(IslandAnchorSnapshot().fallbackFor(1000))
    }

    @Test fun `fallback returns recorded spans for matching screen width`() {
        val snapshot = IslandAnchorSnapshot()
        snapshot.update(spans, observedScreenWidth = 1000)
        assertEquals(spans, snapshot.fallbackFor(1000))
    }

    @Test fun `fallback rejected on screen width mismatch`() {
        val snapshot = IslandAnchorSnapshot()
        snapshot.update(spans, observedScreenWidth = 1000)
        assertNull(snapshot.fallbackFor(2000))
    }

    @Test fun `fallback rejected when recorded spans are empty`() {
        val snapshot = IslandAnchorSnapshot()
        snapshot.update(emptyList(), observedScreenWidth = 1000)
        assertNull(snapshot.fallbackFor(1000))
    }

    @Test fun `latest update replaces earlier snapshot`() {
        val snapshot = IslandAnchorSnapshot()
        snapshot.update(spans, observedScreenWidth = 1000)
        val rotated = listOf(Span(20, 120, text = true))
        snapshot.update(rotated, observedScreenWidth = 2000)
        assertEquals(rotated, snapshot.fallbackFor(2000))
        assertNull(snapshot.fallbackFor(1000))
    }

    @Test fun `save and restore keep the clock mark`() {
        val marked = listOf(Span(83, 266, text = true, clock = true), Span(536, 599, text = true))
        val snapshot = IslandAnchorSnapshot()
        snapshot.update(marked, observedScreenWidth = 1200)
        val restored = IslandAnchorSnapshot().also { it.restore(snapshot.save()) }
        assertEquals(marked, restored.fallbackFor(1200))
    }

    @Test fun `old format flags 0 and 1 restore with clock false`() {
        val restored = IslandAnchorSnapshot().also {
            it.restore(intArrayOf(1200, 83, 266, 1, 800, 980, 0))
        }
        assertEquals(
            listOf(Span(83, 266, text = true), Span(800, 980)),
            restored.fallbackFor(1200),
        )
    }
}
