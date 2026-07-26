package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.common.RootConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextSongPreviewPolicyTest {
    @Test
    fun `full preview reserves both island text slots`() {
        assertTrue(
            NextSongPreviewPolicy.reservesSlot(
                previewStyle = RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL,
                targetIsLeft = null,
                slotIsLeft = true
            )
        )
        assertTrue(
            NextSongPreviewPolicy.reservesSlot(
                previewStyle = RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL,
                targetIsLeft = null,
                slotIsLeft = false
            )
        )
    }

    @Test
    fun `half preview only reserves its target slot`() {
        assertTrue(
            NextSongPreviewPolicy.reservesSlot(
                previewStyle = RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF,
                targetIsLeft = true,
                slotIsLeft = true
            )
        )
        assertFalse(
            NextSongPreviewPolicy.reservesSlot(
                previewStyle = RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF,
                targetIsLeft = true,
                slotIsLeft = false
            )
        )
    }

    @Test
    fun `media session duration takes priority over lyric package duration`() {
        assertTrue(
            NextSongPreviewPolicy.resolveTrackDuration(
                mediaDurationMs = 213_000L,
                lyricDurationMs = 205_000L
            ) == 213_000L
        )
    }

    @Test
    fun `lyric duration is only used when media duration is unavailable`() {
        assertTrue(
            NextSongPreviewPolicy.resolveTrackDuration(
                mediaDurationMs = -1L,
                lyricDurationMs = 205_000L
            ) == 205_000L
        )
    }

    @Test
    fun `line lyric shows within selected window when final line starts before it`() {
        assertFalse(NextSongPreviewPolicy.shouldShow(56_999, 60_000, 55_000, null, 3_000, false))
        assertTrue(NextSongPreviewPolicy.shouldShow(57_000, 60_000, 55_000, null, 3_000, false))
    }

    @Test
    fun `line lyric is not replaced when final line starts inside the window`() {
        assertFalse(NextSongPreviewPolicy.shouldShow(58_000, 60_000, 59_000, null, 3_000, false))
    }

    @Test
    fun `syllable lyric shows when final word ends before the window`() {
        assertTrue(NextSongPreviewPolicy.shouldShow(58_000, 60_000, 54_000, 56_500, 3_000, false))
    }

    @Test
    fun `syllable lyric is not replaced when final word ends inside the window`() {
        assertFalse(NextSongPreviewPolicy.shouldShow(58_000, 60_000, 54_000, 58_000, 3_000, false))
    }

    @Test
    fun `syllable ending exactly at the window boundary is not replaced`() {
        assertFalse(NextSongPreviewPolicy.shouldShow(58_000, 60_000, 54_000, 57_000, 3_000, false))
    }

    @Test
    fun `force mode may replace a lyric within selected window`() {
        assertTrue(NextSongPreviewPolicy.shouldShow(58_000, 60_000, 59_000, 59_000, 3_000, true))
    }

    @Test
    fun `disabled duration never shows`() {
        assertFalse(NextSongPreviewPolicy.shouldShow(59_000, 60_000, 55_000, 56_000, 0, true))
    }
}
