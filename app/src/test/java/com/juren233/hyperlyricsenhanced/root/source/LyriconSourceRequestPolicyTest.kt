package com.juren233.hyperlyricsenhanced.root.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyriconSourceRequestPolicyTest {
    @Test
    fun `automatic LunaBeat match may replace confirmed Apple native lyrics`() {
        assertTrue(
            acceptsAppleOnlineLyricResult(
                generation = 4,
                currentGeneration = 4,
                sameTrack = true,
                currentNativeLyrics = true,
                currentSongHasNativeLyrics = true,
                manualSourceSwitch = false,
                automaticLunaBeatOverride = true,
            )
        )
    }

    @Test
    fun `automatic result is rejected after native lyrics become current`() {
        assertFalse(
            acceptsAppleOnlineLyricResult(
                generation = 13,
                currentGeneration = 13,
                sameTrack = true,
                currentNativeLyrics = true,
                currentSongHasNativeLyrics = true,
                manualSourceSwitch = false,
            )
        )
    }

    @Test
    fun `automatic result is rejected when native state is stale but current song has lyrics`() {
        assertFalse(
            acceptsAppleOnlineLyricResult(
                generation = 24,
                currentGeneration = 24,
                sameTrack = true,
                currentNativeLyrics = false,
                currentSongHasNativeLyrics = true,
                manualSourceSwitch = false,
            )
        )
    }

    @Test
    fun `automatic result remains accepted for a true missing lyrics song`() {
        assertTrue(
            acceptsAppleOnlineLyricResult(
                generation = 24,
                currentGeneration = 24,
                sameTrack = true,
                currentNativeLyrics = false,
                currentSongHasNativeLyrics = false,
                manualSourceSwitch = false,
            )
        )
    }

    @Test
    fun `confirmed native transition cannot stay in repeated enrichment fast path`() {
        assertFalse(
            shouldKeepRunningAppleEnrichment(
                sameTrack = true,
                authoritativeNativeTransition = true,
                hasLyrics = true,
                needsEnrichment = true,
                originalMetadataChanged = false,
                enrichmentRunning = true,
            )
        )
        assertTrue(
            shouldKeepRunningAppleEnrichment(
                sameTrack = true,
                authoritativeNativeTransition = false,
                hasLyrics = true,
                needsEnrichment = true,
                originalMetadataChanged = false,
                enrichmentRunning = true,
            )
        )
    }

    @Test
    fun `confirmed native transition clears supplement translation attempt`() {
        assertTrue(
            shouldClearAppleOnlineTranslationAttempt(
                sameTrack = true,
                authoritativeNativeTransition = true,
                needsEnrichment = true,
                originalMetadataChanged = false,
            )
        )
    }

    @Test
    fun `ordinary repeated native callback keeps translation attempt deduplicated`() {
        assertFalse(
            shouldClearAppleOnlineTranslationAttempt(
                sameTrack = true,
                authoritativeNativeTransition = false,
                needsEnrichment = true,
                originalMetadataChanged = false,
            )
        )
    }

    @Test
    fun `manual source switch is accepted for same track despite native callback`() {
        assertTrue(
            acceptsAppleOnlineLyricResult(
                generation = 13,
                currentGeneration = 13,
                sameTrack = true,
                currentNativeLyrics = true,
                currentSongHasNativeLyrics = true,
                manualSourceSwitch = true,
            )
        )
    }

    @Test
    fun `manual source switch still rejects stale generation or different track`() {
        assertFalse(
            acceptsAppleOnlineLyricResult(
                generation = 12,
                currentGeneration = 13,
                sameTrack = true,
                currentNativeLyrics = true,
                currentSongHasNativeLyrics = true,
                manualSourceSwitch = true,
            )
        )
        assertFalse(
            acceptsAppleOnlineLyricResult(
                generation = 13,
                currentGeneration = 13,
                sameTrack = false,
                currentNativeLyrics = true,
                currentSongHasNativeLyrics = true,
                manualSourceSwitch = true,
            )
        )
    }
}
