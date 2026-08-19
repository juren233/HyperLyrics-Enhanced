/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleFallbackPlaybackPolicyTest {

    @Test
    fun `central Apple playback remains authoritative during fallback lyrics`() {
        assertTrue(
            shouldForwardCentralPlaybackState(
                hasActiveCentralPlayer = true,
                centralAppleProviderActive = true,
                fallbackSongActive = true,
            )
        )
    }

    @Test
    fun `playback callback is rejected without an active Central player`() {
        assertFalse(
            shouldForwardCentralPlaybackState(
                hasActiveCentralPlayer = false,
                centralAppleProviderActive = true,
                fallbackSongActive = true,
            )
        )
    }
}
