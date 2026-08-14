/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.online.model.Source
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineTranslationRaceTest {
    @Test
    fun `starts all sources concurrently and reports completion order`() = runBlocking {
        val allStarted = CompletableDeferred<Unit>()
        val startedCount = AtomicInteger()
        val completionOrder = mutableListOf<Source>()

        val results = withTimeout(1_000L) {
            OnlineTranslationRace.run(
                sources = listOf(Source.NE, Source.QM, Source.KUGOU),
                clockMs = { System.nanoTime() / 1_000_000L },
                fetch = { source ->
                    if (startedCount.incrementAndGet() == 3) allStarted.complete(Unit)
                    allStarted.await()
                    delay(
                        when (source) {
                            Source.QM -> 10L
                            Source.KUGOU -> 20L
                            else -> 30L
                        }
                    )
                    source.name
                },
                onCompletion = { completionOrder += it.source },
            )
        }

        assertEquals(3, startedCount.get())
        assertEquals(listOf(Source.QM, Source.KUGOU, Source.NE), completionOrder)
        assertEquals("NE", results[Source.NE])
    }

    @Test
    fun `one source failure does not cancel successful racers`() = runBlocking {
        val completions = mutableListOf<OnlineTranslationRace.Completion<String>>()

        val results = OnlineTranslationRace.run(
            sources = listOf(Source.NE, Source.QM),
            clockMs = { System.nanoTime() / 1_000_000L },
            fetch = { source ->
                if (source == Source.NE) error("network failed")
                "success"
            },
            onCompletion = { completions += it },
        )

        assertNull(results[Source.NE])
        assertEquals("success", results[Source.QM])
        assertTrue(completions.single { it.source == Source.NE }.error is IllegalStateException)
    }
}
