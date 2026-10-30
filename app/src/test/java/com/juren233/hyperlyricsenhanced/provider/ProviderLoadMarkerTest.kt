/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLoadMarkerTest {

    private val nowMs = 1_800_000_000_000L

    @Test
    fun encodeDecodeRoundTrip() {
        val state = ProviderLoadMarker.MarkerState(
            versionCode = 29,
            uncleanCount = 2,
            lastAttemptAtMs = nowMs,
        )

        assertEquals(state, ProviderLoadMarker.decode(ProviderLoadMarker.encode(state)))
    }

    @Test
    fun decodeRejectsMalformedValues() {
        assertNull(ProviderLoadMarker.decode(null))
        assertNull(ProviderLoadMarker.decode(""))
        assertNull(ProviderLoadMarker.decode("29"))
        assertNull(ProviderLoadMarker.decode("29:2"))
        assertNull(ProviderLoadMarker.decode("29:x:$nowMs"))
        assertNull(ProviderLoadMarker.decode("x:2:$nowMs"))
        assertNull(ProviderLoadMarker.decode("0:2:$nowMs"))
        assertNull(ProviderLoadMarker.decode("29:0:$nowMs"))
        assertNull(ProviderLoadMarker.decode("29:2:0"))
        // 第 4 段是写入标记的进程 pid（可选），必须是非负整数；超过 4 段一律无效。
        assertNull(ProviderLoadMarker.decode("29:2:${nowMs}:x"))
        assertNull(ProviderLoadMarker.decode("29:2:${nowMs}:-1"))
        assertNull(ProviderLoadMarker.decode("29:2:${nowMs}:1:2"))
    }

    @Test
    fun firstLoadOfVersionStartsCountingAtOne() {
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = null,
            previousVersionCode = 28,
            nowMs = nowMs,
        )

        assertTrue(decision is ProviderLoadMarker.Decision.LoadNormally)
        decision as ProviderLoadMarker.Decision.LoadNormally
        assertEquals(29, decision.versionCode)
        assertEquals(
            ProviderLoadMarker.MarkerState(29, 1, nowMs),
            decision.newMarker,
        )
    }

    @Test
    fun staleMarkerOfOlderVersionRestartsCounting() {
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 30,
            marker = ProviderLoadMarker.MarkerState(29, 2, nowMs),
            previousVersionCode = 28,
            nowMs = nowMs,
        )

        assertTrue(decision is ProviderLoadMarker.Decision.LoadNormally)
        decision as ProviderLoadMarker.Decision.LoadNormally
        assertEquals(ProviderLoadMarker.MarkerState(30, 1, nowMs), decision.newMarker)
    }

    @Test
    fun uncleanRelaunchIncrementsCount() {
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = ProviderLoadMarker.MarkerState(29, 1, nowMs - 60_000L),
            previousVersionCode = 28,
            nowMs = nowMs,
        )

        assertTrue(decision is ProviderLoadMarker.Decision.LoadNormally)
        decision as ProviderLoadMarker.Decision.LoadNormally
        assertEquals(ProviderLoadMarker.MarkerState(29, 2, nowMs), decision.newMarker)
    }

    @Test
    fun rollsBackAfterMaxStrikesWithPreviousVersion() {
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = ProviderLoadMarker.MarkerState(29, 2, nowMs - 60_000L),
            previousVersionCode = 28,
            nowMs = nowMs,
        )

        assertTrue(decision is ProviderLoadMarker.Decision.Rollback)
        decision as ProviderLoadMarker.Decision.Rollback
        assertEquals(29, decision.badVersionCode)
        assertEquals(28, decision.fallbackVersionCode)
    }

    @Test
    fun rollbackWithoutPreviousVersionSkipsPlugin() {
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = ProviderLoadMarker.MarkerState(29, 2, nowMs - 60_000L),
            previousVersionCode = 0,
            nowMs = nowMs,
        )

        assertTrue(decision is ProviderLoadMarker.Decision.Rollback)
        decision as ProviderLoadMarker.Decision.Rollback
        assertNull(decision.fallbackVersionCode)
    }

    @Test
    fun customStrikeLimitIsRespected() {
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = ProviderLoadMarker.MarkerState(29, 1, nowMs - 60_000L),
            previousVersionCode = 28,
            maxStrikes = 1,
            nowMs = nowMs,
        )

        assertTrue(decision is ProviderLoadMarker.Decision.Rollback)
    }

    @Test
    fun decayedStrikesRestartCountingInsteadOfRollingBack() {
        // 两次异常发生在很久以前（超过衰减窗口）：重装同版本不应直接回退。
        val staleAt = nowMs - ProviderLoadMarker.STRIKE_DECAY_MS - 1
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = ProviderLoadMarker.MarkerState(29, 2, staleAt),
            previousVersionCode = 28,
            nowMs = nowMs,
        )

        assertTrue(decision is ProviderLoadMarker.Decision.LoadNormally)
        decision as ProviderLoadMarker.Decision.LoadNormally
        assertEquals(ProviderLoadMarker.MarkerState(29, 1, nowMs), decision.newMarker)
    }

    @Test
    fun freshStrikeAfterDecayCountsFromOne() {
        val staleAt = nowMs - ProviderLoadMarker.STRIKE_DECAY_MS - 1
        val first = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = ProviderLoadMarker.MarkerState(29, 2, staleAt),
            previousVersionCode = 28,
            nowMs = nowMs,
        ) as ProviderLoadMarker.Decision.LoadNormally
        val second = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = first.newMarker,
            previousVersionCode = 28,
            nowMs = nowMs + 60_000L,
        )

        // 衰减后重新计 1，本次再异常升到 2，仍未达回退阈值（阈值 2 会在下一次判定触发）。
        assertEquals(1, first.newMarker.uncleanCount)
        assertTrue(second is ProviderLoadMarker.Decision.LoadNormally)
        assertEquals(2, (second as ProviderLoadMarker.Decision.LoadNormally).newMarker.uncleanCount)
    }

    @Test
    fun recordedBadVersionStaysRolledBackAfterMarkerDecay() {
        val staleAt = nowMs - ProviderLoadMarker.STRIKE_DECAY_MS - 1
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = ProviderLoadMarker.MarkerState(29, 2, staleAt),
            previousVersionCode = 28,
            badVersionCodes = setOf(29),
            nowMs = nowMs,
        )

        assertEquals(ProviderLoadMarker.Decision.Rollback(29, 28), decision)
    }

    @Test
    fun recordedBadVersionRollsBackWithoutMarker() {
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = null,
            previousVersionCode = 28,
            badVersionCodes = setOf(29),
            nowMs = nowMs,
        )

        assertEquals(ProviderLoadMarker.Decision.Rollback(29, 28), decision)
    }

    @Test
    fun badPreviousVersionIsNotUsedAsFallback() {
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 28,
            marker = ProviderLoadMarker.MarkerState(28, 2, nowMs),
            previousVersionCode = 29,
            badVersionCodes = setOf(29),
            nowMs = nowMs,
        )

        assertEquals(ProviderLoadMarker.Decision.Rollback(28, null), decision)
    }

    @Test
    fun markerWithPidRoundTripsAndLegacyFormatStillDecodes() {
        val withPid = ProviderLoadMarker.MarkerState(15, 1, nowMs, pid = 4321)
        assertEquals("15:1:$nowMs:4321", ProviderLoadMarker.encode(withPid))
        assertEquals(withPid, ProviderLoadMarker.decode("15:1:$nowMs:4321"))
        assertEquals(
            ProviderLoadMarker.MarkerState(15, 1, nowMs, pid = 0),
            ProviderLoadMarker.decode("15:1:$nowMs"),
        )
    }

    @Test
    fun userKillsBetweenLoadsNeverRollBack() {
        // 标记计数 2 且已达上限，但上一次退出不是崩溃（划掉/强停/系统回收）：不回退，计数不增长。
        val marker = ProviderLoadMarker.MarkerState(15, 2, nowMs - 1_000, pid = 99)
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 15,
            marker = marker,
            previousVersionCode = 0,
            previousExitWasCrash = false,
            nowMs = nowMs,
        ) as ProviderLoadMarker.Decision.LoadNormally
        assertEquals(2, decision.newMarker.uncleanCount)
    }

    @Test
    fun singleLoadKilledByUserStartsOverAtOne() {
        val marker = ProviderLoadMarker.MarkerState(15, 1, nowMs - 1_000, pid = 99)
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 15,
            marker = marker,
            previousVersionCode = 0,
            previousExitWasCrash = false,
            nowMs = nowMs,
        ) as ProviderLoadMarker.Decision.LoadNormally
        assertEquals(1, decision.newMarker.uncleanCount)
    }

    @Test
    fun realCrashesStillRollBackAtTheLimit() {
        val marker = ProviderLoadMarker.MarkerState(15, ProviderLoadMarker.DEFAULT_MAX_STRIKES, nowMs - 1_000, pid = 99)
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 15,
            marker = marker,
            previousVersionCode = 14,
            previousExitWasCrash = true,
            nowMs = nowMs,
        )
        assertTrue(decision is ProviderLoadMarker.Decision.Rollback)
    }

    @Test
    fun onlyCrashExitReasonsCount() {
        assertTrue(ProviderProcessExit.isCrashReason(android.app.ApplicationExitInfo.REASON_CRASH))
        assertTrue(ProviderProcessExit.isCrashReason(android.app.ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals(false, ProviderProcessExit.isCrashReason(android.app.ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals(false, ProviderProcessExit.isCrashReason(android.app.ApplicationExitInfo.REASON_OTHER))
        assertEquals(false, ProviderProcessExit.isCrashReason(android.app.ApplicationExitInfo.REASON_LOW_MEMORY))
    }
}
