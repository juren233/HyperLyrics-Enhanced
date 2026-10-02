/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandPromotedMediaGatePolicyTest {
    @Test
    fun `playing media notification of current lyric package is promoted`() {
        assertTrue(
            IslandPromotedMediaGatePolicy.shouldPromote(
                sbnIsMediaNotification = true,
                sbnPackageName = "com.apple.android.music",
                lyricPackageName = "com.apple.android.music",
                playbackActive = true,
            ),
        )
    }

    @Test
    fun `non media notification is never promoted`() {
        assertFalse(
            IslandPromotedMediaGatePolicy.shouldPromote(
                sbnIsMediaNotification = false,
                sbnPackageName = "com.apple.android.music",
                lyricPackageName = "com.apple.android.music",
                playbackActive = true,
            ),
        )
    }

    @Test
    fun `paused playback is never promoted`() {
        // 暂停时原生的收岛/门禁是正常行为，不得放行。
        assertFalse(
            IslandPromotedMediaGatePolicy.shouldPromote(
                sbnIsMediaNotification = true,
                sbnPackageName = "com.apple.android.music",
                lyricPackageName = "com.apple.android.music",
                playbackActive = false,
            ),
        )
    }

    @Test
    fun `media notification of another package is never promoted`() {
        // 双播时非活跃方（如后台酷我）的通知不得放行，避免抢岛。
        assertFalse(
            IslandPromotedMediaGatePolicy.shouldPromote(
                sbnIsMediaNotification = true,
                sbnPackageName = "cn.kuwo.player",
                lyricPackageName = "com.apple.android.music",
                playbackActive = true,
            ),
        )
    }

    @Test
    fun `missing lyric package is never promoted`() {
        assertFalse(
            IslandPromotedMediaGatePolicy.shouldPromote(
                sbnIsMediaNotification = true,
                sbnPackageName = "cn.kuwo.player",
                lyricPackageName = null,
                playbackActive = true,
            ),
        )
        assertFalse(
            IslandPromotedMediaGatePolicy.shouldPromote(
                sbnIsMediaNotification = true,
                sbnPackageName = "cn.kuwo.player",
                lyricPackageName = "",
                playbackActive = true,
            ),
        )
    }
}
