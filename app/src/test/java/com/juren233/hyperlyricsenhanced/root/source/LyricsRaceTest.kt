/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRaceTest {
    private fun hit(value: String?): CompletableDeferred<Result<String?>> =
        CompletableDeferred<Result<String?>>().also { it.complete(Result.success(value)) }

    private suspend fun run(
        ttml: List<Deferred<Result<String?>>>,
        search: Deferred<Result<String?>>,
        events: MutableList<String>,
    ) = LyricsRace.run(
        ttml = ttml,
        search = search,
        ttmlHit = { it != null },
        searchHit = { it != null },
        onEarlyTtml = { index, value -> events += "early-ttml$index:$value" },
        onEarlySearch = { value -> events += "early-search:$value" },
    )

    @Test
    fun `search first is shown early then late amll wins`() = runBlocking {
        val amll = CompletableDeferred<Result<String?>>()
        val lb = hit(null)
        val search = hit("NE")
        val events = mutableListOf<String>()
        val final = async { run(listOf(amll, lb), search, events) }
        // 让竞速先处理搜索源的先到结果，再放出 AMLL。
        while (events.isEmpty()) kotlinx.coroutines.yield()
        amll.complete(Result.success("AMLL"))
        val result = final.await()
        assertEquals(listOf("early-search:NE"), events)
        assertEquals(LyricsRace.Final.Ttml(0, "AMLL" as String?), result)
    }

    @Test
    fun `all ttml missed uses the search result without an early event`() = runBlocking {
        val events = mutableListOf<String>()
        val result = run(listOf(hit(null), hit(null)), hit("QM"), events)
        assertTrue(events.isEmpty())
        assertEquals(LyricsRace.Final.Search("QM" as String?), result)
    }

    @Test
    fun `amll hit first returns immediately`() = runBlocking {
        val events = mutableListOf<String>()
        val result = run(listOf(hit("AMLL"), hit("LB")), CompletableDeferred(), events)
        assertTrue(events.isEmpty())
        assertEquals(LyricsRace.Final.Ttml(0, "AMLL" as String?), result)
    }

    @Test
    fun `lb arrives before amll is shown early and amll still wins`() = runBlocking {
        val amll = CompletableDeferred<Result<String?>>()
        val events = mutableListOf<String>()
        val final = async { run(listOf(amll, hit("LB")), CompletableDeferred(), events) }
        while (events.isEmpty()) kotlinx.coroutines.yield()
        amll.complete(Result.success("AMLL"))
        assertEquals(LyricsRace.Final.Ttml(0, "AMLL" as String?), final.await())
        assertEquals(listOf("early-ttml1:LB"), events)
    }
}
