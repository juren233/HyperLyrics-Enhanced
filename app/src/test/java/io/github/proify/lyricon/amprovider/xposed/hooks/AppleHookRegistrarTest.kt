/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Executable
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class AppleHookRegistrarTest {

    @Test
    fun `callback tracer reports only the first callback for each module`() {
        val firstCallbacks = mutableListOf<String>()
        val scopes = mutableListOf<String>()
        var delegateCalls = 0
        val tracer = AppleHookCallbackTracer(
            withModule = { moduleId, block ->
                scopes += "enter:$moduleId"
                try {
                    block()
                } finally {
                    scopes += "exit:$moduleId"
                }
            },
            onFirstCallback = { moduleId, executable ->
                firstCallbacks += "$moduleId:${executable.name}"
            },
        )
        val delegate = object : Hooker {
            override fun intercept(chain: Chain): Any? {
                delegateCalls += 1
                return chain.proceed()
            }
        }
        val firstModuleHook = tracer.wrap("module-a", TEST_EXECUTABLE, delegate)
        val secondModuleHook = tracer.wrap("module-b", TEST_EXECUTABLE, delegate)

        assertEquals("original", firstModuleHook.intercept(chain()))
        assertEquals("original", firstModuleHook.intercept(chain()))
        assertEquals("original", secondModuleHook.intercept(chain()))

        assertEquals(listOf("module-a:toString", "module-b:toString"), firstCallbacks)
        assertEquals(3, delegateCalls)
        assertEquals(
            listOf(
                "enter:module-a",
                "exit:module-a",
                "enter:module-a",
                "exit:module-a",
                "enter:module-b",
                "exit:module-b",
            ),
            scopes,
        )
    }

    private fun chain(): Chain = Proxy.newProxyInstance(
        Chain::class.java.classLoader,
        arrayOf(Chain::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "getExecutable" -> TEST_EXECUTABLE
            "getThisObject" -> null
            "getArgs" -> emptyList<Any?>()
            "getArg" -> null
            "proceed", "proceedWith" -> "original"
            "toString" -> "TestChain"
            "hashCode" -> System.identityHashCode(this)
            "equals" -> arguments?.firstOrNull() === this
            else -> error("Unexpected Chain method: ${method.name}")
        }
    } as Chain

    private companion object {
        val TEST_EXECUTABLE: Executable = Any::class.java.getDeclaredMethod("toString")
    }
}
