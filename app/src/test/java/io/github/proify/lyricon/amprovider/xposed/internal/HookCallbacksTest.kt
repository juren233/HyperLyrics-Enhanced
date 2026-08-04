/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.internal

import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Executable
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HookCallbacksTest {

    @Test
    fun `callback hook preserves before proceed after order`() {
        val events = mutableListOf<String>()
        val hook = CallbackHook(
            before = { events += "before" },
            after = { _, result -> events += "after:$result" },
        )

        val result = hook.intercept(chain(result = "original") { _, _ ->
            events += "proceed"
        })

        assertEquals("original", result)
        assertEquals(listOf("before", "proceed", "after:original"), events)
    }

    @Test
    fun `scoped callback exits after success and original failure`() {
        val successEvents = mutableListOf<String>()
        val successHook = ScopedCallbackHook(
            enter = { successEvents += "enter"; true },
            after = { _, result -> successEvents += "after:$result" },
            exit = { successEvents += "exit" },
        )

        assertEquals(
            "original",
            successHook.intercept(chain(result = "original") { _, _ ->
                successEvents += "proceed"
            }),
        )
        assertEquals(
            listOf("enter", "proceed", "after:original", "exit"),
            successEvents,
        )

        val failureEvents = mutableListOf<String>()
        val failure = IllegalStateException("expected")
        val failureHook = ScopedCallbackHook(
            enter = { failureEvents += "enter"; true },
            after = { _, _ -> failureEvents += "after" },
            exit = { failureEvents += "exit" },
        )
        val caught = runCatching {
            failureHook.intercept(chain { _, _ ->
                failureEvents += "proceed"
                throw failure
            })
        }.exceptionOrNull()

        assertSame(failure, caught)
        assertEquals(listOf("enter", "proceed", "exit"), failureEvents)
    }

    @Test
    fun `scoped callback skips after and exit when enter declines`() {
        val events = mutableListOf<String>()
        val hook = ScopedCallbackHook(
            enter = { events += "enter"; false },
            after = { _, _ -> events += "after" },
            exit = { events += "exit" },
        )

        assertEquals(
            "original",
            hook.intercept(chain(result = "original") { _, _ -> events += "proceed" }),
        )
        assertEquals(listOf("enter", "proceed"), events)
    }

    @Test
    fun `conditional void skip avoids or continues the original call`() {
        var proceedCount = 0
        val chain = chain(result = "unexpected") { _, _ -> proceedCount += 1 }

        assertNull(ConditionalVoidSkipHook { true }.intercept(chain))
        assertEquals(0, proceedCount)

        assertEquals("unexpected", ConditionalVoidSkipHook { false }.intercept(chain))
        assertEquals(1, proceedCount)
    }

    @Test
    fun `result override receives and replaces the original result`() {
        var observedOriginal: Any? = null
        val hook = ResultOverrideHook { _, original ->
            observedOriginal = original
            "replacement"
        }

        assertEquals("replacement", hook.intercept(chain(result = "original")))
        assertEquals("original", observedOriginal)
    }

    @Test
    fun `argument rewrite selects the matching proceed overload`() {
        val calls = mutableListOf<Array<Any?>?>()
        val chain = chain(result = "result") { rewritten, _ -> calls += rewritten }

        assertEquals("result", ArgumentRewriteHook { null }.intercept(chain))
        assertEquals("result", ArgumentRewriteHook { arrayOf("new", 2) }.intercept(chain))

        assertNull(calls[0])
        assertEquals(listOf("new", 2), calls[1]?.toList())
    }

    private fun chain(
        result: Any? = null,
        onProceed: (rewrittenArgs: Array<Any?>?, receiver: Any?) -> Unit = { _, _ -> },
    ): Chain {
        return Proxy.newProxyInstance(
            Chain::class.java.classLoader,
            arrayOf(Chain::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getExecutable" -> TEST_EXECUTABLE
                "getThisObject" -> null
                "getArgs" -> emptyList<Any?>()
                "getArg" -> null
                "proceed" -> {
                    @Suppress("UNCHECKED_CAST")
                    val rewritten = arguments?.singleOrNull() as? Array<Any?>
                    onProceed(rewritten, null)
                    result
                }
                "proceedWith" -> {
                    val receiver = arguments?.firstOrNull()
                    @Suppress("UNCHECKED_CAST")
                    val rewritten = arguments?.getOrNull(1) as? Array<Any?>
                    onProceed(rewritten, receiver)
                    result
                }
                "toString" -> "TestChain"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> arguments?.firstOrNull() === this
                else -> error("Unexpected Chain method: ${method.name}")
            }
        } as Chain
    }

    private companion object {
        val TEST_EXECUTABLE: Executable = Any::class.java.getDeclaredMethod("toString")
    }
}
