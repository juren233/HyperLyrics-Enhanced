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
    @Test
    fun `search arrives first is shown early while higher priority ttml is pending`() {
        val decision = AppleLyricsRacePolicy.decide(listOf(PENDING, PENDING), HIT, earlyPublished = false)
        assertEquals(Decision.Early(null), decision)
    }

    @Test
    fun `early is not repeated once published`() {
        val decision = AppleLyricsRacePolicy.decide(listOf(PENDING, PENDING), HIT, earlyPublished = true)
        assertEquals(Decision.Wait, decision)
    }

    @Test
    fun `late amll always wins over search`() {
        val decision = AppleLyricsRacePolicy.decide(listOf(HIT, PENDING), HIT, earlyPublished = true)
        assertEquals(Decision.FinalTtml(0), decision)
    }

    @Test
    fun `amll has priority over lb even when lb arrives first`() {
        assertEquals(
            Decision.Early(1),
            AppleLyricsRacePolicy.decide(listOf(PENDING, HIT), PENDING, earlyPublished = false),
        )
        assertEquals(
            Decision.Wait,
            AppleLyricsRacePolicy.decide(listOf(PENDING, HIT), HIT, earlyPublished = true),
        )
        assertEquals(
            Decision.FinalTtml(1),
            AppleLyricsRacePolicy.decide(listOf(MISS, HIT), HIT, earlyPublished = true),
        )
    }

    @Test
    fun `all ttml missed falls to search once it finishes`() {
        assertEquals(
            Decision.Wait,
            AppleLyricsRacePolicy.decide(listOf(MISS, MISS), PENDING, earlyPublished = false),
        )
        assertEquals(
            Decision.FinalSearch,
            AppleLyricsRacePolicy.decide(listOf(MISS, MISS), HIT, earlyPublished = false),
        )
        assertEquals(
            Decision.FinalSearch,
            AppleLyricsRacePolicy.decide(listOf(MISS, MISS), MISS, earlyPublished = false),
        )
    }

    @Test
    fun `nothing to show yet waits`() {
        assertEquals(
            Decision.Wait,
            AppleLyricsRacePolicy.decide(listOf(PENDING, PENDING), PENDING, earlyPublished = false),
        )
        assertEquals(
            Decision.Wait,
            AppleLyricsRacePolicy.decide(listOf(PENDING), MISS, earlyPublished = false),
        )
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
