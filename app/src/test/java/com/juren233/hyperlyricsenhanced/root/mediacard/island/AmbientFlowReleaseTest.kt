/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.island

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AmbientFlowReleaseTest {
    @Test fun `release entry delegates invalidation cleanup and state clearing to ordering helper`() {
        val relative = "src/main/java/com/juren233/hyperlyricsenhanced/root/mediacard/island/IslandExpandedMediaAmbientFlowHooker.kt"
        val source = listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
        val release = source.substringAfter("fun releaseAll() {").substringBefore("private enum class Action")
        // Wiring guard only; queue behavior is covered separately below.
        assertTrue(release.contains("releaseAmbientFlowResources("))
        assertTrue(release.contains("invalidateRequests = {"))
        assertTrue(release.contains("binderStates.values.forEach { it.request.incrementAndGet() }"))
        assertTrue(release.contains("cleanupViews = { cleanup.run() }"))
        assertTrue(release.contains("clearStates = { binderStates.clear() }"))
        assertEquals(1, Regex("binderStates\\.clear\\(\\)").findAll(release).count())
        // P5：全局图标取色路径必须在释放时失效，旧封面色不得在释放后落地。
        assertTrue(release.contains("iconColorRequest.incrementAndGet()"))
        assertTrue(release.contains("iconColorToken = null"))
        assertTrue(release.contains("iconColorPalette = null"))
    }

    private class Fixture {
        val queue = ArrayDeque<Runnable>()
        val states = mutableMapOf("binder" to "view")
        val attachedViews = mutableSetOf("view")
        var generation = 0
        var shutdown = false
        var cleanupCalls = 0
        val events = mutableListOf<String>()

        fun release(immediate: Boolean = false) = releaseAmbientFlowResources(
            dispatchCleanup = { if (immediate) it.run() else queue.addLast(it) },
            invalidateRequests = { generation++; events.add("invalidate") },
            cleanupViews = {
                events.add("cleanup")
                cleanupCalls++
                // Mirrors removeCustomFlow: the resource can only be located through state.
                states["binder"]?.let { attachedViews.remove(it) }
            },
            clearStates = { events.add("clear"); states.clear() },
            shutdownWorker = { shutdown = true },
        )
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    @Test fun `background release retains state until queued view cleanup runs`() {
        val f = Fixture()
        f.release()
        assertTrue(f.states.isNotEmpty())
        assertEquals(0, f.cleanupCalls)
        assertTrue(f.shutdown)
        f.drain()
        assertTrue(f.attachedViews.isEmpty())
        assertTrue(f.states.isEmpty())
    }

    @Test fun `old result is invalid before main queue is drained`() {
        val f = Fixture()
        val oldRequest = f.generation
        f.release()
        assertFalse(f.states.containsKey("binder") && oldRequest == f.generation)
        assertTrue(f.states.isNotEmpty())
        f.drain()
    }

    @Test fun `main thread release cleans before clearing without posting`() {
        val f = Fixture()
        f.release(immediate = true)
        assertTrue(f.queue.isEmpty())
        assertTrue(f.attachedViews.isEmpty())
        assertTrue(f.states.isEmpty())
        assertTrue(f.shutdown)
        assertEquals(listOf("invalidate", "cleanup", "clear"), f.events)
    }

    @Test fun `repeated queued releases leave no attached resources`() {
        val f = Fixture()
        f.release()
        f.release()
        f.drain()
        assertTrue(f.attachedViews.isEmpty())
        assertTrue(f.states.isEmpty())
        f.release(immediate = true)
        assertTrue(f.attachedViews.isEmpty())
    }

    @Test fun `empty release still closes worker`() {
        val f = Fixture()
        f.states.clear()
        f.attachedViews.clear()
        f.release()
        f.drain()
        assertTrue(f.shutdown)
        assertTrue(f.states.isEmpty())
    }
}
