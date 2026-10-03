/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider

import io.github.proify.lyricon.central.connection.ConnectionRegistry
import io.github.proify.lyricon.central.ProviderFlowDiagnostics
import io.github.proify.lyricon.central.provider.player.ActivePlayerCoordinator
import io.github.proify.lyricon.provider.IProviderBinder
import io.github.proify.lyricon.provider.ProviderInfo

internal class ProviderDirectory(
    private val activePlayers: ActivePlayerCoordinator
) {
    private val registry = ConnectionRegistry<ProviderInfo, ProviderConnection>()

    fun getOrCreate(binder: IProviderBinder, info: ProviderInfo): ProviderConnection {
        val existing = registry.get(info)
        if (existing != null) {
            ProviderFlowDiagnostics.log("connection_reuse", info) {
                "${existing.diagnosticState()}, sameBinder=${existing.hasBinder(binder)}, " +
                    "incomingBinder=${ProviderFlowDiagnostics.id(binder.asBinder())}"
            }
            return existing
        }
        val candidate = ProviderConnection(binder, info, activePlayers)
        val registered = registry.register(candidate)
        ProviderFlowDiagnostics.log("connection_register", info) {
            "${registered.diagnosticState()}, candidate=${ProviderFlowDiagnostics.id(candidate)}, " +
                "accepted=${registered === candidate}"
        }
        return registered
    }

    fun closeAll(retire: Boolean = true) = registry.closeAll(retire)

    fun unregister(connection: ProviderConnection) {
        val removed = registry.unregister(connection)
        ProviderFlowDiagnostics.log("connection_unregister", connection.providerInfo) {
            "${connection.diagnosticState()}, removed=$removed"
        }
    }
}
