/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.*
import org.junit.Test

class IslandContentMotionReaderTest {
    class Delegate(var selfMotion: Boolean = false, var windowMotion: Boolean = false) {
        fun isAnimating(): Boolean = selfMotion
        fun getIslandWindowAnimRunning(): Boolean = windowMotion
    }
    class Host(val delegate: Delegate?) {
        // Reproduces 220040: the aggregate remains true with two lost completions.
        val isAnimationRunning = true
        val pendingDispatchCount = 2
        fun getAnimatorDelegate(): Delegate? = delegate
    }
    private val reader = IslandContentMotionReader(
        Host::class.java.getMethod("getAnimatorDelegate"),
        Delegate::class.java.getMethod("isAnimating"),
        Delegate::class.java.getMethod("getIslandWindowAnimRunning"),
    )

    @Test fun StaleAggregateCannotKeepFinishedContentQueued() {
        val host = Host(Delegate(selfMotion = true))
        val pending = IslandDeferredContentState<String>()
        pending.merge(content = true)
        assertFalse(pending.onFrame(reader.isAnimating(host) == true))
        host.delegate!!.selfMotion = false
        assertTrue(host.isAnimationRunning)
        assertEquals(2, host.pendingDispatchCount)
        assertFalse(pending.onFrame(reader.isAnimating(host) == true))
        assertTrue(pending.onFrame(reader.isAnimating(host) == true))
    }

    @Test fun AppOrFreeformMotionAndQuickReversalStillKeepTheBarrierClosed() {
        val motion = Delegate(windowMotion = true)
        val host = Host(motion)
        val pending = IslandDeferredContentState<String>()
        repeat(3) { assertFalse(pending.onFrame(reader.isAnimating(host) == true)) }
        motion.windowMotion = false
        assertFalse(pending.onFrame(reader.isAnimating(host) == true))
        motion.selfMotion = true
        assertFalse(pending.onFrame(reader.isAnimating(host) == true))
        motion.selfMotion = false
        assertFalse(pending.onFrame(reader.isAnimating(host) == true))
        assertTrue(pending.onFrame(reader.isAnimating(host) == true))
    }

    @Test fun MissingOrIncompatibleDelegateDoesNotStrandAnExistingPendingUpdate() {
        assertEquals(false, reader.isAnimating(Host(null)))
        assertNull(reader.isAnimating(Any()))
    }
}
