/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudibleSessionSelectionPolicyTest {

    private data class Session(
        val key: String,
        val catalog: Boolean,
        val audible: Boolean,
        val lastAudibleAtMs: Long? = null,
        val startedAtMs: Long? = null,
    )

    private val now = 100_000L
    private val yieldMs = 15_000L
    private val freshWindow = 8_000L

    private fun select(
        sessions: List<Session>,
        heldKey: String?,
        audioAvailable: Boolean = true,
        nowMs: Long = now,
    ): Session? = AudibleSessionSelectionPolicy.select(
        sessions = sessions,
        keyOf = { it.key },
        heldKey = heldKey,
        inMusicCatalog = { it.catalog },
        audioAvailable = audioAvailable,
        isAudible = { it.audible },
        lastAudibleAtMs = { it.lastAudibleAtMs },
        audioStartedAtMs = { it.startedAtMs },
        nowMs = nowMs,
        silentHoldYieldMs = yieldMs,
        freshStartWindowMs = freshWindow,
    )?.selected

    @Test
    fun `audio unavailable yields null to fall back to session-only policy`() {
        val music = Session("music", catalog = true, audible = true, lastAudibleAtMs = now)

        assertNull(select(listOf(music), heldKey = null, audioAvailable = false))
    }

    @Test
    fun `empty session list yields null`() {
        assertNull(select(emptyList(), heldKey = null))
    }

    @Test
    fun `no catalog session audible yields null`() {
        // 仅视频在响：音频层不裁决，交回纯会话规则（视频不因发声夺走歌词锚点）。
        val video = Session("video", catalog = false, audible = true, lastAudibleAtMs = now)
        val pausedMusic = Session("music", catalog = true, audible = false, lastAudibleAtMs = 10L)

        assertNull(select(listOf(video, pausedMusic), heldKey = "music"))
    }

    @Test
    fun `blind window deadlock is unlocked after silent hold threshold`() {
        // 2026-09-29 真机：持有者暂停落在观测盲区，另一音乐 App 已持续发声。
        val held = Session("kuwo", catalog = true, audible = false, lastAudibleAtMs = now - 60_000L)
        val playing = Session("netease", catalog = true, audible = true, lastAudibleAtMs = now - 1_000L)

        assertEquals(playing, select(listOf(held, playing), heldKey = "kuwo"))
    }

    @Test
    fun `silent holder within grace keeps timeline frozen`() {
        // 换曲间隙/缓冲误报暂停：静默未超阈值不切换。
        val held = Session("musicA", catalog = true, audible = false, lastAudibleAtMs = now - 5_000L)
        val other = Session("musicB", catalog = true, audible = true, lastAudibleAtMs = now - 30_000L)

        assertEquals(held, select(listOf(held, other), heldKey = "musicA"))
    }

    @Test
    fun `freshly started challenger takes over immediately despite silent grace`() {
        // 210160 回归（2026-09-29 23:23 真机：酷我起播后歌词迟 16 秒）：交接中旧持有者
        // 先被抢焦点暂停（静默 2 秒、在宽限内），挑战者刚转入发声必须立即接管。
        val held = Session("musicA", catalog = true, audible = false, lastAudibleAtMs = now - 2_000L)
        val fresh = Session("kuwo", catalog = true, audible = true, lastAudibleAtMs = now, startedAtMs = now - 500L)

        assertEquals(fresh, select(listOf(held, fresh), heldKey = "musicA"))
    }

    @Test
    fun `long running challenger does not flicker holder through track gap`() {
        // 双播防抖：挑战者一直在放（转入发声已过窗），持有者换曲间隙的短暂静默
        // 不被挤走，直到静默超时。
        val held = Session("musicA", catalog = true, audible = false, lastAudibleAtMs = now - 3_000L)
        val longRunning = Session("musicB", catalog = true, audible = true, lastAudibleAtMs = now, startedAtMs = now - 60_000L)

        assertEquals(held, select(listOf(held, longRunning), heldKey = "musicA"))
    }

    @Test
    fun `silent holder without audio history yields immediately`() {
        // 从未观测到发声的持有者（跟踪中途启用等）不享有静默保持。
        val held = Session("musicA", catalog = true, audible = false, lastAudibleAtMs = null)
        val other = Session("musicB", catalog = true, audible = true, lastAudibleAtMs = now - 30_000L)

        assertEquals(other, select(listOf(held, other), heldKey = "musicA"))
    }

    @Test
    fun `most recently audible wins when both catalog sessions are audible`() {
        // 双音乐 App 并行出声：最近发声者胜（官方仲裁器语义，A→B 即时切换）。
        val held = Session("musicA", catalog = true, audible = true, lastAudibleAtMs = now - 60_000L)
        val fresh = Session("musicB", catalog = true, audible = true, lastAudibleAtMs = now - 500L)

        assertEquals(fresh, select(listOf(held, fresh), heldKey = "musicA"))
    }

    @Test
    fun `equal audible timestamps keep held to avoid same-snapshot churn`() {
        val held = Session("musicA", catalog = true, audible = true, lastAudibleAtMs = now)
        val other = Session("musicB", catalog = true, audible = true, lastAudibleAtMs = now)

        assertEquals(held, select(listOf(held, other), heldKey = "musicA"))
    }

    @Test
    fun `freshly audible challenger takes over while fading holder still sounds`() {
        // 210161 回归（2026-09-29 23:45 真机十连切换迟 ~2s）：持有者被抢焦点后系统淡出
        // ~2s，挑战者进场那一拍双方 lastAudibleAt 被刷成同拍 now——挑战者刚转入发声
        // 必须立即接管，不等持有者淡出结束。
        val fadingHolder = Session(
            "musicA", catalog = true, audible = true,
            lastAudibleAtMs = now, startedAtMs = now - 60_000L,
        )
        val freshChallenger = Session(
            "musicB", catalog = true, audible = true,
            lastAudibleAtMs = now, startedAtMs = now - 300L,
        )

        assertEquals(
            freshChallenger,
            select(listOf(fadingHolder, freshChallenger), heldKey = "musicA"),
        )
    }

    @Test
    fun `steady dual playback without fresh entry keeps holder`() {
        // 双方长期并行出声（均非新近转入）：保持持有者，防止稳态双播来回抢锚点。
        val held = Session(
            "musicA", catalog = true, audible = true,
            lastAudibleAtMs = now, startedAtMs = now - 120_000L,
        )
        val longRunning = Session(
            "musicB", catalog = true, audible = true,
            lastAudibleAtMs = now, startedAtMs = now - 90_000L,
        )

        assertEquals(held, select(listOf(held, longRunning), heldKey = "musicA"))
    }

    @Test
    fun `audible video session never steals anchor from audible music holder`() {
        // AM 后台出声 + 抖音前台出声：目录过滤保住音乐持有者。
        val held = Session("am", catalog = true, audible = true, lastAudibleAtMs = now - 30_000L)
        val video = Session("douyin", catalog = false, audible = true, lastAudibleAtMs = now)

        assertEquals(held, select(listOf(held, video), heldKey = "am"))
    }

    @Test
    fun `non catalog holder yields immediately once catalog session is audible`() {
        val held = Session("douyin", catalog = false, audible = true, lastAudibleAtMs = now)
        val music = Session("am", catalog = true, audible = true, lastAudibleAtMs = now - 1_000L)

        assertEquals(music, select(listOf(held, music), heldKey = "douyin"))
    }

    @Test
    fun `no holder picks most recent audio history among silent catalog sessions`() {
        // 2026-09-29 22:51 真机：无持有时曾掉进停播一小时的僵尸会话（列表第一个）。
        val zombie = Session("kuwo", catalog = true, audible = false, lastAudibleAtMs = now - 3_600_000L)
        val recent = Session("netease", catalog = true, audible = false, lastAudibleAtMs = now - 120_000L)

        assertEquals(recent, select(listOf(zombie, recent), heldKey = null))
    }

    @Test
    fun `no holder without any audio history yields null to legacy policy`() {
        val zombie = Session("kuwo", catalog = true, audible = false, lastAudibleAtMs = null)

        assertNull(select(listOf(zombie), heldKey = null))
    }

    @Test
    fun `held already best audible keeps selection`() {
        val held = Session("am", catalog = true, audible = true, lastAudibleAtMs = now)
        val older = Session("musicB", catalog = true, audible = true, lastAudibleAtMs = now - 10L)

        assertEquals(held, select(listOf(held, older), heldKey = "am"))
    }

    @Test
    fun `held missing from session list yields best audible candidate`() {
        // 持有会话已摘除（观测盲区成因之一）：直接由发声集合重选。
        val music = Session("am", catalog = true, audible = true, lastAudibleAtMs = now)

        assertEquals(music, select(listOf(music), heldKey = "gone"))
    }
}
