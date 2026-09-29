/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandSelfHealGuardPolicyTest {

    private val now = 1_000_000L
    private val kuwoKey = "0|cn.kuwo.player|67331|null|10466"
    private val appleKey = "0|com.apple.android.music|1001|null|10361"
    @Test
    fun `package name is extracted from notification key`() {
        assertEquals("cn.kuwo.player", IslandSelfHealGuardPolicy.packageNameOfIslandKey(kuwoKey))
        assertEquals(
            "com.apple.android.music",
            IslandSelfHealGuardPolicy.packageNameOfIslandKey(appleKey),
        )
    }

    @Test
    fun `malformed island keys yield no package`() {
        assertNull(IslandSelfHealGuardPolicy.packageNameOfIslandKey(null))
        assertNull(IslandSelfHealGuardPolicy.packageNameOfIslandKey(""))
        assertNull(IslandSelfHealGuardPolicy.packageNameOfIslandKey("0|"))
        assertNull(IslandSelfHealGuardPolicy.packageNameOfIslandKey("cn.kuwo.player"))
    }

    @Test
    fun `force delete of playing lyric package island is vetoed`() {
        assertTrue(
            IslandSelfHealGuardPolicy.shouldVetoForceDelete(
                flaggedIslandKey = kuwoKey,
                lyricPackageName = "cn.kuwo.player",
                playbackActive = true,
                lastLegitRemoveKey = null,
                lastLegitRemoveAtMs = 0L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `no veto when playback is inactive`() {
        assertFalse(
            IslandSelfHealGuardPolicy.shouldVetoForceDelete(
                flaggedIslandKey = kuwoKey,
                lyricPackageName = "cn.kuwo.player",
                playbackActive = false,
                lastLegitRemoveKey = null,
                lastLegitRemoveAtMs = 0L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `no veto for island key of another package`() {
        assertFalse(
            IslandSelfHealGuardPolicy.shouldVetoForceDelete(
                flaggedIslandKey = appleKey,
                lyricPackageName = "cn.kuwo.player",
                playbackActive = true,
                lastLegitRemoveKey = null,
                lastLegitRemoveAtMs = 0L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `no veto when lyric package or key is missing`() {
        assertFalse(
            IslandSelfHealGuardPolicy.shouldVetoForceDelete(
                flaggedIslandKey = null,
                lyricPackageName = "cn.kuwo.player",
                playbackActive = true,
                lastLegitRemoveKey = null,
                lastLegitRemoveAtMs = 0L,
                nowMs = now,
            ),
        )
        assertFalse(
            IslandSelfHealGuardPolicy.shouldVetoForceDelete(
                flaggedIslandKey = kuwoKey,
                lyricPackageName = null,
                playbackActive = true,
                lastLegitRemoveKey = null,
                lastLegitRemoveAtMs = 0L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `legit removal of same key within window blocks the veto`() {
        assertFalse(
            IslandSelfHealGuardPolicy.shouldVetoForceDelete(
                flaggedIslandKey = kuwoKey,
                lyricPackageName = "cn.kuwo.player",
                playbackActive = true,
                lastLegitRemoveKey = kuwoKey,
                lastLegitRemoveAtMs = now - 2_000L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `legit removal of another package does not block the veto`() {
        // 2026-09-29 09:14 复现场景：Apple→酷我交接时 Apple 岛被合法移除 1.8 秒后，
        // checkError 误删酷我岛——两个 key 不同，守卫必须生效。
        assertTrue(
            IslandSelfHealGuardPolicy.shouldVetoForceDelete(
                flaggedIslandKey = kuwoKey,
                lyricPackageName = "cn.kuwo.player",
                playbackActive = true,
                lastLegitRemoveKey = appleKey,
                lastLegitRemoveAtMs = now - 1_800L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `expired legit removal marker does not block the veto`() {
        assertTrue(
            IslandSelfHealGuardPolicy.shouldVetoForceDelete(
                flaggedIslandKey = kuwoKey,
                lyricPackageName = "cn.kuwo.player",
                playbackActive = true,
                lastLegitRemoveKey = kuwoKey,
                lastLegitRemoveAtMs = now - IslandSelfHealGuardPolicy.LEGIT_REMOVE_WINDOW_MS - 1L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `recovery requires no legit removal of same key within recovery block window`() {
        // 容器被误拆（同 key 无合法移除）→ 允许重建。
        assertTrue(
            IslandSelfHealGuardPolicy.shouldRecoverAfterDetach(
                detachedIslandKey = kuwoKey,
                lyricPackageName = "cn.kuwo.player",
                playbackActive = true,
                lastLegitRemoveKey = appleKey,
                lastLegitRemoveAtMs = now - 1_000L,
                nowMs = now,
            ),
        )
        // 用户主动划掉（同 key 合法移除）30 秒内 → 不重建，尊重用户意图。
        assertFalse(
            IslandSelfHealGuardPolicy.shouldRecoverAfterDetach(
                detachedIslandKey = kuwoKey,
                lyricPackageName = "cn.kuwo.player",
                playbackActive = true,
                lastLegitRemoveKey = kuwoKey,
                lastLegitRemoveAtMs = now - 10_000L,
                nowMs = now,
            ),
        )
        // 同 key 合法移除已超过 30 秒 → 允许重建。
        assertTrue(
            IslandSelfHealGuardPolicy.shouldRecoverAfterDetach(
                detachedIslandKey = kuwoKey,
                lyricPackageName = "cn.kuwo.player",
                playbackActive = true,
                lastLegitRemoveKey = kuwoKey,
                lastLegitRemoveAtMs = now - IslandSelfHealGuardPolicy.LEGIT_REMOVE_RECOVERY_BLOCK_MS - 1L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `rebuild throttle and attempt cap`() {
        val key = kuwoKey
        // 新 key 首次尝试放行。
        assertTrue(
            IslandSelfHealGuardPolicy.shouldAttemptRebuild(
                rebuildKey = key,
                lastRebuildKey = null,
                lastRebuildAtMs = 0L,
                consecutiveAttempts = 0,
                nowMs = now,
            ),
        )
        // 同 key 节流窗口内不放行。
        assertFalse(
            IslandSelfHealGuardPolicy.shouldAttemptRebuild(
                rebuildKey = key,
                lastRebuildKey = key,
                lastRebuildAtMs = now - 1_000L,
                consecutiveAttempts = 1,
                nowMs = now,
            ),
        )
        // 同 key 节流期满、未达上限放行。
        assertTrue(
            IslandSelfHealGuardPolicy.shouldAttemptRebuild(
                rebuildKey = key,
                lastRebuildKey = key,
                lastRebuildAtMs = now - IslandSelfHealGuardPolicy.REBUILD_THROTTLE_MS - 1L,
                consecutiveAttempts = IslandSelfHealGuardPolicy.MAX_REBUILD_ATTEMPTS - 1,
                nowMs = now,
            ),
        )
        // 同 key 次数用尽不放行。
        assertFalse(
            IslandSelfHealGuardPolicy.shouldAttemptRebuild(
                rebuildKey = key,
                lastRebuildKey = key,
                lastRebuildAtMs = now - IslandSelfHealGuardPolicy.REBUILD_THROTTLE_MS - 1L,
                consecutiveAttempts = IslandSelfHealGuardPolicy.MAX_REBUILD_ATTEMPTS,
                nowMs = now,
            ),
        )
    }
}
