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
    fun `installed catalog puts Apple Music before NetEase and QQ Music`() {
        assertEquals(
            listOf("Apple Music", "网易云音乐", "QQ音乐"),
            OneTapRefreshCatalog.installedMusicApps(musicAppIds).map { it.displayName },
        )
    }
}
