/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextTrackMetadataCacheTest {
    @Test
    fun `official next-track frames follow Pack enabled state`() {
        val providerPackageName =
            "com.juren233.hyperlyricsenhanced.provider.salt-player"
        val playerPackageName = "com.salt.music"

        assertTrue(
            NextTrackMetadataCache.isProviderAccepted(
                providerPackageName,
                playerPackageName,
                officialProviderPreference = true,
            ),
        )
        assertFalse(
            NextTrackMetadataCache.isProviderAccepted(
                providerPackageName,
                playerPackageName,
                officialProviderPreference = false,
            ),
        )
        assertTrue(
            NextTrackMetadataCache.isProviderAccepted(
                providerPackageName,
                playerPackageName,
                officialProviderPreference = null,
            ),
        )
    }

    @Test
    fun `legacy or mismatched Provider cannot use official next-track channel`() {
        assertFalse(
            NextTrackMetadataCache.isProviderAccepted(
                "io.github.proify.lyricon.saltprovider",
                "com.salt.music",
                officialProviderPreference = true,
            ),
        )
        assertFalse(
            NextTrackMetadataCache.isProviderAccepted(
                "com.juren233.hyperlyricsenhanced.provider.salt-player",
                "cn.kuwo.player",
                officialProviderPreference = true,
            ),
        )
    }
}
