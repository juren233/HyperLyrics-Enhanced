/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.reload

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ReloadTaskTest {
    @Test fun `late queued cleanup cannot run after request was rejected`() {
        var clears = 0
        val task = ReloadTask { ++clears }
        assertNull(task.await(0))
        task.run()
        assertEquals(0, clears)
    }

    @Test fun `teardown completes before state is handed to replacement`() {
        val events = mutableListOf<String>()
        val task = ReloadTask {
            events += "capture"
            events += "clear old views"
            events += "unregister listeners"
            "host state"
        }
        task.run()
        task.run()
        assertEquals("host state", task.await(0))
        assertEquals(listOf("capture", "clear old views", "unregister listeners"), events)
    }

    @Test fun `running cleanup is awaited even if dispatch wait expires or is interrupted`() {
        val began = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val value = AtomicBoolean()
        val task = ReloadTask { began.countDown(); finish.await(); true }
        val worker = thread { task.run() }
        assertTrue(began.await(2, TimeUnit.SECONDS))
        val waiter = thread {
            value.set(task.await(0) == true)
            interrupted.set(Thread.currentThread().isInterrupted)
            returned.countDown()
        }
        try {
            waiter.interrupt()
            assertFalse(returned.await(30, TimeUnit.MILLISECONDS))
        } finally {
            finish.countDown()
            worker.join(2_000)
            waiter.join(2_000)
        }
        assertTrue(value.get())
        assertTrue(interrupted.get())
    }

    @Test fun `failed cleanup is never reported as successful state transfer`() {
        val task = ReloadTask<Boolean> { error("cleanup failed") }
        task.run()
        assertThrows(IllegalStateException::class.java) { task.await(0) }
    }
}
