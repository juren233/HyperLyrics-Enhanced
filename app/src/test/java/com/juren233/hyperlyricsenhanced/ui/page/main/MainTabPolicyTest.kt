/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.main

import org.junit.Assert.assertEquals
import org.junit.Test

class MainTabPolicyTest {

    @Test
    fun `keeps home and apple music pages with about when all entries are enabled`() {
        assertEquals(
            listOf(MainTab.Home, MainTab.AppleMusic, MainTab.About),
            MainTabPolicy.tabs(
                hyperIslandEntryEnabled = true,
                aodLyricsEntryEnabled = true,
                dynamicIslandEntryEnabled = true,
                appleMusicEntryEnabled = true,
            ),
        )
    }

    @Test
    fun `apple music becomes the first page when all lyric entries are disabled`() {
        assertEquals(
            listOf(MainTab.AppleMusic, MainTab.About),
            MainTabPolicy.tabs(
                hyperIslandEntryEnabled = false,
                aodLyricsEntryEnabled = false,
                dynamicIslandEntryEnabled = false,
                appleMusicEntryEnabled = true,
            ),
        )
    }

    @Test
    fun `keeps home page with a single lyric entry`() {
        assertEquals(
            listOf(MainTab.Home, MainTab.About),
            MainTabPolicy.tabs(
                hyperIslandEntryEnabled = true,
                aodLyricsEntryEnabled = false,
                dynamicIslandEntryEnabled = false,
                appleMusicEntryEnabled = false,
            ),
        )
        assertEquals(
            listOf(MainTab.Home, MainTab.About),
            MainTabPolicy.tabs(
                hyperIslandEntryEnabled = false,
                aodLyricsEntryEnabled = true,
                dynamicIslandEntryEnabled = false,
                appleMusicEntryEnabled = false,
            ),
        )
    }

    @Test
    fun `dynamic island entry alone keeps the home page`() {
        assertEquals(
            listOf(MainTab.Home, MainTab.About),
            MainTabPolicy.tabs(
                hyperIslandEntryEnabled = false,
                aodLyricsEntryEnabled = false,
                dynamicIslandEntryEnabled = true,
                appleMusicEntryEnabled = false,
            ),
        )
        assertEquals(
            listOf(MainTab.Home, MainTab.AppleMusic, MainTab.About),
            MainTabPolicy.tabs(
                hyperIslandEntryEnabled = false,
                aodLyricsEntryEnabled = false,
                dynamicIslandEntryEnabled = true,
                appleMusicEntryEnabled = true,
            ),
        )
    }

    @Test
    fun `shows only the guidance page when every feature switch is disabled`() {
        assertEquals(
            listOf(MainTab.Unsupported),
            MainTabPolicy.tabs(
                hyperIslandEntryEnabled = false,
                aodLyricsEntryEnabled = false,
                dynamicIslandEntryEnabled = false,
                appleMusicEntryEnabled = false,
            ),
        )
    }

    @Test
    fun `home page stays visible while at least one lyric entry is enabled`() {
        assertEquals(
            true,
            MainTabPolicy.isHomePageVisible(
                hyperIslandEntryEnabled = true,
                aodLyricsEntryEnabled = true,
                dynamicIslandEntryEnabled = true,
            ),
        )
        assertEquals(
            true,
            MainTabPolicy.isHomePageVisible(
                hyperIslandEntryEnabled = true,
                aodLyricsEntryEnabled = false,
                dynamicIslandEntryEnabled = false,
            ),
        )
        assertEquals(
            true,
            MainTabPolicy.isHomePageVisible(
                hyperIslandEntryEnabled = false,
                aodLyricsEntryEnabled = true,
                dynamicIslandEntryEnabled = false,
            ),
        )
        assertEquals(
            true,
            MainTabPolicy.isHomePageVisible(
                hyperIslandEntryEnabled = false,
                aodLyricsEntryEnabled = false,
                dynamicIslandEntryEnabled = true,
            ),
        )
    }

    @Test
    fun `home page content only moves into settings when all lyric entries are disabled`() {
        assertEquals(
            false,
            MainTabPolicy.isHomePageVisible(
                hyperIslandEntryEnabled = false,
                aodLyricsEntryEnabled = false,
                dynamicIslandEntryEnabled = false,
            ),
        )
    }
}
