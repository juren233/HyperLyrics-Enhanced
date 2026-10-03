/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.main

/**
 * 主页面（MainPage）中实际保留的页面。
 *
 * 主页与 Apple Music 体验优化页分别由“功能开关”中的歌词入口与 Apple Music 入口控制，
 * 关于页在仍存在任一功能页面时保留；全部功能开关关闭时只显示引导页（不支持设备页）。
 */
enum class MainTab {
    Home,
    AppleMusic,
    About,
    Unsupported,
}

object MainTabPolicy {

    /**
     * 主页是否保留：三个歌词入口（米系超级岛歌词、米系息屏歌词、通知型灵动岛歌词）至少有一个开启。
     *
     * 通知型灵动岛歌词入口开启时主页同样保留，其开关正常显示在主页上，不收进设置页；
     * 主页被隐藏时，主页上的歌词设置与特殊功能才迁到设置页顶部。
     */
    fun isHomePageVisible(
        hyperIslandEntryEnabled: Boolean,
        aodLyricsEntryEnabled: Boolean,
        dynamicIslandEntryEnabled: Boolean,
    ): Boolean = hyperIslandEntryEnabled || aodLyricsEntryEnabled || dynamicIslandEntryEnabled

    fun tabs(
        hyperIslandEntryEnabled: Boolean,
        aodLyricsEntryEnabled: Boolean,
        dynamicIslandEntryEnabled: Boolean,
        appleMusicEntryEnabled: Boolean,
    ): List<MainTab> {
        val showHome = isHomePageVisible(
            hyperIslandEntryEnabled = hyperIslandEntryEnabled,
            aodLyricsEntryEnabled = aodLyricsEntryEnabled,
            dynamicIslandEntryEnabled = dynamicIslandEntryEnabled,
        )
        val showAppleMusic = appleMusicEntryEnabled
        if (!showHome && !showAppleMusic) {
            // 全部功能开关（含 Apple Music 体验优化入口）都关闭时才显示不支持设备页。
            return listOf(MainTab.Unsupported)
        }
        return buildList {
            if (showHome) add(MainTab.Home)
            if (showAppleMusic) add(MainTab.AppleMusic)
            add(MainTab.About)
        }
    }
}
