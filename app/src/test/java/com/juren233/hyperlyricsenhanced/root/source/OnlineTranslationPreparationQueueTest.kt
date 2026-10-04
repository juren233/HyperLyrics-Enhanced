/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.online.model.Source
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class OnlineTranslationPreparationQueueTest {
    @Test fun `UI continues processing while matching is blocked on worker`() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "translation-test-main") }.asCoroutineDispatcher().use { main ->
            Executors.newSingleThreadExecutor { Thread(it, "translation-test-worker") }.asCoroutineDispatcher().use { worker ->
                val queue = OnlineTranslationPreparationQueue(main, worker)
                val started = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                var committed = false
                val job = launch {
                    queue.prepareAndCommit(
                        capture = { assertTrue(Thread.currentThread().name.startsWith("translation-test-main")); "snapshot" },
                        prepare = {
                            assertTrue(Thread.currentThread().name.startsWith("translation-test-worker"))
                            started.complete(Unit)
                            check(release.await(5, TimeUnit.SECONDS))
                            it.uppercase()
                        },
                        commit = { _, result ->
                            assertTrue(Thread.currentThread().name.startsWith("translation-test-main"))
                            assertEquals("SNAPSHOT", result)
                            committed = true
                            true
                        },
                    )
                }
                try {
                    withTimeout(2_000) { started.await() }
                    withTimeout(2_000) { withContext(main) { assertFalse(committed) } }
                } finally {
                    release.countDown()
                }
                withTimeout(2_000) { job.join() }
                assertTrue(committed)
            }
        }
    }

    @Test fun `late native translation and changed line layout invalidate the prepared result`() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
                val queue = OnlineTranslationPreparationQueue(main, worker)
                val started = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val selection = OnlineTranslationSelection(
                    onlineLinesBySource = mapOf(Source.NE to listOf(LrcLine(1_000, "First", translation = "fallback"))),
                    defaultTranslationSource = Source.NE,
                )
                var current = song()
                var attempts = 0
                val published = mutableListOf<Song>()
                val job = launch {
                    queue.prepareAndCommit(
                        capture = { current.deepCopy() },
                        prepare = { snapshot ->
                            attempts++
                            val candidates = selection.matchCandidates(snapshot)
                            val result = selection.composeMatched(snapshot, null, candidates)
                            if (attempts == 1) {
                                started.complete(Unit)
                                check(release.await(5, TimeUnit.SECONDS))
                            }
                            result
                        },
                        commit = { snapshot, result ->
                            if (snapshot != current) false
                            else { published += result!!.song; true }
                        },
                    )
                }
                try {
                    withTimeout(2_000) { started.await() }
                    withContext(main) {
                        // Mutate the live model too: a reference-only snapshot would miss this.
                        current.lyrics!!.first().translation = "native"
                        current = current.copy(lyrics = listOf(
                            RichLyricLine(begin = 0, end = 900, text = "Intro"),
                        ) + current.lyrics.orEmpty())
                    }
                } finally {
                    release.countDown()
                }
                withTimeout(2_000) { job.join() }
                assertEquals(2, attempts)
                assertEquals(1, published.size)
                assertEquals(listOf(null, "native"), published.single().lyrics!!.map { it.translation })
            }
        }
    }

    @Test fun `switching songs rejects old preparation and publishes the new source availability`() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
                val queue = OnlineTranslationPreparationQueue(main, worker)
                val owner = OnlineTranslationRequest<String>()
                val oldToken = owner.begin("old")!!
                val started = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                var current = song().copy(id = "old")
                val published = mutableListOf<Song>()
                val oldJob = launch {
                    queue.prepareAndCommit(
                        capture = { current.deepCopy().takeIf { owner.snapshot().generation == oldToken } },
                        prepare = { started.complete(Unit); check(release.await(5, TimeUnit.SECONDS)); it },
                        commit = { snapshot, result ->
                            owner.deliver(oldToken, stillCurrent = { snapshot == current }) { published += result }
                            true
                        },
                    )
                }
                try {
                    withTimeout(2_000) { started.await() }
                    withContext(main) {
                        owner.cancel(clearAttempt = true)
                        current = song().copy(id = "new", lyrics = song().lyrics!!.map { it.copy(translation = "native new") })
                    }
                } finally {
                    release.countDown()
                }
                val newToken = owner.begin("new")!!
                val newJob = launch {
                    queue.prepareAndCommit(
                        capture = { current.deepCopy() },
                        prepare = { it },
                        commit = { snapshot, result -> owner.deliver(newToken, stillCurrent = { snapshot == current }) { published += result } },
                    )
                }
                withTimeout(2_000) { oldJob.join(); newJob.join() }
                assertEquals(listOf("new"), published.map { it.id })
                assertEquals("native new", published.single().lyrics!!.single().translation)
            }
        }
    }

    @Test fun `race final waits for first commit and snapshots its published content`() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
                val queue = OnlineTranslationPreparationQueue(main, worker)
                val started = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val commits = mutableListOf<String>()
                var current = "native"
                val first = launch(start = CoroutineStart.UNDISPATCHED) {
                    queue.prepareAndCommit(
                        capture = { current },
                        prepare = { started.complete(Unit); check(release.await(5, TimeUnit.SECONDS)); "$it+first" },
                        commit = { _, result -> current = result; commits += result; true },
                    )
                }
                withTimeout(2_000) { started.await() }
                val final = launch(start = CoroutineStart.UNDISPATCHED) {
                    queue.prepareAndCommit(
                        capture = { current },
                        prepare = { "$it+final" },
                        commit = { _, result -> commits += result; true },
                    )
                }
                release.countDown()
                withTimeout(2_000) { first.join(); final.join() }
                assertEquals(listOf("native+first", "native+first+final"), commits)
            }
        }
    }

    @Test fun `invalidated queued work never calls matcher or publisher`() = runBlocking {
        val queue = OnlineTranslationPreparationQueue(kotlinx.coroutines.Dispatchers.Unconfined)
        queue.prepareAndCommit<String, String>(
            capture = { null },
            prepare = { error("expired request must not match") },
            commit = { _, _ -> error("expired request must not publish") },
        )
    }

    private fun song() = Song(id = "song", name = "Song", lyrics = listOf(
        RichLyricLine(begin = 1_000, end = 1_900, text = "First"),
    ))
}
