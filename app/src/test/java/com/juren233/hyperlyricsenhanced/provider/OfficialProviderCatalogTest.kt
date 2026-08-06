/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialProviderCatalogTest {

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
}
