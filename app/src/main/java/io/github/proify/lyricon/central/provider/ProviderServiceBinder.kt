/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider

import android.os.IBinder
import android.os.SharedMemory
import io.github.proify.lyricon.central.CentralRuntime
import io.github.proify.lyricon.central.ProviderFlowDiagnostics
import io.github.proify.lyricon.central.provider.player.PlayerBinder
import io.github.proify.lyricon.provider.IRemotePlayer
import io.github.proify.lyricon.provider.IRemoteService

/**
 * The provider reaches this service and its player only through [ProviderConnection]'s relays:
 * [getPlayer] hands out the player relay, never the [PlayerBinder] of this generation.
 */
internal class ProviderServiceBinder(
    private var connection: ProviderConnection?,
    private val playerRelay: IBinder,
    adoptedMemory: SharedMemory? = null,
) : IRemoteService.Stub() {

    var player: PlayerBinder? = connection?.let {
        PlayerBinder(it.providerInfo, CentralRuntime.activePlayers, adoptedMemory)
    }
        private set

    override fun getPlayer(): IRemotePlayer? {
        val result = player
        ProviderFlowDiagnostics.log("service_get_player", connection?.providerInfo) {
            "service=${ProviderFlowDiagnostics.id(this)}, playerBinder=${ProviderFlowDiagnostics.id(result)}, " +
                "connection=${ProviderFlowDiagnostics.id(connection)}"
        }
        return result?.let { IRemotePlayer.Stub.asInterface(playerRelay) }
    }

    /** With [keepMemory] the player's position memory passes to the next generation. */
    fun close(keepMemory: Boolean = false) {
        ProviderFlowDiagnostics.log("service_close", connection?.providerInfo) {
            "service=${ProviderFlowDiagnostics.id(this)}, playerBinder=${ProviderFlowDiagnostics.id(player)}, " +
                "keepMemory=$keepMemory"
        }
        player?.close(keepMemory)
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
