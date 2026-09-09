package com.juren233.hyperlyricsenhanced.root.source

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class DelayedFallbackRequestTest {
    private class Dispatcher : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private class Fixture {
        val dispatcher = Dispatcher()
        val queued = mutableListOf<Runnable>()
        val delays = mutableListOf<Long>()
        val published = mutableListOf<String?>()
        val failures = mutableListOf<Exception>()
        val owner = DelayedFallbackRequest<String?>(
            CoroutineScope(SupervisorJob() + dispatcher),
            post = { task, delay -> queued.add(task); delays.add(delay) },
            remove = { queued.remove(it) },
        )
        fun schedule(value: String?, delay: Long = 0L, query: suspend () -> String? = { value }) =
            owner.schedule(delay, query, { _, result -> published.add(result) }, failures::add)
        fun start() { queued.removeAt(0).run(); dispatcher.drain() }
        fun deliver() { queued.removeAt(0).run() }
    }

    @Test fun `completed query stays pending until queued delivery is consumed`() {
        val f = Fixture()
        f.schedule("A")
        f.start()
        assertFalse(f.owner.snapshot().running)
        assertTrue(f.owner.snapshot().awaitingDelivery)
        assertTrue(f.owner.snapshot().pending)
        f.deliver()
        assertFalse(f.owner.snapshot().awaitingDelivery)
        assertFalse(f.owner.snapshot().pending)
    }

    @Test fun `same track placeholder does not cancel queued online result`() {
        assertSameTrackDelivery(preferOnline = false, hasNativeLyrics = false, expectOnline = true)
    }

    @Test fun `prefer online keeps queued result when native lyrics arrive`() {
        assertSameTrackDelivery(preferOnline = true, hasNativeLyrics = true, expectOnline = true)
    }

    @Test fun `native first still cancels queued result when native lyrics arrive`() {
        assertSameTrackDelivery(preferOnline = false, hasNativeLyrics = true, expectOnline = false)
    }

    // Models the existing sameTrack + pending + preference gate from handleThirdPartySong.
    // This tests the actual owner but does not instantiate the Android Source or renderer.
    private fun assertSameTrackDelivery(preferOnline: Boolean, hasNativeLyrics: Boolean, expectOnline: Boolean) {
        val f = Fixture()
        f.schedule("online")
        f.start()
        val delivery = f.queued.single()
        val preserve = f.owner.snapshot().pending && (preferOnline || !hasNativeLyrics)
        if (!preserve) f.owner.cancel()
        delivery.run()
        assertEquals(if (expectOnline) listOf("online") else emptyList<String>(), f.published)
    }

    @Test fun `cancel clears pending delivery and cannot publish dequeued result`() {
        val f = Fixture()
        f.schedule("A")
        f.start()
        val delivery = f.queued.single()
        f.owner.cancel()
        assertFalse(f.owner.snapshot().pending)
        delivery.run()
        assertTrue(f.published.isEmpty())
    }

    @Test fun `empty queued result is pending until existing empty handling consumes it`() {
        val f = Fixture()
        f.schedule(null)
        f.start()
        assertTrue(f.owner.snapshot().pending)
        f.deliver()
        assertEquals(listOf<String?>(null), f.published)
        assertFalse(f.owner.snapshot().pending)
    }

    @Test fun `delivery holds owner monitor while calling publisher`() {
        val f = Fixture()
        var invoked = false
        f.owner.schedule(0, { "A" }, { _, _ ->
            assertTrue(Thread.holdsLock(f.owner))
            invoked = true
        }, f.failures::add)
        f.start()
        f.deliver()
        assertTrue(invoked)
    }

    @Test fun `dispatched query does not hold owner monitor`() {
        val f = Fixture()
        var invoked = false
        f.schedule("A", query = {
            assertFalse(Thread.holdsLock(f.owner))
            invoked = true
            "A"
        })
        f.start()
        f.deliver()
        assertTrue(invoked)
    }

    @Test fun `completed job still leaves queued delivery cancellable`() {
        val f = Fixture()
        f.schedule("A")
        f.start()
        assertFalse(f.owner.snapshot().running)
        assertEquals(1, f.queued.size)
        val callback = f.queued.single()
        f.owner.cancel()
        callback.run()
        assertTrue(f.published.isEmpty())
    }

    @Test fun `cancel before delayed start prevents query even when callback was dequeued`() {
        val f = Fixture()
        var queries = 0
        f.schedule("A", query = { queries++; "A" })
        val dequeued = f.queued.single()
        f.owner.cancel()
        dequeued.run()
        f.dispatcher.drain()
        assertEquals(0, queries)
        assertTrue(f.queued.isEmpty())
        assertFalse(f.owner.snapshot().pending)
    }

    @Test fun `non cancellable old query cannot publish or clear replacement job`() {
        val f = Fixture()
        val oldResponse = CompletableDeferred<String?>()
        val newResponse = CompletableDeferred<String?>()
        f.schedule(null, query = { withContext(NonCancellable) { oldResponse.await() } })
        f.start()
        f.schedule(null, query = { newResponse.await() })
        f.start()
        oldResponse.complete("old")
        f.dispatcher.drain()
        assertTrue(f.owner.snapshot().running)
        assertTrue(f.published.isEmpty())
        assertTrue(f.queued.isEmpty())
        assertTrue(f.failures.isEmpty())
        newResponse.complete("new")
        f.dispatcher.drain()
        f.deliver()
        assertEquals(listOf("new"), f.published)
        assertFalse(f.owner.snapshot().pending)
    }

    @Test fun `negative delay is queued as zero without inline publication`() {
        val f = Fixture()
        f.schedule("A", -10L)
        assertEquals(listOf(0L), f.delays)
        assertTrue(f.published.isEmpty())
        f.start()
        f.deliver()
        assertEquals(listOf("A"), f.published)
    }

    @Test fun `successful result reaches publication only on delivery queue`() {
        val f = Fixture()
        f.schedule("A", 3000)
        assertEquals(listOf(3000L), f.delays)
        assertTrue(f.owner.snapshot().delayed)
        f.start()
        assertTrue(f.published.isEmpty())
        f.deliver()
        assertEquals(listOf("A"), f.published)
        assertFalse(f.owner.snapshot().pending)
    }

    @Test fun `cancel rejects completed result even if removal cannot retract callback`() {
        val f = Fixture()
        f.schedule("A")
        f.start()
        val alreadyDequeued = f.queued.single()
        f.owner.cancel()
        alreadyDequeued.run()
        assertTrue(f.published.isEmpty())
        assertTrue(f.queued.isEmpty())
    }

    @Test fun `old delayed start cannot launch after replacement`() {
        val f = Fixture()
        var oldQueries = 0
        f.schedule("A", query = { oldQueries++; "A" })
        val oldStart = f.queued.single()
        f.schedule("B")
        oldStart.run()
        f.start()
        f.deliver()
        assertEquals(0, oldQueries)
        assertEquals(listOf("B"), f.published)
    }

    @Test fun `A B A rejects both older identities even when song returns`() {
        val f = Fixture()
        f.schedule("A")
        f.start()
        val oldA = f.queued.single()
        f.schedule("B")
        f.start()
        val oldB = f.queued.single()
        f.schedule("A")
        oldA.run()
        oldB.run()
        f.start()
        f.deliver()
        assertEquals(listOf("A"), f.published)
    }

    @Test fun `cancel suspended query and repeated cleanup leave no delivery`() {
        val f = Fixture()
        val response = CompletableDeferred<String?>()
        f.schedule(null, query = { response.await() })
        f.start()
        assertTrue(f.owner.snapshot().running)
        f.owner.cancel()
        f.owner.cancel()
        response.complete("stale")
        f.dispatcher.drain()
        assertTrue(f.published.isEmpty())
        assertTrue(f.queued.isEmpty())
        assertTrue(f.failures.isEmpty())
        assertFalse(f.owner.snapshot().pending)
    }

    @Test fun `query failure releases task and permits retry`() {
        val f = Fixture()
        f.schedule(null, query = { throw IllegalStateException("expected") })
        f.start()
        assertEquals(1, f.failures.size)
        assertFalse(f.owner.snapshot().pending)
        f.schedule("retry")
        f.start()
        f.deliver()
        assertEquals(listOf("retry"), f.published)
    }

    @Test fun `empty result is delivered for existing empty state handling`() {
        val f = Fixture()
        f.schedule(null)
        f.start()
        f.deliver()
        assertEquals(listOf<String?>(null), f.published)
    }

    @Test fun `publication may reentrantly schedule next request`() {
        val f = Fixture()
        f.owner.schedule(0, { "A" }, { _, result ->
            f.published.add(result)
            f.schedule("B")
        }, f.failures::add)
        f.start()
        f.deliver()
        f.start()
        f.deliver()
        assertEquals(listOf("A", "B"), f.published)
    }

    @Test fun `one completion cannot publish twice`() {
        val f = Fixture()
        f.schedule("A")
        f.start()
        val callback = f.queued.single()
        callback.run()
        callback.run()
        assertEquals(listOf("A"), f.published)
    }

    @Test fun `cancellation cannot split delivery validation from publication`() {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        f.owner.schedule(0, { "A" }, { _, result ->
            entered.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
            f.published.add(result)
        }, f.failures::add)
        f.start()
        val delivery = FutureTask<Unit> { f.deliver() }
        val deliveryThread = Thread(delivery).apply { isDaemon = true; start() }
        val cancellation = FutureTask<Unit> { f.owner.cancel() }
        val cancellationThread = Thread(cancellation).apply { isDaemon = true }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            cancellationThread.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (cancellationThread.state != Thread.State.BLOCKED &&
                !cancellation.isDone && System.nanoTime() < deadline
            ) Thread.yield()
            assertEquals(Thread.State.BLOCKED, cancellationThread.state)
        } finally {
            proceed.countDown()
            delivery.get(5, TimeUnit.SECONDS)
            cancellation.get(5, TimeUnit.SECONDS)
            deliveryThread.join(1000)
            cancellationThread.join(1000)
        }
        assertEquals(listOf("A"), f.published)
        assertFalse(f.owner.snapshot().pending)
    }
}
