/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住超级岛内容渲染的作用域语义（2026-09-24 issue #39）：
 * 只有模块作用域内的音乐 App（含汽水音乐的系统媒体路径）允许进入
 * 岛内容管线（标题回退与锚点优先级都以此目录为准）。
 */
class IslandMusicAppCatalogTest {

    @Test
    fun `module managed music apps are supported`() {
        assertTrue(IslandMusicAppCatalog.isSupported("com.salt.music"))
        assertTrue(IslandMusicAppCatalog.isSupported("com.netease.cloudmusic"))
        assertTrue(IslandMusicAppCatalog.isSupported("com.apple.android.music"))
        assertTrue(IslandMusicAppCatalog.isSupported("com.luna.music"))
        assertTrue(IslandMusicAppCatalog.isSupported("com.tencent.qqmusic"))
        assertTrue(IslandMusicAppCatalog.isSupported("com.kugou.android"))
        assertTrue(IslandMusicAppCatalog.isSupported("cn.kuwo.player"))
        assertTrue(IslandMusicAppCatalog.isSupported("com.spotify.music"))
    }

    @Test
    fun `apps outside module scope are not supported`() {
        // issue #39：B 站后台播放曾把视频标题与 Up 主写进超级岛内容。
        assertFalse(IslandMusicAppCatalog.isSupported("tv.danmaku.bili"))
        assertFalse(IslandMusicAppCatalog.isSupported("com.ss.android.ugc.aweme"))
        assertFalse(IslandMusicAppCatalog.isSupported(null))
        assertFalse(IslandMusicAppCatalog.isSupported(""))
    }
}
