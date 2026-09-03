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
    fun `unrelated preferences do not use the island live refresh broadcast`() {
        assertFalse(LivePreferenceRefreshPolicy.contains("unrelated_preference"))
    }
}
