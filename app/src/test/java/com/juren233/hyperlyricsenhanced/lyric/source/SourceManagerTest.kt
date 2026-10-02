/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.source

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.common.HyperLogger
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SourceManagerTest {
    private class Source(override val id: String) : LyricSource {
        override val displayName = id
        val sinks = mutableListOf<LyricSink>()
        var startFailure = false
        var stopFailure = false
        var starts = 0
        override fun isAvailable() = true
        override fun start(sink: LyricSink) {
            starts++
            sinks += sink
            if (startFailure) error("start failure")
            sink.onPlainText("start:$id")
        }
        override fun stop() {
            sinks.lastOrNull()?.onStop()
            if (stopFailure) error("stop failure")
        }
    }

    private class Fixture {
        val a = Source("A")
        val b = Source("B")
        val events = mutableListOf<String>()
        private val preferences = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name == "getString") args[1] else error(method.name)
        } as SharedPreferences
        private val logger = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(HyperLogger::class.java)) { _, _, _ -> null } as HyperLogger
        private val sink = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(LyricSink::class.java)) { _, method, args ->
            when (method.name) {
                "onPlainText" -> events.add(args[0] as String)
                "onStop" -> events.add("stop")
            }
            null
        } as LyricSink
        val manager = SourceManager(listOf(a, b), preferences, sink, "source", "A", logger)
    }

    @Test fun `returning to a source rejects its previous activation callbacks`() {
        val f = Fixture()
        f.manager.start()
        val old = f.a.sinks.single()
        f.manager.switchSource("B")
        f.manager.switchSource("A")
        f.events.clear()
        old.onPlainText("stale")
        old.onStop()
        f.a.sinks.last().onPlainText("current")
        assertEquals(listOf("current"), f.events)
    }

    @Test fun `stop and restart also invalidates previous activation`() {
        val f = Fixture()
        f.manager.start()
        val old = f.a.sinks.single()
        f.manager.stop()
        f.manager.start()
        f.events.clear()
        old.onPlainText("stale")
        assertTrue(f.events.isEmpty())
    }

    @Test fun `startup failure clears active source and permits retry`() {
        val f = Fixture()
        f.a.startFailure = true
        assertThrows(IllegalStateException::class.java) { f.manager.start() }
        assertNull(f.manager.getActiveSource())
        f.a.startFailure = false
        f.manager.start()
        assertEquals(2, f.a.starts)
        assertSame(f.a, f.manager.getActiveSource())
    }

    @Test fun `switch startup failure permits retry without reviving old source`() {
        val f = Fixture()
        f.manager.start()
        f.b.startFailure = true
        assertThrows(IllegalStateException::class.java) { f.manager.switchSource("B") }
        assertNull(f.manager.getActiveSource())
        f.events.clear()
        f.a.sinks.single().onPlainText("stale")
        assertTrue(f.events.isEmpty())
        f.b.startFailure = false
        f.manager.switchSource("B")
        assertEquals(2, f.b.starts)
    }

    @Test fun `stop failure still releases active source`() {
        val f = Fixture()
        f.manager.start()
        f.a.stopFailure = true
        assertThrows(IllegalStateException::class.java) { f.manager.stop() }
        assertNull(f.manager.getActiveSource())
        f.events.clear()
        f.a.sinks.single().onPlainText("stale")
        assertTrue(f.events.isEmpty())
    }

    @Test fun `synchronous startup content and stop are preserved`() {
        val f = Fixture()
        f.manager.start()
        f.manager.stop()
        assertEquals(listOf("start:A", "stop"), f.events)
    }

    @Test fun `selecting the active source does not invalidate its callbacks`() {
        val f = Fixture()
        f.manager.start()
        f.manager.switchSource("A")
        f.events.clear()
        f.a.sinks.single().onPlainText("current")
        assertEquals(listOf("current"), f.events)
        assertEquals(1, f.a.starts)
    }
}
