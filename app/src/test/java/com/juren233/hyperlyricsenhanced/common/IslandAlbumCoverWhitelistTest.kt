/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandAlbumCoverWhitelistTest {
    private val supportedPackages = setOf("music.primary", "music.secondary")

    @Test
    fun `missing preference enables every supported app`() {
        assertTrue(
            IslandAlbumCoverWhitelist.isEnabled(
                enabledPackages = null,
                packageName = "music.primary",
                supportedPackages = supportedPackages,
            )
        )
        assertFalse(
            IslandAlbumCoverWhitelist.isEnabled(
                enabledPackages = null,
                packageName = "other.app",
                supportedPackages = supportedPackages,
            )
        )
    }

    @Test
    fun `stored whitelist can enable an app outside the built in music catalog`() {
        assertTrue(
            IslandAlbumCoverWhitelist.isEnabled(
                enabledPackages = setOf("other.app"),
                packageName = "other.app",
                supportedPackages = supportedPackages,
            )
        )
        assertFalse(
            IslandAlbumCoverWhitelist.isEnabled(
                enabledPackages = setOf("other.app"),
                packageName = "music.primary",
                supportedPackages = supportedPackages,
            )
        )
    }

    @Test
    fun `toggle starts from all apps enabled`() {
        val disabled = IslandAlbumCoverWhitelist.updateEnabledPackages(
            current = null,
            packageName = "music.primary",
            enabled = false,
            supportedPackages = supportedPackages,
        )

        assertEquals(setOf("music.secondary"), disabled)
    }

    @Test
    fun `enabling an app adds it back to the stored whitelist`() {
        assertEquals(
            setOf("music.primary", "music.secondary"),
            IslandAlbumCoverWhitelist.updateEnabledPackages(
                current = setOf("music.secondary"),
                packageName = "music.primary",
                enabled = true,
                supportedPackages = supportedPackages,
            )
        )
    }
}
