/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.root.source.AppleLyricsRacePolicy.Decision
import com.juren233.hyperlyricsenhanced.root.source.AppleLyricsRacePolicy.State.HIT
import com.juren233.hyperlyricsenhanced.root.source.AppleLyricsRacePolicy.State.MISS
import com.juren233.hyperlyricsenhanced.root.source.AppleLyricsRacePolicy.State.PENDING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsRacePolicyTest {
    private fun decide(
        ttml: List<AppleLyricsRacePolicy.State>,
        search: AppleLyricsRacePolicy.State,
        freshSearch: Boolean = false,
        publishedTtml: Int? = null,
    ) = AppleLyricsRacePolicy.decide(ttml, search, freshSearch, publishedTtml)

    @Test
    fun `search arrives first is shown early while higher priority ttml is pending`() {
        assertEquals(Decision.Early(null), decide(listOf(PENDING, PENDING), HIT, freshSearch = true))
    }

    @Test
    fun `search result already shown is not repeated`() {
        assertEquals(Decision.Wait, decide(listOf(PENDING, PENDING), HIT, freshSearch = false))
    }

    @Test
    fun `search upgrade is shown again while ttml is pending`() {
        assertEquals(Decision.Early(null), decide(listOf(PENDING, PENDING), PENDING, freshSearch = true))
    }

    @Test
    fun `search upgrade does not replace a shown ttml`() {
        assertEquals(Decision.Wait, decide(listOf(PENDING, HIT), PENDING, freshSearch = true, publishedTtml = 1))
    }

    @Test
    fun `late amll always wins over search`() {
        assertEquals(Decision.FinalTtml(0), decide(listOf(HIT, PENDING), HIT))
    }

    @Test
    fun `amll has priority over lb even when lb arrives first`() {
        assertEquals(Decision.Early(1), decide(listOf(PENDING, HIT), PENDING))
        assertEquals(Decision.Early(1), decide(listOf(PENDING, HIT), HIT, freshSearch = true))
        assertEquals(Decision.Wait, decide(listOf(PENDING, HIT), HIT, freshSearch = true, publishedTtml = 1))
        assertEquals(Decision.FinalTtml(1), decide(listOf(MISS, HIT), HIT, publishedTtml = 1))
    }

    @Test
    fun `all ttml missed shows search upgrades and settles once it finishes`() {
        assertEquals(Decision.Wait, decide(listOf(MISS, MISS), PENDING))
        assertEquals(Decision.Early(null), decide(listOf(MISS, MISS), PENDING, freshSearch = true))
        assertEquals(Decision.FinalSearch, decide(listOf(MISS, MISS), HIT, freshSearch = true))
        assertEquals(Decision.FinalSearch, decide(listOf(MISS, MISS), MISS))
    }

    @Test
    fun `nothing to show yet waits`() {
        assertEquals(Decision.Wait, decide(listOf(PENDING, PENDING), PENDING))
        assertEquals(Decision.Wait, decide(listOf(PENDING), MISS))
    }
}

class AppleFallbackIslandSwitchTest {
    private fun song() = com.juren233.hyperlyricsenhanced.lyric.model.Song(id = "1")

    @Test
    fun `pending commit is taken only at the boundary`() {
        val switch = AppleFallbackIslandSwitch()
        var committed = false
        switch.showPreview(AppleFallbackIslandSwitch.Preview(3, song(), null))
        switch.defer(AppleFallbackIslandSwitch.Pending(3, 5_000L) { committed = true })
        assertNull(switch.takeDue(4_999L) { true })
        val due = switch.takeDue(5_000L) { true }
        assertTrue(due != null)
        due!!.commit()
        assertTrue(committed)
        assertNull(switch.takeDue(6_000L) { true })
    }

    @Test
    fun `stale generation is discarded`() {
        val switch = AppleFallbackIslandSwitch()
        switch.defer(AppleFallbackIslandSwitch.Pending(3, 1_000L) { })
        assertNull(switch.takeDue(2_000L) { it == 4 })
        assertNull(switch.takeDue(2_000L) { true })
    }

    @Test
    fun `preview is only visible to its own generation and cleared on clear`() {
        val switch = AppleFallbackIslandSwitch()
        switch.showPreview(AppleFallbackIslandSwitch.Preview(3, song(), null))
        assertTrue(switch.previewFor(3) != null)
        assertNull(switch.previewFor(4))
        switch.clear()
        assertNull(switch.previewFor(3))
    }
}
