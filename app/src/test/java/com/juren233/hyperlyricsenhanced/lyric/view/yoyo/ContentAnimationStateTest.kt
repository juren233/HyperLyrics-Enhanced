/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.yoyo

import org.junit.Assert.*
import org.junit.Test

class ContentAnimationStateTest {
    @Test fun bindingReleasesWidthBeforeEntranceFinishes() {
        val state = ContentAnimationState(true)
        assertTrue(state.awaitingContent)
        assertTrue(state.claimContent())
        assertFalse(state.awaitingContent)
        assertTrue(state.running)
        state.finish()
        assertFalse(state.running)
    }

    @Test fun cancelDuringExitAppliesQueuedContentOnlyOnce() {
        val state = ContentAnimationState(true)
        assertTrue(state.cancel())
        assertFalse(state.claimContent())
        assertFalse(state.cancel())
        assertFalse(state.awaitingContent)
    }

    @Test fun cancelDuringEntranceDoesNotReplayOldBinding() {
        val state = ContentAnimationState(true)
        state.claimContent()
        assertFalse(state.cancel())
        assertFalse(state.running)
    }

    @Test fun entranceOnlyNeverBlocksWidth() {
        val state = ContentAnimationState(false)
        assertTrue(state.running)
        assertFalse(state.awaitingContent)
        assertFalse(state.claimContent())
    }

    @Test fun staleCompletionCannotChangeTheNextTransaction() {
        val old = ContentAnimationState(true)
        old.cancel()
        val next = ContentAnimationState(true)
        old.finish()
        assertTrue(next.awaitingContent)
        assertTrue(next.claimContent())
        assertTrue(next.running)
    }
}
