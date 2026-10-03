/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class IslandMediaOutputCtaReloadTest {
    @Test fun `old synchronous unhook path waits for the framework monitor`() {
        val monitor = Any()
        val attempted = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val bridge = bridgeWith(handle {
            attempted.countDown()
            synchronized(monitor) { Unit }
        })
        lateinit var main: Thread
        try {
            synchronized(monitor) {
                main = thread(name = "old-reload-main") {
                    try { bridge.release() } finally { completed.countDown() }
                }
                assertTrue(attempted.await(2, TimeUnit.SECONDS))
                assertFalse(completed.await(50, TimeUnit.MILLISECONDS))
            }
        } finally {
            main.join(2_000)
        }
        assertEquals(0L, completed.count)
    }

    @Test fun `main thread cleanup completes while framework owns old module monitor`() {
        val monitor = Any()
        val unhookCalls = AtomicInteger()
        val handle = handle { synchronized(monitor) { unhookCalls.incrementAndGet() } }
        val bridge = bridgeWith(handle)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        lateinit var main: Thread
        try {
            synchronized(monitor) {
                main = thread(name = "reload-main") {
                    try {
                        bridge.releaseForReload()
                    } catch (error: Throwable) {
                        failure.set(error)
                    } finally {
                        completed.countDown()
                    }
                }
                assertTrue("UI cleanup must not wait for the framework callback's monitor",
                    completed.await(2, TimeUnit.SECONDS))
            }
        } finally {
            main.join(2_000)
        }
        failure.get()?.let { throw AssertionError(it) }
        assertEquals(0, unhookCalls.get())
        assertTrue(handles(bridge).isEmpty())
        // Framework ownership survives clearing the old module's local references.
        handle.unhook()
        assertEquals(1, unhookCalls.get())
    }

    @Test fun `ordinary release still removes the installed hook`() {
        val unhookCalls = AtomicInteger()
        val bridge = bridgeWith(handle { unhookCalls.incrementAndGet() })
        bridge.release()
        bridge.release()
        assertEquals(1, unhookCalls.get())
        assertTrue(handles(bridge).isEmpty())
    }

    private fun bridgeWith(handle: HookHandle): IslandMediaOutputCtaBridge {
        val method = String::class.java.getMethod("length")
        return IslandMediaOutputCtaBridge(object : XposedModule() {}, method).also {
            handles(it)[method] = handle
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handles(bridge: IslandMediaOutputCtaBridge): MutableMap<Method, HookHandle> =
        IslandMediaOutputCtaBridge::class.java.getDeclaredField("handles").let {
            it.isAccessible = true
            it.get(bridge) as MutableMap<Method, HookHandle>
        }

    private fun handle(unhook: () -> Unit): HookHandle = Proxy.newProxyInstance(
        HookHandle::class.java.classLoader, arrayOf(HookHandle::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "unhook" -> { unhook(); null }
            else -> error("Unexpected HookHandle call: ${method.name}")
        }
    } as HookHandle
}
