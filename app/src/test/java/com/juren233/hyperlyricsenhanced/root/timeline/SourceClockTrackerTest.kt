/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceClockTrackerTest {

    // 与真机一致：elapsedRealtime 远大于 TTL，「外推起点 0」自然视为过期。
    private val t0 = 10_000_000L

    @Test
    fun `projects at real time while playing and expires after ttl`() {
        val tracker = SourceClockTracker(ttlMs = 15_000L)
        tracker.accept(10_000L, explicitSeek = false, nowMs = t0 + 1_000L, playing = true)
        assertEquals(10_500L, tracker.projected(t0 + 1_500L, playing = true))
        assertEquals(10_000L, tracker.projected(t0 + 1_500L, playing = false))
        assertNull(tracker.projected(t0 + 16_001L, playing = true))
    }

    @Test
    fun `persistent small backtrack re-anchors after one second`() {
        val tracker = SourceClockTracker()
        tracker.accept(10_000L, explicitSeek = false, nowMs = t0 + 0L, playing = true)
        val first = tracker.accept(9_100L, explicitSeek = false, nowMs = t0 + 100L, playing = true)
        assertEquals(SourceClockUpdatePolicy.Action.IGNORE_BACKTRACK, first.action)
        assertEquals(10_000L, tracker.anchorPosition)
        val second = tracker.accept(10_900L, explicitSeek = false, nowMs = t0 + 1_100L, playing = true)
        assertEquals(SourceClockUpdatePolicy.Action.ANCHOR, second.action)
        assertEquals(1_000L, second.endedBacktrackStreakMs)
        assertEquals(10_900L, tracker.anchorPosition)
        assertEquals(0L, tracker.backtrackSinceMs)
    }

    @Test
    fun `pause stops projecting and resume restarts paused samples from now`() {
        val tracker = SourceClockTracker()
        tracker.accept(10_000L, explicitSeek = false, nowMs = t0 + 0L, playing = true)
        tracker.stopProjecting()
        assertNull(tracker.projected(t0 + 500L, playing = true))
        // 没有暂停期间的样本：恢复时不凭空外推，等下一个源样本（与抽取前的驱动一致）。
        tracker.retimeOnResume(t0 + 1_000L)
        assertNull(tracker.projected(t0 + 1_200L, playing = true))
        // 暂停期间收到的冻结样本：恢复播放从恢复时刻起外推，暂停时长不计入。
        tracker.accept(10_050L, explicitSeek = false, nowMs = t0 + 2_000L, playing = false)
        tracker.retimeOnResume(t0 + 60_000L)
        assertEquals(10_250L, tracker.projected(t0 + 60_200L, playing = true))
        tracker.reset()
        assertNull(tracker.anchorPosition)
        assertEquals(0L, tracker.sampleAtMs)
    }
}
