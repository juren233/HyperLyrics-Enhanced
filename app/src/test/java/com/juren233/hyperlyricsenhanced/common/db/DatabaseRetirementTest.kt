/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.common.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class DatabaseRetirementTest {
    private val closes = AtomicInteger()
    private val retirement = DatabaseRetirement("test") { closes.incrementAndGet() }

    @Test fun `nothing closes before the generation retires`() {
        assertEquals(1, retirement.operation(0) { 1 })
        assertFalse(retirement.isClosed)
        assertEquals(0, closes.get())
    }

    @Test fun `an idle generation closes at retirement, once`() {
        retirement.retire()
        retirement.retire()
        assertTrue(retirement.isClosed)
        assertEquals(1, closes.get())
    }

    @Test fun `a running operation delays the close until it ends`() {
        assertTrue(retirement.enter())
        retirement.retire()
        assertFalse(retirement.isClosed)
        // Still open for work of the retiring generation that is under way.
        assertEquals(2, retirement.operation(0) { 2 })
        assertFalse(retirement.isClosed)
        retirement.exit()
        assertTrue(retirement.isClosed)
        assertEquals(1, closes.get())
    }

    @Test fun `a late call after the close never runs and gets its fallback`() {
        retirement.retire()
        var ran = false
        assertEquals(-1, retirement.operation(-1) { ran = true; 1 })
        assertFalse(ran)
        assertFalse(retirement.enter())
        assertEquals(1, closes.get())
    }

    @Test fun `a failing operation is still counted out`() {
        runCatching { retirement.operation(0) { error("boom") } }
        retirement.retire()
        assertTrue(retirement.isClosed)
    }

    @Test fun `a failing close does not reach the caller`() {
        val failing = DatabaseRetirement("failing") { error("close failed") }
        failing.retire()
        assertTrue(failing.isClosed)
    }

    @Test fun `no operation is inside when the database closes`() {
        val inside = AtomicInteger()
        val violations = AtomicInteger()
        val guarded = DatabaseRetirement("concurrent") { if (inside.get() != 0) violations.incrementAndGet() }
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        repeat(8) {
            pool.execute {
                start.await()
                repeat(2_000) {
                    guarded.operation(Unit) {
                        inside.incrementAndGet()
                        inside.decrementAndGet()
                    }
                }
            }
        }
        start.countDown()
        Thread.sleep(5)
        guarded.retire()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertTrue(guarded.isClosed)
        assertEquals(0, violations.get())
    }
}
