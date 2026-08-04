/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadLocalStateTest {

    @Test
    fun `reentry guard is nested and exception safe`() {
        val guard = ThreadLocalReentryGuard()

        assertFalse(guard.isActive)
        guard.run {
            assertTrue(guard.isActive)
            guard.run {
                assertTrue(guard.isActive)
            }
            assertTrue(guard.isActive)
        }
        assertFalse(guard.isActive)

        runCatching {
            guard.run {
                assertTrue(guard.isActive)
                error("expected")
            }
        }
        assertFalse(guard.isActive)
    }

    @Test
    fun `stack restores the outer value after nested binding`() {
        val stack = ThreadLocalStack<String>()

        stack.push("outer")
        assertEquals("outer", stack.current)
        stack.push("inner")
        assertEquals("inner", stack.current)
        assertEquals("inner", stack.pop())
        assertEquals("outer", stack.current)
        assertEquals("outer", stack.pop())
        assertNull(stack.current)
        assertNull(stack.pop())
    }

    @Test
    fun `stack values are isolated between threads`() {
        val stack = ThreadLocalStack<String>()
        var workerInitial: String? = "unset"
        var workerValue: String? = null

        stack.push("main")
        val worker = Thread {
            workerInitial = stack.current
            stack.push("worker")
            workerValue = stack.pop()
        }
        worker.start()
        worker.join()

        assertNull(workerInitial)
        assertEquals("worker", workerValue)
        assertEquals("main", stack.pop())
        assertNull(stack.current)
    }
}
