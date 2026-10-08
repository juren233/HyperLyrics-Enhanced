/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneTapRefreshSelectionPolicyTest {
    private val musicAppIds = setOf(
        "com.apple.android.music",
        "com.netease.cloudmusic",
        "com.tencent.qqmusic",
    )

    private val installedLabels = mapOf(
        "com.apple.android.music" to "Apple Music",
        "com.netease.cloudmusic" to "网易云音乐",
        "com.tencent.qqmusic" to "QQ音乐",
    )

    @Test
    fun `installed app labels are refreshed after phone language changes`() {
        val labels = mutableMapOf("com.miui.player" to "音乐")
        assertEquals("音乐", OneTapRefreshCatalog.installedMusicApps(labels::get).single().displayName)
        labels["com.miui.player"] = "Music"
        assertEquals("Music", OneTapRefreshCatalog.installedMusicApps(labels::get).single().displayName)
    }

    @Test
    fun `missing labels exclude absent apps and blank labels use package names`() {
        val apps = OneTapRefreshCatalog.installedMusicApps(
            mapOf("com.miui.player" to " ")::get,
        )
        assertEquals(listOf(OneTapRefreshMusicApp("com.miui.player", "com.miui.player")), apps)
    }

    @Test
    fun `identical labels keep package identity and both refresh targets`() {
        val apps = OneTapRefreshCatalog.installedMusicApps(
            mapOf("com.apple.android.music" to "Music", "com.miui.player" to "Music")::get,
        )
        assertEquals(2, apps.size)
        assertEquals(
            listOf("com.apple.android.music", "com.miui.player"),
            OneTapRefreshSelectionPolicy.selectedPackages(
                setOf(OneTapRefreshSelectionPolicy.ALL_MUSIC_APPS_ID), apps,
            ),
        )
    }

    @Test
    fun `all music apps cancels individual music app selections but preserves system ui`() {
        val selected = OneTapRefreshSelectionPolicy.toggle(
            selectedIds = setOf(
                OneTapRefreshSelectionPolicy.SYSTEM_UI_ID,
                "com.apple.android.music",
                "com.tencent.qqmusic",
            ),
            targetId = OneTapRefreshSelectionPolicy.ALL_MUSIC_APPS_ID,
            musicAppIds = musicAppIds,
        )

        assertEquals(
            setOf(
                OneTapRefreshSelectionPolicy.SYSTEM_UI_ID,
                OneTapRefreshSelectionPolicy.ALL_MUSIC_APPS_ID,
            ),
            selected,
        )
    }

    @Test
    fun `selecting an individual music app cancels all music apps`() {
        val selected = OneTapRefreshSelectionPolicy.toggle(
            selectedIds = setOf(
                OneTapRefreshSelectionPolicy.SYSTEM_UI_ID,
                OneTapRefreshSelectionPolicy.ALL_MUSIC_APPS_ID,
            ),
            targetId = "com.netease.cloudmusic",
            musicAppIds = musicAppIds,
        )

        assertFalse(OneTapRefreshSelectionPolicy.ALL_MUSIC_APPS_ID in selected)
        assertTrue(OneTapRefreshSelectionPolicy.SYSTEM_UI_ID in selected)
        assertTrue("com.netease.cloudmusic" in selected)
    }

    @Test
    fun `all music apps expands to every installed music package`() {
        val musicApps = listOf(
            OneTapRefreshMusicApp("com.apple.android.music", "Apple Music"),
            OneTapRefreshMusicApp("com.netease.cloudmusic", "网易云音乐"),
            OneTapRefreshMusicApp("com.tencent.qqmusic", "QQ音乐"),
        )

        assertEquals(
            listOf(
                OneTapRefreshSelectionPolicy.SYSTEM_UI_PACKAGE,
                "com.apple.android.music",
                "com.netease.cloudmusic",
                "com.tencent.qqmusic",
            ),
            OneTapRefreshSelectionPolicy.selectedPackages(
                selectedIds = setOf(
                    OneTapRefreshSelectionPolicy.SYSTEM_UI_ID,
                    OneTapRefreshSelectionPolicy.ALL_MUSIC_APPS_ID,
                ),
                musicApps = musicApps,
            ),
        )
    }

    @Test
    fun `installed catalog puts scoped apps in order and excludes apps outside module scope`() {
        assertEquals(
            listOf("Apple Music", "网易云音乐", "QQ音乐", "Music"),
            OneTapRefreshCatalog.installedMusicApps(
                (installedLabels + mapOf(
                    "com.miui.player" to "Music",
                    "com.google.android.apps.youtube.music" to "YouTube Music",
                ))::get,
            ).map { it.displayName },
        )
    }

    @Test
    fun `installed catalog distinguishes KuGou full and concept apps`() {
        assertEquals(
            listOf(
                OneTapRefreshMusicApp("com.kugou.android", "酷狗音乐"),
                OneTapRefreshMusicApp("com.kugou.android.lite", "酷狗概念版"),
            ),
            OneTapRefreshCatalog.installedMusicApps(
                mapOf(
                    "com.kugou.android" to "酷狗音乐",
                    "com.kugou.android.lite" to "酷狗概念版",
                )::get,
            ),
        )
    }

    @Test
    fun `home hidden keeps only system ui and apple music refresh targets`() {
        val installed = OneTapRefreshCatalog.installedMusicApps(installedLabels::get)

        val targets = OneTapRefreshCatalog.refreshTargets(
            installedMusicApps = installed,
            appleMusicOnly = true,
        )

        assertEquals(
            listOf(OneTapRefreshMusicApp("com.apple.android.music", "Apple Music")),
            targets.musicApps,
        )
        assertFalse(targets.showAllMusicAppsOption)
    }

    @Test
    fun `home visible keeps every installed music app with the all music apps option`() {
        val installed = OneTapRefreshCatalog.installedMusicApps(installedLabels::get)

        val targets = OneTapRefreshCatalog.refreshTargets(
            installedMusicApps = installed,
            appleMusicOnly = false,
        )

        assertEquals(installed, targets.musicApps)
        assertTrue(targets.showAllMusicAppsOption)
    }

    @Test
    fun `dialog defaults to system ui and all music apps`() {
        val targets = OneTapRefreshCatalog.refreshTargets(
            installedMusicApps = OneTapRefreshCatalog.installedMusicApps(installedLabels::get),
            appleMusicOnly = false,
        )

        assertEquals(
            setOf(
                OneTapRefreshSelectionPolicy.SYSTEM_UI_ID,
                OneTapRefreshSelectionPolicy.ALL_MUSIC_APPS_ID,
            ),
            OneTapRefreshSelectionPolicy.defaultSelection(targets),
        )
    }

    @Test
    fun `apple music only dialog defaults to system ui and apple music`() {
        val targets = OneTapRefreshCatalog.refreshTargets(
            installedMusicApps = OneTapRefreshCatalog.installedMusicApps(installedLabels::get),
            appleMusicOnly = true,
        )

        assertEquals(
            setOf(OneTapRefreshSelectionPolicy.SYSTEM_UI_ID, "com.apple.android.music"),
            OneTapRefreshSelectionPolicy.defaultSelection(targets),
        )
    }

    @Test
    fun `dialog defaults to system ui only without installed music apps`() {
        val targets = OneTapRefreshTargets(emptyList(), showAllMusicAppsOption = true)

        assertEquals(
            setOf(OneTapRefreshSelectionPolicy.SYSTEM_UI_ID),
            OneTapRefreshSelectionPolicy.defaultSelection(targets),
        )
    }
}
