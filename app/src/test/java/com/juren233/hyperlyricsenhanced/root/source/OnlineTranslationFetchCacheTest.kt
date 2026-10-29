/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.online.OnlineLyricTargeter
import com.juren233.hyperlyricsenhanced.online.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineTranslationFetchCacheTest {
    private var now = 0L
    private val cache = OnlineTranslationFetchCache(clockMs = { now }, ttlMs = 1_000L)
    private val q = "title\u0000artist"
    private val hit = OnlineTranslationFetchCache.Entry.Hit(OnlineLyricTargeter.FetchOutcome())

    @Test
    fun `entries are per song and per source`() {
        cache.put("a", Source.NE, q, hit)
        cache.put("a", Source.QM, q, OnlineTranslationFetchCache.Entry.Miss)
        assertEquals(hit, cache.get("a", Source.NE, q))
        assertEquals(OnlineTranslationFetchCache.Entry.Miss, cache.get("a", Source.QM, q))
        assertNull(cache.get("a", Source.KUWO, q))
        assertNull(cache.get("b", Source.NE, q))
    }

    @Test
    fun `a new song drops the previous song entries`() {
        cache.put("a", Source.NE, q, hit)
        cache.put("b", Source.QM, q, hit)
        assertNull(cache.get("a", Source.NE, q))
        assertEquals(hit, cache.get("b", Source.QM, q))
    }

    @Test
    fun `entries expire`() {
        cache.put("a", Source.NE, q, hit)
        now = 1_001L
        assertNull(cache.get("a", Source.NE, q))
    }

    @Test
    fun `a changed query does not reuse an earlier miss`() {
        cache.put("a", Source.NE, q, OnlineTranslationFetchCache.Entry.Miss)
        assertNull(cache.get("a", Source.NE, "original title\u0000artist"))
        assertNull(cache.get("a", Source.NE, q))
    }
}
