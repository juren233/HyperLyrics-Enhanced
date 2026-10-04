/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.*
import org.junit.Test

class IslandDeferredContentStateTest {
    @Test fun manyIntermediateUpdatesCommitOnlyTheLatestPresentation() {
        val pending = IslandDeferredContentState<String>()
        val painted = mutableListOf<String>()
        repeat(80) { index ->
            pending.merge(content = true, width = true)
            pending.replaceAction("cover") { painted += "cover-$index" }
            assertFalse(pending.onFrame(animating = true))
        }
        assertTrue(painted.isEmpty())
        assertFalse(pending.onFrame(animating = false))
        assertTrue(pending.onFrame(animating = false))
        pending.takeActions().forEach { it() }
        assertEquals(listOf("cover-79"), painted)
        assertTrue(pending.content)
        assertTrue(pending.width)
        assertTrue(pending.takeActions().isEmpty())
    }

    @Test fun ReversalBetweenQuietFramesDoesNotReleaseTheOldBatch() {
        val pending = IslandDeferredContentState<String>()
        pending.merge(content = true)
        assertFalse(pending.onFrame(false))
        assertFalse(pending.onFrame(true))
        assertFalse(pending.onFrame(false))
        assertTrue(pending.onFrame(false))
    }

    @Test fun ContinuousPositionDeliveryDoesNotPostponeAStableCommitForever() {
        val pending = IslandDeferredContentState<String>()
        pending.merge(content = true)
        assertFalse(pending.onFrame(false))
        pending.merge(content = true)
        assertTrue(pending.onFrame(false))
    }

    @Test fun SystemWidthRequestsSurviveLaterLyricOnlyRequests() {
        val pending = IslandDeferredContentState<String>()
        pending.merge(width = true, protectLyricLottie = false)
        pending.merge(content = true)
        pending.merge(width = true, protectLyricLottie = true)
        assertTrue(pending.content)
        assertTrue(pending.width)
        assertFalse(pending.protectLyricLottie)
    }

    @Test fun ContentWithoutAWidthRequestDoesNotInventOne() {
        val pending = IslandDeferredContentState<String>()
        pending.merge(content = true, protectLyricLottie = false)
        assertFalse(pending.width)
        pending.merge(width = true)
        assertTrue(pending.protectLyricLottie)
    }

    @Test fun PauseAndSeekAreReadAtCommitInsteadOfReplayingOldSnapshots() {
        val pending = IslandDeferredContentState<String>()
        var latestPosition = 100L
        var playing = true
        var presented = ""
        pending.replaceAction("current") { presented = "$latestPosition/$playing" }
        repeat(4) { assertFalse(pending.onFrame(true)) }
        latestPosition = 9200L
        playing = false
        assertFalse(pending.onFrame(false))
        assertTrue(pending.onFrame(false))
        pending.takeActions().forEach { it() }
        assertEquals("9200/false", presented)
    }

    @Test fun SeparateHostsAndSeparateCoverSlotsDoNotOverwriteEachOther() {
        val first = IslandDeferredContentState<String>()
        val second = IslandDeferredContentState<String>()
        val applied = mutableListOf<String>()
        first.replaceAction("large") { applied += "stale" }
        first.replaceAction("small") { applied += "small" }
        first.replaceAction("large") { applied += "large" }
        second.replaceAction("large") { applied += "other-host" }
        first.takeActions().forEach { it() }
        assertEquals(listOf("large", "small"), applied)
        second.takeActions().forEach { it() }
        assertEquals(listOf("large", "small", "other-host"), applied)
    }
    @Test fun OrdinaryProgressDoesNotBecomeASeekAtSettlement() {
        val pending = IslandDeferredContentState<String>()
        repeat(80) { pending.merge(content = true) }
        assertFalse(pending.onFrame(false))
        assertTrue(pending.onFrame(false))
        assertFalse(pending.seek)
    }

    @Test fun ExplicitSeekSurvivesLaterProgressAndContentUpdates() {
        val pending = IslandDeferredContentState<String>()
        pending.merge(content = true, seek = true)
        repeat(80) { pending.merge(content = true, width = true) }
        assertFalse(pending.onFrame(false))
        assertTrue(pending.onFrame(false))
        assertTrue(pending.seek)
        assertFalse(IslandDeferredContentState<String>().seek)
    }


}
