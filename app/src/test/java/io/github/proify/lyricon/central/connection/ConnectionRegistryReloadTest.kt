/* Copyright 2026 juren233 */
package io.github.proify.lyricon.central.connection

import org.junit.Assert.*
import org.junit.Test

class ConnectionRegistryReloadTest {
    private class Connection(override val key: String, val failClose: Boolean = false) : RemoteConnection<String> {
        var closed = 0
        var onDeath: (() -> Unit)? = null
        override fun setDeathRecipient(onDeath: (() -> Unit)?) { this.onDeath = onDeath }
        override fun close() { closed++; if (failClose) error("dead remote") }
    }

    @Test fun `retirement closes every old connection even when one remote fails`() {
        val registry = ConnectionRegistry<String, Connection>()
        val first = registry.register(Connection("first", failClose = true))
        val second = registry.register(Connection("second"))
        assertThrows(IllegalStateException::class.java) { registry.closeAll() }
        registry.closeAll()
        assertNull(registry.get("first"))
        assertNull(registry.get("second"))
        assertEquals(1, first.closed)
        assertEquals(1, second.closed)
    }

    @Test fun `late registration cannot recreate the retired Central`() {
        val registry = ConnectionRegistry<String, Connection>()
        registry.closeAll()
        val late = Connection("late")
        assertThrows(IllegalStateException::class.java) { registry.register(late) }
        assertEquals(1, late.closed)
        assertNull(registry.get("late"))
    }

    @Test fun `disabling lyric display releases connections and permits later reenable`() {
        val registry = ConnectionRegistry<String, Connection>()
        val old = registry.register(Connection("apple"))
        val delayedDeath = old.onDeath!!
        registry.closeAll(retire = false)
        val replacement = registry.register(Connection("apple"))
        delayedDeath()
        assertEquals(1, old.closed)
        assertEquals(0, replacement.closed)
        assertSame(replacement, registry.get("apple"))
    }

    @Test fun `ordinary disconnect also rejects an old queued death callback`() {
        val registry = ConnectionRegistry<String, Connection>()
        val old = registry.register(Connection("apple"))
        registry.unregister(old)
        val replacement = registry.register(Connection("apple"))
        old.onDeath!!()
        assertSame(replacement, registry.get("apple"))
        assertEquals(0, replacement.closed)
    }

    @Test fun `reversible shutdown cannot reopen a retired runtime`() {
        val registry = ConnectionRegistry<String, Connection>()
        registry.closeAll()
        registry.closeAll(retire = false)
        assertThrows(IllegalStateException::class.java) { registry.register(Connection("apple")) }
    }

    @Test fun `failed remote close does not strand other connections during disable`() {
        val registry = ConnectionRegistry<String, Connection>()
        val broken = registry.register(Connection("broken", failClose = true))
        val other = registry.register(Connection("other"))
        assertThrows(IllegalStateException::class.java) { registry.closeAll(retire = false) }
        assertEquals(1, broken.closed)
        assertEquals(1, other.closed)
        assertNull(registry.get("other"))
        val replacement = registry.register(Connection("other"))
        assertSame(replacement, registry.get("other"))
    }
}
