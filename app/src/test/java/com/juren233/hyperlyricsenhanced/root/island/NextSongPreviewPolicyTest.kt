package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextSongPreviewPolicyTest {
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
