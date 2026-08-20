package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandHostCleanupPolicyTest {
    @Test
    fun `ordinary non media island is left untouched`() {
        val decision = IslandHostCleanupPolicy.decide(
            wasRegisteredMediaIsland = false,
            hasVisibleInjectedContent = false,
            suppressRelayout = false,
        )

        assertFalse(decision.shouldClear)
        assertFalse(decision.shouldRelayout)
    }

    @Test
    fun `media to non media transition clears and relayouts once`() {
        val decision = IslandHostCleanupPolicy.decide(
            wasRegisteredMediaIsland = true,
            hasVisibleInjectedContent = true,
            suppressRelayout = false,
        )

        assertTrue(decision.shouldClear)
        assertTrue(decision.shouldRelayout)
    }

    @Test
    fun `repeated non media updates do not relayout after cleanup`() {
        val firstUpdate = IslandHostCleanupPolicy.decide(
            wasRegisteredMediaIsland = true,
            hasVisibleInjectedContent = true,
            suppressRelayout = false,
        )
        val repeatedUpdate = IslandHostCleanupPolicy.decide(
            wasRegisteredMediaIsland = false,
            hasVisibleInjectedContent = false,
            suppressRelayout = false,
        )

        assertTrue(firstUpdate.shouldClear)
        assertTrue(firstUpdate.shouldRelayout)
        assertFalse(repeatedUpdate.shouldClear)
        assertFalse(repeatedUpdate.shouldRelayout)
    }

    @Test
    fun `orphaned visible injection is still cleared`() {
        val decision = IslandHostCleanupPolicy.decide(
            wasRegisteredMediaIsland = false,
            hasVisibleInjectedContent = true,
            suppressRelayout = false,
        )

        assertTrue(decision.shouldClear)
        assertTrue(decision.shouldRelayout)
    }

    @Test
    fun `suppressed cleanup still clears without host relayout`() {
        val decision = IslandHostCleanupPolicy.decide(
            wasRegisteredMediaIsland = true,
            hasVisibleInjectedContent = true,
            suppressRelayout = true,
        )

        assertTrue(decision.shouldClear)
        assertFalse(decision.shouldRelayout)
    }
}
