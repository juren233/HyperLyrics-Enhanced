/* Copyright 2026 juren233 */
package io.github.proify.lyricon.central.provider

import android.os.IBinder
import android.os.SharedMemory
import android.util.Log
import com.juren233.hyperlyricsenhanced.root.reload.GenerationRelay
import io.github.proify.lyricon.central.ProviderFlowDiagnostics
import io.github.proify.lyricon.central.json
import io.github.proify.lyricon.provider.IProviderBinder
import io.github.proify.lyricon.provider.ProviderInfo

/**
 * Passes the central's connections to providers in other processes from a retiring module
 * generation to the next, through the hot reload snapshot (JDK and framework values only).
 *
 * Each row carries the provider's own Binder, its info, the two relays it holds, the position
 * memory it writes to, and its last inputs. The next generation adopts the rows when its central
 * activates: it rebuilds the connections on the same relays and memory, replays the inputs, and
 * only then attaches the relays. While no generation is attached the relays keep the provider's
 * latest setter calls (one per method, user decision 2026-11-23) and replay them right after the
 * inherited state; calls that need an answer are dropped. Relays a row's adoption never reaches
 * are detached, which discards what they held.
 */
internal object ProviderHandover {
    private const val TAG = "ProviderHandover"
    private const val KEY_BINDER = "providerBinder"
    private const val KEY_INFO = "providerInfo"
    private const val KEY_SERVICE_RELAY = "serviceRelay"
    private const val KEY_PLAYER_RELAY = "playerRelay"
    private const val KEY_MEMORY = "positionMemory"
    private const val KEY_PLAYER_STATE = "playerState"

    /** Retiring generation: the list in its snapshot, filled while its connections close. */
    @Volatile
    private var outgoing: ArrayList<HashMap<String, Any?>>? = null

    /** Next generation: rows received but not adopted yet (its central may still be on standby). */
    private val incoming = ArrayList<Map<*, *>>()

    /**
     * Retiring generation, before teardown. Rows this generation itself inherited but never adopted
     * (reloaded again before its central activated) are passed on unchanged.
     */
    fun snapshotForReload(): ArrayList<HashMap<String, Any?>> {
        val rows = ArrayList<HashMap<String, Any?>>()
        synchronized(incoming) {
            incoming.forEach { row -> rows += HashMap<String, Any?>().apply { row.forEach { (k, v) -> put(k as String, v) } } }
            incoming.clear()
        }
        outgoing = rows
        return rows
    }

    /** Retiring generation, inside a connection's close(): whether a reload is handing over. */
    fun isHandingOver(): Boolean = outgoing != null

    /** Retiring generation, inside a connection's close(): false when no reload is handing over. */
    fun recordOutgoing(
        providerBinder: IBinder,
        providerInfo: ProviderInfo,
        serviceRelay: IBinder,
        playerRelay: IBinder,
        positionMemory: SharedMemory?,
        playerState: HashMap<String, Any?>,
    ): Boolean {
        val rows = outgoing ?: return false
        val row = hashMapOf<String, Any?>(
            KEY_BINDER to providerBinder,
            KEY_INFO to json.encodeToString(ProviderInfo.serializer(), providerInfo).toByteArray(Charsets.UTF_8),
            KEY_SERVICE_RELAY to serviceRelay,
            KEY_PLAYER_RELAY to playerRelay,
            KEY_MEMORY to positionMemory,
            KEY_PLAYER_STATE to playerState,
        )
        synchronized(rows) { rows += row }
        return true
    }

    /** Next generation, during restore, before its central initializes. */
    fun receive(value: Any?) {
        val rows = (value as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
        synchronized(incoming) {
            incoming.clear()
            incoming += rows
        }
    }

    /** Next generation, when its central activates: before any pending registration is replayed. */
    fun adoptIncoming(directory: ProviderDirectory) {
        val rows = synchronized(incoming) { incoming.toList().also { incoming.clear() } }
        rows.forEach { row ->
            val memory = row[KEY_MEMORY] as? SharedMemory
            val adopted = runCatching { adopt(directory, row, memory) }
                .onFailure { Log.e(TAG, "Inherited provider connection could not be adopted", it) }
                .getOrDefault(false)
            if (!adopted) release(row, memory)
        }
    }

    /** Next generation, when its central will not activate (a standalone central took over). */
    fun discardIncoming() {
        val rows = synchronized(incoming) { incoming.toList().also { incoming.clear() } }
        rows.forEach { release(it, it[KEY_MEMORY] as? SharedMemory) }
    }

    private fun release(row: Map<*, *>, memory: SharedMemory?) {
        listOf(row[KEY_SERVICE_RELAY], row[KEY_PLAYER_RELAY]).forEach { relay ->
            if (GenerationRelay.isRelay(relay)) runCatching { GenerationRelay.attach(relay as IBinder, null) }
        }
        memory?.close()
    }

    private fun adopt(directory: ProviderDirectory, row: Map<*, *>, memory: SharedMemory?): Boolean {
        val binder = row[KEY_BINDER] as? IBinder ?: return false
        val serviceRelay = row[KEY_SERVICE_RELAY]
        val playerRelay = row[KEY_PLAYER_RELAY]
        if (!binder.isBinderAlive || !GenerationRelay.isRelay(serviceRelay) || !GenerationRelay.isRelay(playerRelay)) {
            return false
        }
        val info = (row[KEY_INFO] as? ByteArray)
            ?.toString(Charsets.UTF_8)
            ?.let { json.decodeFromString(ProviderInfo.serializer(), it) }
            ?: return false
        val connection = directory.adopt(
            binder = IProviderBinder.Stub.asInterface(binder),
            info = info,
            serviceRelay = serviceRelay as IBinder,
            playerRelay = playerRelay as IBinder,
            adoptedMemory = memory,
        ) ?: return false
        // From here the connection owns the memory: a failed replay must not cost the provider it.
        runCatching { (row[KEY_PLAYER_STATE] as? Map<*, *>)?.let { connection.service.player?.replay(it) } }
            .onFailure { Log.e(TAG, "Inherited provider state could not be replayed", it) }
        val heldReplayed = connection.attachRelays()
        ProviderFlowDiagnostics.log("connection_adopted", info) {
            "${connection.diagnosticState()}, heldReplayed=$heldReplayed"
        }
        return true
    }
}
