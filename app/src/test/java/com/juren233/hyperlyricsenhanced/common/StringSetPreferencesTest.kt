/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StringSetPreferencesTest {
    @Test
    fun `backup format parses to a trimmed set and ignores blanks`() {
        assertEquals(
            setOf("com.tencent.qqmusic", "com.netease.cloudmusic"),
            StringSetPreferences.parse("com.tencent.qqmusic, com.netease.cloudmusic,,"),
        )
        assertTrue(StringSetPreferences.parse("").isEmpty())
        assertTrue(StringSetPreferences.parse("  ").isEmpty())
    }

    @Test
    fun `every set typed preference is registered for restore`() {
        assertEquals(
            setOf(
                ServiceConstants.KEY_NOTIFICATION_WHITELIST,
                RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE_APP_WHITELIST,
                MediaSessionOnlineFallbackPreferences.PACKAGES,
            ),
            StringSetPreferences.KEYS,
        )
    }
}
