/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSessionSelectionPolicyTest {

    private data class Session(
        val key: String,
        val playing: Boolean,
        val playStartedAtMs: Long? = null,
    )

    private val window = 8_000L

    private fun select(
        sessions: List<Session>,
        heldKey: String?,
        nowMs: Long = 100_000L,
    ): Session? = MediaSessionSelectionPolicy.select(
        sessions = sessions,
        keyOf = { it.key },
        heldKey = heldKey,
        isPlaying = { it.playing },
        playStartedAtMs = { it.playStartedAtMs },
        nowMs = nowMs,
        playStartWindowMs = window,
    ).selected

    @Test
    fun `empty session list yields null`() {
        assertNull(select(emptyList(), heldKey = null))
    }

    @Test
    fun `held playing session is kept when another session has no newer start`() {
        val held = Session("music", playing = true, playStartedAtMs = 99_000L)
        val other = Session("mirror", playing = true, playStartedAtMs = 10L)

        assertEquals(held, select(listOf(held, other), heldKey = "music"))
    }

    @Test
    fun `freshly started app wins before old app reports pause`() {
        // 真实 A→B 中 B 的 PLAYING 回调可能先于 A 的 PAUSED，不应等 A 才切换。
        val held = Session("musicA", playing = true, playStartedAtMs = 90_000L)
        val fresh = Session("musicB", playing = true, playStartedAtMs = 99_500L)

        assertEquals(fresh, select(listOf(held, fresh), heldKey = "musicA"))
    }

    @Test
    fun `simultaneously first observed sessions do not displace held session`() {
        val held = Session("musicA", playing = true, playStartedAtMs = 99_500L)
        val other = Session("musicB", playing = true, playStartedAtMs = 99_500L)

        assertEquals(held, select(listOf(held, other), heldKey = "musicA"))
    }

    @Test
    fun `held buffering session is kept when other session has long been playing`() {
        // 缓冲场景：音乐 app 上报暂停，旁边的会话一直在放（起播远超窗口）→ 不切换。
        val held = Session("music", playing = false)
        val longPlaying = Session("mirror", playing = true, playStartedAtMs = 10_000L)

        assertEquals(
            held,
            select(listOf(held, longPlaying), heldKey = "music", nowMs = 100_000L),
        )
    }

    @Test
    fun `held paused session yields immediately to freshly started session`() {
        // 换 app 场景：B 新起播（1 秒前）→ 立即让位，无等待。
        val held = Session("musicA", playing = false)
        val fresh = Session("musicB", playing = true, playStartedAtMs = 99_000L)

        assertEquals(
            fresh,
            select(listOf(held, fresh), heldKey = "musicA", nowMs = 100_000L),
        )
    }

    @Test
    fun `leftover paused holder yields to freshly started music`() {
        // 原始卡死场景：残留暂停会话被持有，音乐 app 新起播 → 立即切换。
        val leftover = Session("bili", playing = false)
        val music = Session("music", playing = true, playStartedAtMs = 98_000L)

        assertEquals(
            music,
            select(listOf(leftover, music), heldKey = "bili", nowMs = 100_000L),
        )
    }

    @Test
    fun `freshest start wins among multiple fresh playing sessions`() {
        val held = Session("musicA", playing = false)
        val older = Session("mirror", playing = true, playStartedAtMs = 93_000L)
        val freshest = Session("musicB", playing = true, playStartedAtMs = 99_500L)

        assertEquals(
            freshest,
            select(listOf(held, older, freshest), heldKey = "musicA", nowMs = 100_000L),
        )
    }

    @Test
    fun `freshness window boundary is inclusive`() {
        val held = Session("music", playing = false)
        val boundary = Session("musicB", playing = true, playStartedAtMs = 92_000L)

        assertEquals(
            boundary,
            select(listOf(held, boundary), heldKey = "music", nowMs = 100_000L),
        )

        val beyond = Session("musicB", playing = true, playStartedAtMs = 91_999L)
        assertEquals(
            held,
            select(listOf(held, beyond), heldKey = "music", nowMs = 100_000L),
        )
    }

    @Test
    fun `playing session without recorded start never triggers yield`() {
        // 起播时刻未知（从未记录）不视为新近起播。
        val held = Session("music", playing = false)
        val unknown = Session("mirror", playing = true, playStartedAtMs = null)

        assertEquals(held, select(listOf(held, unknown), heldKey = "music"))
    }

    @Test
    fun `held paused session is kept when nothing else is playing`() {
        val held = Session("music", playing = false)
        val other = Session("bili", playing = false)

        assertEquals(held, select(listOf(other, held), heldKey = "music"))
    }

    @Test
    fun `first playing session wins when held session disappeared`() {
        val paused = Session("bili", playing = false)
        val playing = Session("music", playing = true, playStartedAtMs = 10_000L)

        assertEquals(
            playing,
            select(listOf(paused, playing), heldKey = "closed"),
        )
    }

    @Test
    fun `falls back to first session when held gone and nothing playing`() {
        val first = Session("bili", playing = false)
        val second = Session("music", playing = false)

        assertEquals(first, select(listOf(first, second), heldKey = "closed"))
    }
}
