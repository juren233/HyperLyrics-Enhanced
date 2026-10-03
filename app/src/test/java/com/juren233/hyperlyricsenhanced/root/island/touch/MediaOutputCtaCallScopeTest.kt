/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import org.junit.Assert.*
import org.junit.Test

class MediaOutputCtaCallScopeTest {
    private val scope = MediaOutputCtaCallScope<Any>()
    private val plugin = Any()
    private val context = Any()

    @Test fun `fills the null plugin application context only for the active plugin`() {
        assertNull(scope.argument(plugin, null))
        scope.invoke(plugin, context) {
            assertNull(scope.argument(Any(), null))
            assertSame(context, scope.argument(plugin, null))
            assertNull(scope.argument(plugin, null))
        }
        assertNull(scope.argument(plugin, null))
    }

    @Test fun `keeps a native nonnull argument and consumes only that check`() {
        val nativeContext = Any()
        scope.invoke(plugin, context) {
            assertSame(nativeContext, scope.argument(plugin, nativeContext))
            assertNull(scope.argument(plugin, null))
        }
    }

    @Test fun `real consent still determines both accepted and rejected results`() {
        for (agreed in listOf(false, true)) {
            var calls = 0
            fun originalCheck(input: Any?): Boolean {
                calls++
                // Same null branch as the original MiPlay plugin; otherwise read real consent.
                return input != null && agreed
            }
            assertFalse(originalCheck(null))
            val result = scope.invoke(plugin, context) { originalCheck(scope.argument(plugin, null)) }
            assertEquals(agreed, result)
            assertEquals(2, calls)
        }
    }

    @Test fun `exception cleanup never leaks into the next native click`() {
        val failure = IllegalStateException("native failure")
        try {
            scope.invoke(plugin, context) { throw failure }
            fail("Expected failure")
        } catch (caught: IllegalStateException) { assertSame(failure, caught) }
        assertNull(scope.argument(plugin, null))
    }

    @Test fun `nested calls restore the outer context and its consumed state`() {
        val nestedContext = Any()
        scope.invoke(plugin, context) {
            scope.invoke(plugin, nestedContext) { assertSame(nestedContext, scope.argument(plugin, null)) }
            assertSame(context, scope.argument(plugin, null))
            scope.invoke(plugin, nestedContext) { assertSame(nestedContext, scope.argument(plugin, null)) }
            assertNull(scope.argument(plugin, null))
        }
    }

    @Test fun `other threads and equal but distinct receivers cannot inherit the scope`() {
        val receiver = String(charArrayOf('a'))
        val equalReceiver = String(charArrayOf('a'))
        var otherThreadResult: Any? = context
        scope.invoke(receiver, context) {
            assertNull(scope.argument(equalReceiver, null))
            Thread { otherThreadResult = scope.argument(receiver, null) }.apply { start(); join() }
            assertNull(otherThreadResult)
            assertSame(context, scope.argument(receiver, null))
        }
    }

    @Test fun `release clears a pending call`() {
        scope.invoke(plugin, context) {
            scope.clear()
            assertNull(scope.argument(plugin, null))
        }
    }
}
