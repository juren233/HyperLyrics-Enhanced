package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.common.RootConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandSlotRuntimeConfigTest {

    @Test
    fun `legacy enabled duration migrates to full island preview`() {
        assertEquals(
            RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL,
            IslandSlotRuntimeConfig.resolveNextSongPreviewStyle(
                hasStoredStyle = false,
                storedStyle = RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_NONE,
                legacyDurationSeconds = 4
            )
        )
    }

    @Test
    fun `legacy disabled duration migrates to hidden preview`() {
        assertEquals(
            RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_NONE,
            IslandSlotRuntimeConfig.resolveNextSongPreviewStyle(
                hasStoredStyle = false,
                storedStyle = RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL,
                legacyDurationSeconds = 0
            )
        )
    }

    @Test
    fun `stored preview style takes precedence over legacy duration`() {
        assertEquals(
            RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF,
            IslandSlotRuntimeConfig.resolveNextSongPreviewStyle(
                hasStoredStyle = true,
                storedStyle = RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF,
                legacyDurationSeconds = 0
            )
        )
    }

    @Test
    fun `other side position chooses the side opposite a single lyric slot`() {
        assertFalse(
            IslandSlotRuntimeConfig.resolveHalfPreviewTargetIsLeft(
                position = RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_OTHER_SIDE,
                leftMode = 7,
                rightMode = 5
            )
        )
        assertTrue(
            IslandSlotRuntimeConfig.resolveHalfPreviewTargetIsLeft(
                position = RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_OTHER_SIDE,
                leftMode = 5,
                rightMode = 7
            )
        )
    }

    @Test
    fun `explicit half preview position overrides lyric slots`() {
        assertTrue(
            IslandSlotRuntimeConfig.resolveHalfPreviewTargetIsLeft(
                position = RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_LEFT,
                leftMode = 7,
                rightMode = 5
            )
        )
        assertFalse(
            IslandSlotRuntimeConfig.resolveHalfPreviewTargetIsLeft(
                position = RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_RIGHT,
                leftMode = 5,
                rightMode = 7
            )
        )
    }

    @Test
    fun `half preview always uses a fixed five second window`() {
        assertEquals(
            5_000L,
            IslandSlotRuntimeConfig.resolveNextSongPreviewDurationMs(
                style = RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF,
                fullDurationSeconds = 1
            )
        )
        assertTrue(
            IslandSlotRuntimeConfig.resolveShouldForceNextSongPreview(
                style = RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF,
                fullForceEnabled = false
            )
        )
    }

    @Test
    fun `full preview keeps its configured duration and force switch`() {
        assertEquals(
            3_000L,
            IslandSlotRuntimeConfig.resolveNextSongPreviewDurationMs(
                style = RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL,
                fullDurationSeconds = 3
            )
        )
        assertFalse(
            IslandSlotRuntimeConfig.resolveShouldForceNextSongPreview(
                style = RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL,
                fullForceEnabled = false
            )
        )
    }
}
