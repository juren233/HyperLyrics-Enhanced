/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.connection

import java.util.concurrent.ConcurrentHashMap

internal class ConnectionRegistry<K, C : RemoteConnection<K>> {

    private val connections = ConcurrentHashMap<K, C>()
    private var retired = false

    @Synchronized
    fun closeAll(retire: Boolean = true) {
        retired = retired || retire
        val errors = connections.values.toList().mapNotNull { connection ->
            runCatching { unregister(connection) }.exceptionOrNull()
        }
        if (errors.isNotEmpty()) throw IllegalStateException("Connection retirement failed", errors.first())
    }

    @Synchronized
    fun register(connection: C): C {
        if (retired) {
            connection.close()
            error("Connection registry has been retired")
        }
        val existing = connections.putIfAbsent(connection.key, connection)
        if (existing != null) return existing

        connection.setDeathRecipient { unregister(connection) }
        return connection
    }

    fun unregister(key: K): C? {
        val removed = connections.remove(key) ?: return null
        removed.close()
        return removed
    }

    fun unregister(connection: C): Boolean {
        val removed = connections.remove(connection.key, connection)
        if (removed) connection.close()
        return removed
    }

    fun get(key: K): C? = connections[key]
}
