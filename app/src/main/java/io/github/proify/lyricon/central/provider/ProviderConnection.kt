/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider

import android.os.IBinder
import android.util.Log
import io.github.proify.lyricon.central.ProviderFlowDiagnostics
import io.github.proify.lyricon.central.connection.RemoteConnection
import io.github.proify.lyricon.central.provider.player.ActivePlayerCoordinator
import io.github.proify.lyricon.provider.IProviderBinder
import io.github.proify.lyricon.provider.ProviderInfo
import java.util.concurrent.atomic.AtomicBoolean

internal class ProviderConnection(
    private var binder: IProviderBinder?,
    val providerInfo: ProviderInfo,
    private val activePlayers: ActivePlayerCoordinator
) : RemoteConnection<ProviderInfo> {

    override val key: ProviderInfo get() = providerInfo

    val service = ProviderServiceBinder(this)

    private var deathRecipient: IBinder.DeathRecipient? = null
    private val closed = AtomicBoolean(false)

    fun diagnosticState(): String =
        "connection=${ProviderFlowDiagnostics.id(this)}, service=${ProviderFlowDiagnostics.id(service)}, " +
            "providerBinder=${ProviderFlowDiagnostics.id(binder?.asBinder())}, closed=${closed.get()}"

    fun hasBinder(candidate: IProviderBinder): Boolean = binder?.asBinder() == candidate.asBinder()

    override fun setDeathRecipient(onDeath: (() -> Unit)?) {
        val next = onDeath?.let {
            IBinder.DeathRecipient {
                ProviderFlowDiagnostics.log("provider_binder_died", providerInfo) { diagnosticState() }
                it()
            }
        }

        deathRecipient?.runCatching {
            binder?.asBinder()?.unlinkToDeath(this, 0)
        }?.onFailure {
            Log.e(TAG, "unlink to death failed", it)
        }

        next?.runCatching {
            binder?.asBinder()?.linkToDeath(this, 0)
        }?.onFailure {
            Log.e(TAG, "link to death failed", it)
        }

        deathRecipient = next
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ProviderFlowDiagnostics.log("connection_close", providerInfo) {
            "${diagnosticState()}, path=${ProviderFlowDiagnostics.closePath()}"
        }
        service.close()
        setDeathRecipient(null)
        binder = null
        activePlayers.notifyProviderInvalid(providerInfo)
    }

    override fun toString() = "ProviderConnection{$providerInfo}"

    private companion object {
        private const val TAG = "ProviderConnection"
    }
}
