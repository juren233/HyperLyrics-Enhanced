/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class PreparedWidthCacheTest {
    private class Queue : Executor {
        val jobs = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { jobs.add(command) }
        fun run() { while (jobs.isNotEmpty()) jobs.removeFirst().run() }
    }

    @Test fun diagnosticOriginFollowsTheStoredValueAndEviction() {
        val queue = Queue()
        val cache = PreparedWidthCache<String>(queue, capacity = 1, recordOrigins = true)
        cache.prepare(listOf("line")) { 40f }
        queue.run()
        assertEquals(PreparedWidthCache.Lookup(40f, true), cache.inspect("line"))
        cache.put("line", 41f)
        assertEquals(PreparedWidthCache.Lookup(41f, false), cache.inspect("line"))
        cache.clear()
        cache.prepare(listOf("line")) { 42f }
        queue.run()
        cache.put("other", 20f)
        assertEquals(PreparedWidthCache.Lookup(null, false), cache.inspect("line"))
        cache.clear()
        assertEquals(PreparedWidthCache.Lookup(null, false), cache.inspect("other"))
    }

    @Test fun preparationRunsOffTheCallerAndMissDoesNotWait() {
        val queue = Queue()
        val cache = PreparedWidthCache<String>(queue)
        var measured = 0
        cache.prepare(listOf("next")) { measured++; 40f }
        assertEquals(0, measured)
        assertNull(cache.get("next"))
        queue.run()
        assertEquals(40f, cache.get("next"))
    }

    @Test fun latestSongReplacesQueuedPreparation() {
        val queue = Queue()
        val cache = PreparedWidthCache<String>(queue)
        cache.prepare(listOf("old")) { error("stale request") }
        cache.prepare(listOf("new")) { 21f }
        assertEquals(1, queue.jobs.size)
        queue.run()
        assertNull(cache.get("old"))
        assertEquals(21f, cache.get("new"))
    }

    @Test fun metricChangeDuringMeasurementRejectsOldWidth() {
        val queue = Queue()
        val cache = PreparedWidthCache<String>(queue)
        cache.prepare(listOf("line")) {
            cache.clear()
            cache.prepare(listOf("line")) { 80f }
            40f
        }
        queue.run()
        assertEquals(80f, cache.get("line"))
    }

    @Test fun detachInvalidatesQueuedAndInFlightResults() {
        val queue = Queue()
        val cache = PreparedWidthCache<String>(queue)
        cache.prepare(listOf("line")) { cache.clear(); 40f }
        queue.run()
        assertNull(cache.get("line"))
        cache.prepare(listOf("later")) { 20f }
        cache.clear()
        queue.run()
        assertNull(cache.get("later"))
    }

    @Test fun textChangeAndLateTranslationDoNotReuseAnotherRowsWidth() {
        val queue = Queue()
        val cache = PreparedWidthCache<String>(queue)
        cache.prepare(listOf("main", "translation")) { it.length.toFloat() }
        queue.run()
        assertEquals(4f, cache.get("main"))
        assertEquals(11f, cache.get("translation"))
        assertNull(cache.get("new translation"))
        cache.put("new translation", 15f)
        assertEquals(15f, cache.get("new translation"))
    }

    @Test fun repeatedTextIsMeasuredOnceAndCacheIsBounded() {
        val queue = Queue()
        val cache = PreparedWidthCache<String>(queue, capacity = 2)
        var calls = 0
        cache.prepare(listOf("a", "a", "b", "c")) { calls++; 1f }
        queue.run()
        assertEquals(2, calls)
        cache.get("a")
        cache.put("c", 3f)
        assertNull(cache.get("b"))
        assertEquals(1f, cache.get("a"))
        assertEquals(3f, cache.get("c"))
    }

    @Test fun failedMeasurementDoesNotStopFollowingRowsOrLaterRequests() {
        val queue = Queue()
        val cache = PreparedWidthCache<String>(queue)
        cache.prepare(listOf("bad", "good")) { if (it == "bad") error("bad font") else 12f }
        queue.run()
        assertNull(cache.get("bad"))
        assertEquals(12f, cache.get("good"))
        cache.prepare(listOf("next")) { 20f }
        queue.run()
        assertEquals(20f, cache.get("next"))
    }

    @Test fun rejectedBackgroundWorkLeavesSynchronousFallbackUsable() {
        val cache = PreparedWidthCache<String>(Executor { throw RejectedExecutionException() })
        cache.prepare(listOf("line")) { 30f }
        assertNull(cache.get("line"))
        cache.put("line", 30f)
        assertEquals(30f, cache.get("line"))
    }

    @Test fun movingIslandKeepsPreparationQueuedWithoutAWorker() {
        val queue = Queue()
        val gate = WidthPreparationGate()
        val cache = PreparedWidthCache<String>(queue)
        cache.prepare(listOf("line"), gate) { 30f }
        assertTrue(queue.jobs.isEmpty())
        repeat(2) { gate.onFrame(false) }
        queue.run()
        assertEquals(30f, cache.get("line"))
    }

    @Test fun transitionBetweenRowsPausesRemainingWorkAndResumesIt() {
        val queue = Queue()
        val gate = WidthPreparationGate()
        repeat(2) { gate.onFrame(false) }
        val cache = PreparedWidthCache<String>(queue)
        val measured = mutableListOf<String>()
        cache.prepare(listOf("one", "two"), gate) {
            measured += it
            if (it == "one") gate.onTransitionStarted()
            20f
        }
        queue.run()
        assertEquals(listOf("one"), measured)
        assertEquals(20f, cache.get("one")) // completed cache remains readable while moving
        assertNull(cache.get("two"))
        assertTrue(queue.jobs.isEmpty())
        repeat(2) { gate.onFrame(false) }
        queue.run()
        assertEquals(listOf("one", "two"), measured)
    }

    @Test fun transitionBeforeQueuedWorkerRunsDoesNotStartMeasurement() {
        val queue = Queue()
        val gate = WidthPreparationGate()
        repeat(2) { gate.onFrame(false) }
        val cache = PreparedWidthCache<String>(queue)
        var count = 0
        cache.prepare(listOf("line"), gate) { count++; 20f }
        gate.onTransitionStarted()
        queue.run()
        assertEquals(0, count)
        repeat(2) { gate.onFrame(false) }
        queue.run()
        assertEquals(1, count)
    }

    @Test fun clearWhilePausedUnsubscribesAndCannotReviveOldWork() {
        val queue = Queue()
        val gate = WidthPreparationGate()
        val cache = PreparedWidthCache<String>(queue)
        cache.prepare(listOf("old"), gate) { error("detached work") }
        cache.clear()
        repeat(2) { gate.onFrame(false) }
        assertTrue(queue.jobs.isEmpty())
        assertNull(cache.get("old"))
    }

    @Test fun movingToAnotherHostDoesNotResumeFromTheOldHostsPermit() {
        val queue = Queue()
        val oldHost = WidthPreparationGate()
        val newHost = WidthPreparationGate()
        val cache = PreparedWidthCache<String>(queue)
        cache.prepare(listOf("old"), oldHost) { error("old host") }
        cache.prepare(listOf("new"), newHost) { 25f }
        repeat(2) { oldHost.onFrame(false) }
        assertTrue(queue.jobs.isEmpty())
        repeat(2) { newHost.onFrame(false) }
        queue.run()
        assertEquals(25f, cache.get("new"))
        assertNull(cache.get("old"))
    }
}
