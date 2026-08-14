/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleDirectSongRecoveryPolicyTest {
    private val apple = "com.apple.android.music"
    private val builtIn = "com.juren233.hyperlyricsenhanced"

    @Test
    fun `direct song is accepted without an active Central player`() {
        assertTrue(resolve(player = null, provider = null, centralSongAvailable = false))
    }

    @Test
    fun `direct song fills an empty built-in Apple Central snapshot`() {
        assertTrue(resolve(player = apple, provider = builtIn, centralSongAvailable = false))
    }

    @Test
    fun `Central song remains authoritative once lyrics are available`() {
        assertFalse(resolve(player = apple, provider = builtIn, centralSongAvailable = true))
    }

    @Test
    fun `direct song never overrides another player or external Apple provider`() {
        assertFalse(resolve(player = "com.spotify.music", provider = builtIn, false))
        assertFalse(resolve(player = apple, provider = "external.provider", false))
    }

    private fun resolve(
        player: String?,
        provider: String?,
        centralSongAvailable: Boolean,
    ): Boolean = AppleDirectSongRecoveryPolicy.shouldAccept(
        activePlayerPackage = player,
        activeProviderPackage = provider,
        centralSongAvailable = centralSongAvailable,
        appleMusicPackage = apple,
        builtInProviderPackage = builtIn,
    )
}
