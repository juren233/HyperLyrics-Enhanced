/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.online.OnlineLyricTargeter
import com.juren233.hyperlyricsenhanced.online.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 切换来源只处理被点来源、重开弹窗复用上一轮结果、取词缓存按检索对象互不清除。 */
class OnlineSourceReuseTest {
    private var now = 0L
    private val slot = AppleInAppSong(clockMs = { now })
    private val target = slot.target
    private val q = "query"
    private val hit = OnlineTranslationFetchCache.Entry.Hit(OnlineLyricTargeter.FetchOutcome())
    private val line = RichLyricLine(begin = 0L, end = 1_000L, duration = 1_000L, text = "line")

    private fun song(id: String = "a", lyrics: List<RichLyricLine> = listOf(line)) =
        Song(id = id, name = "title", artist = "artist", duration = 200_000L, lyrics = lyrics)

    private fun request(contentType: String = "translation", source: Source = Source.NE) =
        OnlineSourceSwitchRequest(1L, "a", contentType, source, 0L)

    // --- 是否首次检索（唯一判定） ---

    @Test
    fun `first search has no source results yet so it stays progressive`() {
        target.manual.acceptTranslationRequest(request(), Source.NE)
        assertFalse("没有任何来源结果时不是切换", target.isSourceSwitchRound("a"))
    }

    @Test
    fun `a pending switch with live source results is a switch round`() {
        target.fetchCache.put("a", Source.QM, q, hit)
        assertFalse("没有待完成的切换请求", target.isSourceSwitchRound("a"))

        target.manual.acceptTranslationRequest(request(), Source.NE)
        assertTrue(target.isSourceSwitchRound("a"))
        assertFalse(target.isSourceSwitchRound("other"))
        assertFalse(target.isSourceSwitchRound(null))
    }

    @Test
    fun `switching back to Apple native counts as a switch without a pending request`() {
        target.fetchCache.put("a", Source.QM, q, hit)
        assertFalse(target.isSourceSwitchRound("a"))
        assertTrue(target.isSourceSwitchRound("a", selectionChanged = true))
    }

    @Test
    fun `expired source results make the next search a first search again`() {
        target.fetchCache.put("a", Source.QM, q, hit)
        target.manual.acceptPronunciationRequest(request("pronunciation"), Source.NE)
        assertTrue(target.isSourceSwitchRound("a"))

        now += 10L * 60L * 1_000L + 1
        assertFalse(target.isSourceSwitchRound("a"))
    }

    // --- 只有点选的那一轮强制重查未命中 ---

    @Test
    fun `only the pending request forces a retry of its source`() {
        target.manual.acceptTranslationRequest(request(source = Source.LB), Source.LB)
        assertTrue(target.manual.isRetryRequested(Source.LB))
        assertFalse(target.manual.isRetryRequested(Source.NE))

        // 请求完成后来源的选择仍保留，但之后的检索（重开弹窗）不再强制重查。
        target.manual.clearOnlineRequests()
        assertEquals(Source.LB, target.manual.requestedTranslationSource())
        assertFalse(target.manual.isRetryRequested(Source.LB))
    }

    @Test
    fun `a miss is not searched again within its lifetime unless explicitly requested`() {
        target.fetchCache.put("a", Source.LB, q, OnlineTranslationFetchCache.Entry.Miss)
        assertEquals(OnlineTranslationFetchCache.Entry.Miss, target.fetchCache.get("a", Source.LB, q))
        assertFalse(target.manual.isRetryRequested(Source.LB))

        now += 10L * 60L * 1_000L + 1
        assertNull(target.fetchCache.get("a", Source.LB, q))
    }

    // --- 重开弹窗复用上一轮完整结果 ---

    @Test
    fun `reopening reuses the last final payload for the same attempt and lyric lines`() {
        target.fetchCache.put("a", Source.QM, q, hit)
        val payload = song().copy(name = "payload")
        target.finalPayload.record("attempt", song(), payload)

        assertSame(payload, target.reusableFinalPayload(song(), "attempt"))
        assertNull("attempt 变了（如原名到达）要重新检索", target.reusableFinalPayload(song(), "other"))
        assertNull(
            "歌词行变了（如换了歌词来源）要重新检索",
            target.reusableFinalPayload(song(lyrics = listOf(line.copy(text = "changed"))), "attempt"),
        )
    }

    @Test
    fun `no reuse while a search is running, after expiry or once cleared`() {
        target.fetchCache.put("a", Source.QM, q, hit)
        target.finalPayload.record("attempt", song(), song())

        val generation = target.request.begin("attempt", allowRestart = true)
        assertNotNull(generation)
        target.request.launch(kotlinx.coroutines.GlobalScope, requireNotNull(generation)) {
            kotlinx.coroutines.awaitCancellation()
        }
        assertNull(target.reusableFinalPayload(song(), "attempt"))
        target.request.cancel(clearAttempt = false)

        now += 10L * 60L * 1_000L + 1
        assertNull("取词结果过期后不再复用", target.reusableFinalPayload(song(), "attempt"))

        target.fetchCache.put("a", Source.QM, q, hit)
        assertTrue(target.reusableFinalPayload(song(), "attempt") != null)
        target.finalPayload.clear()
        assertNull(target.reusableFinalPayload(song(), "attempt"))
    }

    private fun assertNotNull(value: Any?) = org.junit.Assert.assertNotNull(value)

    // --- 取词缓存按检索对象互不清除 ---

    @Test
    fun `each search target keeps its own song results`() {
        val active = OnlineTranslationFetchCache(clockMs = { now })
        target.fetchCache.put("in-app-song", Source.NE, q, hit)
        active.put("active-song", Source.NE, q, hit)
        // 交替检索不互相清掉。
        target.fetchCache.put("in-app-song", Source.QM, q, hit)
        active.put("active-song", Source.QM, q, hit)

        assertEquals(hit, target.fetchCache.get("in-app-song", Source.NE, q))
        assertEquals(hit, active.get("active-song", Source.NE, q))
        assertNull(target.fetchCache.get("active-song", Source.NE, q))
    }

    @Test
    fun `live entry check follows the song and the lifetime`() {
        val cache = OnlineTranslationFetchCache(clockMs = { now }, ttlMs = 1_000L)
        assertFalse(cache.hasLiveEntries("a"))
        cache.put("a", Source.NE, q, hit)
        assertTrue(cache.hasLiveEntries("a"))
        assertFalse(cache.hasLiveEntries("b"))
        now += 1_001L
        assertFalse(cache.hasLiveEntries("a"))
    }

    @Test
    fun `final payload recorder returns nothing for another attempt`() {
        val store = OnlineSourceFinalPayload()
        assertNull(store.reusable("x", song()))
        store.record("x", song(), song().copy(name = "p"))
        assertEquals("p", store.reusable("x", song())?.name)
        assertNull(store.reusable("y", song()))
    }
}
