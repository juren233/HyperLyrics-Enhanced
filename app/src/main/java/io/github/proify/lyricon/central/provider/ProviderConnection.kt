/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider

import android.os.IBinder
import android.os.SharedMemory
import android.util.Log
import com.juren233.hyperlyricsenhanced.root.reload.GenerationRelay
import io.github.proify.lyricon.central.ProviderFlowDiagnostics
import io.github.proify.lyricon.central.connection.RemoteConnection
import io.github.proify.lyricon.central.provider.player.ActivePlayerCoordinator
import io.github.proify.lyricon.provider.IProviderBinder
import io.github.proify.lyricon.provider.IRemoteService
import io.github.proify.lyricon.provider.ProviderInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One registered provider. The provider is given [remoteService], whose Binder is a process-lifetime
 * relay; it reaches [service] and its player only through the two relays. A provider in another
 * process therefore never holds a Binder of this module generation, and a hot reload can hand the
 * relays to the next generation ([ProviderHandover]) without the provider reconnecting: the Lyricon
 * provider library re-registers only after the central's Binder dies, which a reload never causes.
 */
internal class ProviderConnection(
    private var binder: IProviderBinder?,
    val providerInfo: ProviderInfo,
    private val activePlayers: ActivePlayerCoordinator,
    private val serviceRelay: IBinder = GenerationRelay.create(SERVICE_HELD_CODES),
    private val playerRelay: IBinder = GenerationRelay.create(PLAYER_HELD_CODES),
    adoptedMemory: SharedMemory? = null,
) : RemoteConnection<ProviderInfo> {

    override val key: ProviderInfo get() = providerInfo

    val service = ProviderServiceBinder(this, playerRelay, adoptedMemory)

    /** What the provider is given at registration. */
    val remoteService: IRemoteService = IRemoteService.Stub.asInterface(serviceRelay)

    private var deathRecipient: IBinder.DeathRecipient? = null
    private val closed = AtomicBoolean(false)

    fun diagnosticState(): String =
        "connection=${ProviderFlowDiagnostics.id(this)}, service=${ProviderFlowDiagnostics.id(service)}, " +
            "providerBinder=${ProviderFlowDiagnostics.id(binder?.asBinder())}, closed=${closed.get()}"

    fun hasBinder(candidate: IProviderBinder): Boolean = binder?.asBinder() == candidate.asBinder()

    /**
     * Once registered: from here on the provider's calls reach this generation. Returns how many
     * calls the relays held during a hot reload and replayed first.
     */
    fun attachRelays(): Int {
        if (closed.get()) return 0
        val player = service.player?.let { GenerationRelay.attach(playerRelay, it) } ?: 0
        // A held disconnect closes this connection while it replays: nothing is left to attach.
        return player + GenerationRelay.attach(serviceRelay, service)
    }

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
        val remote = handOverTarget()
        if (remote != null) {
            // Until the next generation attaches, its relays keep the provider's latest calls.
            GenerationRelay.hold(serviceRelay)
            GenerationRelay.hold(playerRelay)
        } else {
            GenerationRelay.attach(serviceRelay, null)
            GenerationRelay.attach(playerRelay, null)
        }
        val handedOver = remote != null && handOver(remote)
        service.close(keepMemory = handedOver)
        setDeathRecipient(null)
        binder = null
        activePlayers.notifyProviderInvalid(providerInfo)
    }

    /**
     * During a hot reload, passes a provider in another process to the next generation. A provider
     * inside SystemUI is not passed on: its Binder belongs to the retiring generation's plugin, and
     * the next generation's plugin registers again by itself.
     */
    private fun handOverTarget(): IBinder? {
        if (!ProviderHandover.isHandingOver() || service.player == null) return null
        return binder?.asBinder()?.takeIf { it !is android.os.Binder && it.isBinderAlive }
    }

    private fun handOver(remote: IBinder): Boolean {
        val player = service.player ?: return false
        return ProviderHandover.recordOutgoing(
            providerBinder = remote,
            providerInfo = providerInfo,
            serviceRelay = serviceRelay,
            playerRelay = playerRelay,
            positionMemory = player.getPositionMemory(),
            playerState = player.handoverState(),
        ).also { recorded ->
            if (recorded) ProviderFlowDiagnostics.log("connection_handed_over", providerInfo) { diagnosticState() }
        }
    }

    override fun toString() = "ProviderConnection{$providerInfo}"

    internal companion object {
        private const val TAG = "ProviderConnection"
        private const val FIRST = IBinder.FIRST_CALL_TRANSACTION

        /**
         * The provider library's setters, whose latest call replaces earlier ones (codes follow the
         * AIDL method order; ProviderRelayCodesTest checks them against the library). Not held:
         * getPositionMemory, which the caller needs answered.
         */
        val PLAYER_HELD_CODES = intArrayOf(
            FIRST + 0, // setSong
            FIRST + 1, // setPlaybackState
            FIRST + 2, // seekTo
            FIRST + 3, // sendText
            FIRST + 4, // setPositionUpdateInterval
            FIRST + 5, // setDisplayTranslation
            FIRST + 7, // setDisplayRoma
            FIRST + 8, // setPlaybackState2
        )

        /** disconnect; not getPlayer, which the caller needs answered. */
        val SERVICE_HELD_CODES = intArrayOf(FIRST + 1)
    }
}
