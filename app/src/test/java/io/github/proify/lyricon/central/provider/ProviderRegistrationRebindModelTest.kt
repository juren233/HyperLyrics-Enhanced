/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider

import io.github.proify.lyricon.central.connection.ConnectionRegistry
import io.github.proify.lyricon.central.connection.RemoteConnection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Controlled model of the provider 0.1.70 classes.jar bytecode, not device evidence.
 * javap: RemoteServiceProxy.bindRemoteService first calls disconnect(DEFAULT), which
 * invokes the previous IRemoteService.disconnect(), then reads incoming.getPlayer().
 * ProviderDirectory currently returns registry.get(info) for a duplicate registration.
 * These tests document why connection identity/close logging is needed before changing
 * that lifecycle. They deliberately do not claim the user's trace took this path.
 */
class ProviderRegistrationRebindModelTest {
    @Test
    fun `first registration returns an open player`() {
        val registry = ConnectionRegistry<String, Connection>()
        val first = registry.register(Connection("kuwo"))
        val sdk = RebindModel(registry)
        sdk.bind(first)
        assertSame(first, sdk.player)
        assertFalse(first.closed)
    }

    @Test
    fun `returning the same service on duplicate registration closes the incoming player`() {
        val registry = ConnectionRegistry<String, Connection>()
        val first = registry.register(Connection("kuwo"))
        val sdk = RebindModel(registry)
        sdk.bind(first)
        val reused = requireNotNull(registry.get("kuwo"))
        sdk.bind(reused)
        assertTrue(first.closed)
        assertNull(sdk.player)
        assertNull(registry.get("kuwo"))
    }

    @Test
    fun `an old disconnected service cannot unregister a distinct replacement`() {
        val registry = ConnectionRegistry<String, Connection>()
        val old = registry.register(Connection("kuwo"))
        val sdk = RebindModel(registry)
        sdk.bind(old)
        registry.unregister(old)
        val replacement = registry.register(Connection("kuwo"))
        sdk.bind(replacement)
        assertSame(replacement, sdk.player)
        assertSame(replacement, registry.get("kuwo"))
        assertFalse(replacement.closed)
    }

    private class Connection(override val key: String) : RemoteConnection<String> {
        var closed = false
        override fun setDeathRecipient(onDeath: (() -> Unit)?) = Unit
        override fun close() { closed = true }
    }

    private class RebindModel(private val registry: ConnectionRegistry<String, Connection>) {
        private var service: Connection? = null
        var player: Connection? = null
        fun bind(incoming: Connection) {
            service?.let { registry.unregister(it) }
            service = incoming
            player = incoming.takeUnless { it.closed }
        }
    }
}
