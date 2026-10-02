/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root

import com.juren233.hyperlyricsenhanced.common.RootConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePreferenceRefreshPolicyTest {
    @Test
    fun `font weight selection and custom value refresh SystemUI immediately`() {
        assertTrue(LivePreferenceRefreshPolicy.contains(RootConstants.KEY_HOOK_FONT_WEIGHT_MODE))
        assertTrue(LivePreferenceRefreshPolicy.contains(RootConstants.KEY_HOOK_FONT_WEIGHT))
    }

    @Test
    fun `next lyric line toggle refreshes SystemUI immediately`() {
        assertTrue(LivePreferenceRefreshPolicy.contains(RootConstants.KEY_HOOK_NEXT_LYRIC_LINE))
        assertTrue(LivePreferenceRefreshPolicy.contains(RootConstants.KEY_HOOK_ISLAND_NEXT_LINE_MODE))
    }

    @Test
    fun `status bar font color refreshes SystemUI immediately`() {
        assertTrue(LivePreferenceRefreshPolicy.contains(RootConstants.KEY_HOOK_STATUS_BAR_TEXT_COLOR))
    }

    @Test
    fun `short lyric song info toggle refreshes SystemUI immediately`() {
        assertTrue(LivePreferenceRefreshPolicy.contains(RootConstants.KEY_HOOK_ISLAND_SHORT_LYRIC_SONG_INFO))
    }
    @Test
    fun `dynamic limit toggle refreshes SystemUI immediately`() {
        assertTrue(LivePreferenceRefreshPolicy.contains(RootConstants.KEY_HOOK_ISLAND_DYNAMIC_LIMIT))
    }

    @Test
    fun `cover text color changes refresh SystemUI immediately`() {
        assertTrue(
            LivePreferenceRefreshPolicy.contains(
                RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_COLOR
            )
        )
        assertTrue(
            LivePreferenceRefreshPolicy.contains(
                RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_GRADIENT
            )
        )
        assertTrue(
            LivePreferenceRefreshPolicy.contains(
                RootConstants.KEY_HOOK_CUSTOM_TEXT_COLOR_ENABLED
            )
        )
        assertTrue(
            LivePreferenceRefreshPolicy.contains(
                RootConstants.KEY_HOOK_CUSTOM_TEXT_COLOR
            )
        )
        assertTrue(
            LivePreferenceRefreshPolicy.contains(
                RootConstants.KEY_HOOK_MONET_TEXT_COLOR
            )
        )
    }

    @Test
    fun `edge progress color mode changes refresh SystemUI immediately`() {
        assertTrue(
            LivePreferenceRefreshPolicy.contains(
                RootConstants.KEY_HOOK_ISLAND_PROGRESS_COLOR_MODE
            )
        )
        assertTrue(
            LivePreferenceRefreshPolicy.contains(
                RootConstants.KEY_HOOK_ISLAND_PROGRESS_CUSTOM_COLOR
            )
        )
    }

    @Test
    fun `album cover whitelist changes refresh SystemUI immediately`() {
        assertTrue(
            LivePreferenceRefreshPolicy.contains(
                RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE_APP_WHITELIST
            )
        )
    }

    @Test
    fun `music wave color mode changes refresh SystemUI immediately`() {
        assertTrue(
            LivePreferenceRefreshPolicy.contains(
                RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR
            )
        )
        assertTrue(
            LivePreferenceRefreshPolicy.contains(
                RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_GRADIENT
            )
        )
        assertTrue(
            LivePreferenceRefreshPolicy.contains(
                RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR_MODE
            )
        )
    }

    @Test
    fun `dynamic width and duet fixed length toggles refresh SystemUI immediately`() {
        assertTrue(LivePreferenceRefreshPolicy.contains(RootConstants.KEY_HOOK_ISLAND_DYNAMIC_WIDTH))
        assertTrue(LivePreferenceRefreshPolicy.contains(RootConstants.KEY_HOOK_ISLAND_DUET_FIXED_LENGTH))
    }

    @Test
    fun `unrelated preferences do not use the island live refresh broadcast`() {
        assertFalse(LivePreferenceRefreshPolicy.contains("unrelated_preference"))
    }
}
