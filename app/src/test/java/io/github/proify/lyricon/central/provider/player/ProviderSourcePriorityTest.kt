/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderSourcePriorityTest {
    @Test
    fun appleMusicUsesBuiltInPriority() {
        assertEquals(
            ProviderSourcePriority.BUILT_IN,
            ProviderSourcePriorityResolver.resolve(
                "com.juren233.hyperlyricsenhanced",
                "com.apple.android.music",
            ),
        )
    }

    @Test
    fun officialPackOutranksLegacyApk() {
        assertEquals(
            ProviderSourcePriority.OFFICIAL_PLUGIN,
            ProviderSourcePriorityResolver.resolve(
                "com.juren233.hyperlyricsenhanced.provider.kuwo",
                "cn.kuwo.player",
            ),
        )
        assertEquals(
            ProviderSourcePriority.LEGACY_APK,
            ProviderSourcePriorityResolver.resolve(
                "io.github.proify.lyricon.kwprovider",
                "cn.kuwo.player",
            ),
        )
    }

    @Test
    fun unknownOrMismatchedOfficialNamesCannotSpoofPluginPriority() {
        assertEquals(
            ProviderSourcePriority.LEGACY_APK,
            ProviderSourcePriorityResolver.resolve(
                "com.juren233.hyperlyricsenhanced.provider.unknown",
                "cn.kuwo.player",
            ),
        )
        assertEquals(
            ProviderSourcePriority.LEGACY_APK,
            ProviderSourcePriorityResolver.resolve(
                "com.juren233.hyperlyricsenhanced.provider.kuwo",
                "com.spotify.music",
            ),
        )
    }
}
