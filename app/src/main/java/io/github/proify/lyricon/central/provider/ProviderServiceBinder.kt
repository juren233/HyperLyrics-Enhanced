/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider

import io.github.proify.lyricon.central.CentralRuntime
import io.github.proify.lyricon.central.ProviderFlowDiagnostics
import io.github.proify.lyricon.central.provider.player.PlayerBinder
import io.github.proify.lyricon.provider.IRemoteService

internal class ProviderServiceBinder(
    private var connection: ProviderConnection?
) : IRemoteService.Stub() {

    private var player: PlayerBinder? = connection?.let {
        PlayerBinder(it.providerInfo, CentralRuntime.activePlayers)
    }

    override fun getPlayer(): PlayerBinder? {
        val result = player
        ProviderFlowDiagnostics.log("service_get_player", connection?.providerInfo) {
            "service=${ProviderFlowDiagnostics.id(this)}, playerBinder=${ProviderFlowDiagnostics.id(result)}, " +
                "connection=${ProviderFlowDiagnostics.id(connection)}"
        }
        return result
    }

    fun close() {
        ProviderFlowDiagnostics.log("service_close", connection?.providerInfo) {
            "service=${ProviderFlowDiagnostics.id(this)}, playerBinder=${ProviderFlowDiagnostics.id(player)}"
        }
        player?.close()
        player = null
        connection = null
    }

    override fun disconnect() {
        ProviderFlowDiagnostics.log("service_disconnect", connection?.providerInfo) {
            "service=${ProviderFlowDiagnostics.id(this)}, connection=${ProviderFlowDiagnostics.id(connection)}, " +
                "path=${ProviderFlowDiagnostics.closePath()}"
        }
        connection?.let { CentralRuntime.providers.unregister(it) }
    }
}
