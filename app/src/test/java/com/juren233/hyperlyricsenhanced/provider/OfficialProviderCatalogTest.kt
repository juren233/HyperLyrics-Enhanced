/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialProviderCatalogTest {

    @Test
    fun `uses canonical provider display names without spaces`() {
        assertEquals("QQ音乐", OfficialProviderCatalog.definitionForId("qqmusic")?.displayName)
        assertEquals("QQ音乐HD", OfficialProviderCatalog.definitionForId("qqmusic-hd")?.displayName)
        assertEquals("LX音乐", OfficialProviderCatalog.definitionForId("lxmusic")?.displayName)
        assertEquals("椒盐音乐", OfficialProviderCatalog.definitionForId("salt-player")?.displayName)
    }

    @Test
    fun `shows exact supported Salt Player versions without feature switch instructions`() {
        assertEquals(
            "已适配版本：11.1.0、12.0.0、12.1.0、12.1.1",
            OfficialProviderCatalog.definitionForId("salt-player")?.description,
        )
    }

    @Test
    fun `allows QQ Music playback service process`() {
        assertTrue(
            OfficialProviderCatalog.shouldLoadIntoProcess(
                packageName = "com.tencent.qqmusic",
                processName = "com.tencent.qqmusic:QQPlayerService",
            )
        )
    }

    @Test
    fun `rejects undeclared QQ Music secondary processes`() {
        assertFalse(
            OfficialProviderCatalog.shouldLoadIntoProcess(
                packageName = "com.tencent.qqmusic",
                processName = "com.tencent.qqmusic:push",
            )
        )
    }

    @Test
    fun `keeps non-provider secondary processes filtered`() {
        assertFalse(
            OfficialProviderCatalog.shouldLoadIntoProcess(
                packageName = "com.example.music",
                processName = "com.example.music:player",
            )
        )
    }

    @Test
    fun `keeps main processes enabled`() {
        assertTrue(
            OfficialProviderCatalog.shouldLoadIntoProcess(
                packageName = "com.tencent.qqmusic",
                processName = "com.tencent.qqmusic",
            )
        )
    }

    @Test
    fun `validates provider package against its declared player packages`() {
        assertTrue(
            OfficialProviderCatalog.isOfficialProviderPair(
                providerPackageName = "com.juren233.hyperlyricsenhanced.provider.qqmusic",
                playerPackageName = "com.tencent.qqmusic",
            )
        )
        assertFalse(
            OfficialProviderCatalog.isOfficialProviderPair(
                providerPackageName = "com.juren233.hyperlyricsenhanced.provider.qqmusic",
                playerPackageName = "com.netease.cloudmusic",
            )
        )
    }
}
