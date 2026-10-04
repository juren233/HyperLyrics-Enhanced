package com.juren233.hyperlyricsenhanced.common.bridge

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class AppleDirectLifecycleOrderingTest {
    @Test fun `in flight payload finishes before final clear and cannot replay after stop`() {
        val lock = Any()
        val entered = CountDownLatch(1)
        val finishSend = CountDownLatch(1)
        val stopRequested = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val calls = mutableListOf<String>()
        var active = true
        val sender = thread {
            runCatching {
                withAppleDirectLifecycle(lock) {
                    if (active) {
                        entered.countDown()
                        check(finishSend.await(5, TimeUnit.SECONDS))
                        calls += "payload"
                    }
                }
            }.onFailure(error::set)
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val stopper = thread {
            runCatching {
                stopRequested.countDown()
                withAppleDirectLifecycle(lock) {
                    active = false
                    calls += "final_clear"
                }
                stopped.countDown()
            }.onFailure(error::set)
        }
        assertTrue(stopRequested.await(5, TimeUnit.SECONDS))
        assertFalse(stopped.await(50, TimeUnit.MILLISECONDS))
        finishSend.countDown()
        sender.join(5_000)
        stopper.join(5_000)
        assertFalse(sender.isAlive)
        assertFalse(stopper.isAlive)
        assertNull(error.get())
        withAppleDirectLifecycle(lock) { if (active) calls += "late_replay" }
        assertEquals(listOf("payload", "final_clear"), calls)
    }

    @Test fun `stop before a queued replay prevents remote invocation`() {
        val lock = Any()
        var active = true
        val calls = mutableListOf<String>()
        val queuedReplay = {
            withAppleDirectLifecycle(lock) { if (active) calls += "payload" }
        }
        withAppleDirectLifecycle(lock) {
            active = false
            calls += "final_clear"
        }
        queuedReplay()
        assertEquals(listOf("final_clear"), calls)
    }
}
