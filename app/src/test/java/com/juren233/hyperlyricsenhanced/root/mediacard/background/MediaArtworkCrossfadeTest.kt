package com.juren233.hyperlyricsenhanced.root.mediacard.background

import org.junit.Assert.*
import org.junit.Test

class MediaArtworkCrossfadeTest {
    @Test fun firstArtworkHasNoPlaceholderFade() {
        val state = MediaArtworkCrossfade<String>()
        assertTrue(state.offer("A"))
        assertFalse(state.active)
        assertEquals(1f, state.draw(0), 0f)
    }
    @Test fun artworkChangeAdvancesWithoutPlayback() {
        val state = MediaArtworkCrossfade<String>()
        state.offer("A")
        assertTrue(state.offer("B"))
        assertEquals(0f, state.draw(100), 0f)
        assertEquals(0.5f, state.draw(525), 0.001f)
        assertEquals(1f, state.draw(950), 0f)
        assertNull(state.completeFrame())
        assertFalse(state.active)
    }
    @Test fun hiddenTimeDoesNotSkipTheVisibleBlend() {
        val state = MediaArtworkCrossfade<String>()
        state.offer("A"); state.offer("B")
        state.draw(0)
        val fraction = state.draw(200)
        state.suspendDrawing()
        assertEquals(fraction, state.draw(100000), 0f)
    }
    @Test fun burstKeepsLatestAndStartsFromCompletedTarget() {
        val state = MediaArtworkCrossfade<String>()
        state.offer("A"); state.offer("B")
        state.draw(0); state.draw(400)
        assertFalse(state.offer("C"))
        assertFalse(state.offer("D"))
        assertEquals("B", state.target)
        assertEquals("D", state.pending)
        assertNull(state.completeFrame())
        state.draw(850)
        assertEquals("D", state.completeFrame())
        assertEquals(0f, state.draw(850), 0f)
        assertTrue(state.active)
    }
    @Test fun latestRequestForCurrentTargetCancelsStalePending() {
        val state = MediaArtworkCrossfade<String>()
        state.offer("A"); state.offer("B"); state.offer("C"); state.offer("B")
        assertNull(state.pending)
        state.draw(0); state.draw(850)
        assertNull(state.completeFrame())
    }
}
