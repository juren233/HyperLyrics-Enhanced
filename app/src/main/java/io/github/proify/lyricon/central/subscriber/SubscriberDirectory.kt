/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.subscriber

import io.github.proify.lyricon.central.connection.ConnectionRegistry
import io.github.proify.lyricon.subscriber.ISubscriberBinder
import io.github.proify.lyricon.subscriber.SubscriberInfo

internal class SubscriberDirectory {
    private val registry = ConnectionRegistry<SubscriberInfo, SubscriberConnection>()

    fun getOrCreate(binder: ISubscriberBinder, info: SubscriberInfo): SubscriberConnection {
        val existing = registry.get(info)
        if (existing != null) return existing
        return registry.register(SubscriberConnection(binder, info))
    }

    fun closeAll(retire: Boolean = true) = registry.closeAll(retire)

    fun unregister(connection: SubscriberConnection) {
        registry.unregister(connection)
    }
}
