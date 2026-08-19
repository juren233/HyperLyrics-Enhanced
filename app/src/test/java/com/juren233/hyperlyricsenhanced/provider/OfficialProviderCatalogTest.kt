/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialProviderCatalogTest {

    @Test
    fun `uses canonical provider display names without spaces`() {
        assertEquals("QQ音乐", OfficialProviderCatalog.definitionForId("qqmusic")?.displayName)
        assertNull(OfficialProviderCatalog.definitionForId("qqmusic-hd"))
        assertEquals("LX音乐", OfficialProviderCatalog.definitionForId("lxmusic")?.displayName)
        assertEquals("椒盐音乐", OfficialProviderCatalog.definitionForId("salt-player")?.displayName)
    }

    @Test
    fun `merges QQ Music mobile and HD into one provider`() {
        val definition = requireNotNull(OfficialProviderCatalog.definitionForId("qqmusic"))

        assertEquals(
            setOf("com.tencent.qqmusic", "com.tencent.qqmusicpad"),
            definition.targetPackages,
        )
        assertEquals("QQ音乐", definition.displayNameForPackage("com.tencent.qqmusic"))
        assertEquals("QQ音乐HD", definition.displayNameForPackage("com.tencent.qqmusicpad"))
    }

    @Test
    fun `does not show a Salt Player supported version description`() {
        assertNull(OfficialProviderCatalog.definitionForId("salt-player")?.description)
    }

    @Test
    fun `hides discontinued entries only from the download list`() {
        val hiddenIds = OfficialProviderCatalog.definitions
            .filterNot(OfficialProviderCatalog.Definition::showInDownloadList)
            .mapTo(linkedSetOf(), OfficialProviderCatalog.Definition::id)

        assertEquals(
            setOf("lxmusic", "poweramp", "musicfree", "gramophone", "symfonium"),
            hiddenIds,
        )
        assertTrue(OfficialProviderCatalog.shouldShowInDownloadList("salt-player"))
        assertFalse(OfficialProviderCatalog.shouldShowInDownloadList("lxmusic"))
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
    fun `runs QQ Music HD only in its main process`() {
        assertTrue(
            OfficialProviderCatalog.shouldLoadIntoProcess(
                packageName = "com.tencent.qqmusicpad",
                processName = "com.tencent.qqmusicpad",
            )
        )
        assertFalse(
            OfficialProviderCatalog.shouldLoadIntoProcess(
                packageName = "com.tencent.qqmusicpad",
                processName = "com.tencent.qqmusicpad:QQPlayerService",
            )
        )
    }

    @Test
    fun `allows both KuGou support processes`() {
        assertTrue(
            OfficialProviderCatalog.shouldLoadIntoProcess(
                packageName = "com.kugou.android",
                processName = "com.kugou.android.support",
            )
        )
        assertTrue(
            OfficialProviderCatalog.shouldLoadIntoProcess(
                packageName = "com.kugou.android.lite",
                processName = "com.kugou.android.lite.support",
            )
        )
    }

    @Test
    fun `distinguishes KuGou full and concept app display names`() {
        val definition = requireNotNull(OfficialProviderCatalog.definitionForId("kugou"))

        assertEquals("酷狗音乐", definition.displayNameForPackage("com.kugou.android"))
        assertEquals("酷狗概念版", definition.displayNameForPackage("com.kugou.android.lite"))
    }

    @Test
    fun `rejects undeclared fully qualified secondary processes`() {
        assertFalse(
            OfficialProviderCatalog.shouldLoadIntoProcess(
                packageName = "com.kugou.android",
                processName = "com.kugou.android.message",
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
    fun `keeps core host processes enabled`() {
        assertTrue(
            OfficialProviderCatalog.shouldLoadIntoProcess(
                packageName = "com.android.systemui",
                processName = "com.android.systemui",
            ),
        )
        assertTrue(
            OfficialProviderCatalog.shouldLoadIntoProcess(
                packageName = "miui.systemui.plugin",
                processName = "miui.systemui.plugin",
            ),
        )
        assertTrue(
            OfficialProviderCatalog.shouldLoadIntoProcess(
                packageName = "com.apple.android.music",
                processName = "com.apple.android.music",
            ),
        )
    }

    @Test
    fun `runs Qishui only as a SystemUI media provider`() {
        val qishui = requireNotNull(OfficialProviderCatalog.definitionForId("qishui"))

        assertTrue(qishui.systemMediaRuntime)
        assertFalse(qishui.supportsNextTrackPreview)
        assertFalse(
            OfficialProviderCatalog.shouldLoadIntoProcess(
                packageName = "com.luna.music",
                processName = "com.luna.music",
            ),
        )
        assertFalse(OfficialProviderCatalog.supportsNextTrackPreview("com.luna.music"))
    }

    @Test
    fun `does not declare Qishui in the static Xposed scope`() {
        val scopes = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("META-INF/xposed/scope.list"),
        ).bufferedReader().useLines { it.toSet() }

        assertFalse("com.luna.music" in scopes)
    }

    @Test
    fun `validates provider package against its declared player packages`() {
        assertTrue(
            OfficialProviderCatalog.isOfficialProviderPair(
                providerPackageName = "com.juren233.hyperlyricsenhanced.provider.qqmusic",
                playerPackageName = "com.tencent.qqmusic",
            )
        )
        assertTrue(
            OfficialProviderCatalog.isOfficialProviderPair(
                providerPackageName = "com.juren233.hyperlyricsenhanced.provider.qqmusic",
                playerPackageName = "com.tencent.qqmusicpad",
            )
        )
        assertFalse(
            OfficialProviderCatalog.isOfficialProviderPair(
                providerPackageName = "com.juren233.hyperlyricsenhanced.provider.qqmusic",
                playerPackageName = "com.netease.cloudmusic",
            )
        )
    }

    @Test
    fun `OfficialProviderItem flags needsRepair correctly and suppresses updateAvailable when damaged`() {
        val entry = ProviderCatalogEntry(
            id = "qqmusic",
            displayName = "QQ音乐",
            targetPackages = listOf("com.tencent.qqmusic"),
            available = true,
            versionName = "1.0.10",
            versionCode = 11,
        )
        val normalItem = OfficialProviderItem(
            catalog = entry,
            installedVersionCode = 10,
            installedVersionName = "1.0.9",
            enabled = true,
            needsRepair = false,
        )
        assertTrue(normalItem.installed)
        assertTrue(normalItem.updateAvailable)
        assertFalse(normalItem.needsRepair)

        val damagedItem = OfficialProviderItem(
            catalog = entry,
            installedVersionCode = 10,
            installedVersionName = "1.0.9",
            enabled = true,
            needsRepair = true,
        )
        assertTrue(damagedItem.installed)
        assertFalse(damagedItem.updateAvailable)
        assertTrue(damagedItem.needsRepair)
    }
}
