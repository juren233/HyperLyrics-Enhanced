/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.dexkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DexMethodWatchdogTest {
    @Test
    fun `records resolved hook callback and valid business result`() {
        val events = mutableListOf<DexWatchdogEvent>()
        val watchdog = DexMethodWatchdog(events::add)
        watchdog.register("lyrics", "runtime-lyrics")
        watchdog.resolved(
            cacheKey = "lyrics",
            source = DexResolutionSource.DEXKIT,
            cacheWritten = true,
            target = "example.Target#lyrics():void",
        )
        watchdog.hookInstalled("lyrics", "example.Target#lyrics():void")
        watchdog.callback("lyrics")
        watchdog.validation("lyrics", valid = true, detail = "lines=42")
        watchdog.timeout("lyrics")

        assertEquals(
            listOf("resolved", "hookInstalled", "firstCallback", "firstProbe", "firstValid"),
            events.map(DexWatchdogEvent::stage),
        )
        val snapshot = requireNotNull(watchdog.snapshot("lyrics"))
        assertTrue(snapshot.cacheWritten)
        assertTrue(snapshot.hookInstalled)
        assertTrue(snapshot.validObserved)
        assertEquals(1L, snapshot.callbackCount)
        assertEquals(1L, snapshot.validationCount)
    }


    @Test
    fun `records hook installation failure without turning it into timeout`() {
        val events = mutableListOf<DexWatchdogEvent>()
        val watchdog = DexMethodWatchdog(events::add)
        watchdog.register("method", "runtime-method")
        watchdog.resolved(
            cacheKey = "method",
            source = DexResolutionSource.CACHE,
            cacheWritten = false,
            target = "example.Target#method():void",
        )
        watchdog.hookInstallFailed(
            cacheKey = "method",
            target = "example.Target#method():void",
            detail = "IllegalStateException: invalid target",
        )
        watchdog.timeout("method")

        assertTrue(events.any { it.stage == "hookInstallFailed" })
        assertFalse(events.any { it.stage == "timeout" })
        assertEquals("failed", events.first { it.stage == "hookInstallFailed" }.result)
    }

    @Test
    fun `distinguishes unused target from callback without business validation`() {
        val events = mutableListOf<DexWatchdogEvent>()
        val watchdog = DexMethodWatchdog(events::add)
        watchdog.register("unused", "runtime-unused")
        watchdog.resolved(
            cacheKey = "unused",
            source = DexResolutionSource.CACHE,
            cacheWritten = false,
            target = "example.Target#unused():void",
        )
        watchdog.timeout("unused")
        watchdog.register("active", "runtime-active")
        watchdog.resolved(
            cacheKey = "active",
            source = DexResolutionSource.PREFERRED_TARGET,
            cacheWritten = true,
            target = "example.Target#active():void",
        )
        watchdog.callback("active")
        watchdog.timeout("active")

        val timeouts = events.filter { it.stage == "timeout" }
        assertEquals("not_exercised", timeouts[0].result)
        assertEquals("unverified_after_activity", timeouts[1].result)
    }

    @Test
    fun `invalid business result is evidence and does not become timeout`() {
        val events = mutableListOf<DexWatchdogEvent>()
        val watchdog = DexMethodWatchdog(events::add)
        watchdog.register("queue", "runtime-queue")
        watchdog.resolved(
            cacheKey = "queue",
            source = DexResolutionSource.DEXKIT,
            cacheWritten = true,
            target = "example.Target#queue():java.lang.Object",
        )
        watchdog.validation("queue", valid = false, detail = "next=null")
        watchdog.timeout("queue")

        assertTrue(events.any { it.stage == "firstProbe" && it.result == "invalid" })
        assertFalse(events.any { it.stage == "timeout" })
    }
}
