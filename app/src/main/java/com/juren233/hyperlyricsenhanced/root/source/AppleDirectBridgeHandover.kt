/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.source

import android.os.IBinder
import com.juren233.hyperlyricsenhanced.root.reload.GenerationRelay

/**
 * Apple Music holds one relay Binder for the whole SystemUI process. Each module generation attaches
 * its own [AppleMusicDirectBridge] to it, and a hot reload hands the relay plus the last Apple state
 * to the next generation, so Apple never has to re-register (MIUI Greezer denies that broadcast to a
 * cached Apple Music) and no Apple-held reference pins a retired generation.
 *
 * Handover policy (user decision 2026-11-22): calls that arrive while no generation is attached are
 * dropped; the new generation replays the last state it inherited. No timeout, retry or buffer.
 */
internal object AppleDirectBridgeHandover {
    private const val KEY_RELAY = "relay"
    private const val KEY_RECEIVER = "translationReceiver"
    private const val KEY_SONG = "songPayload"
    private const val KEY_PLAYING = "playing"
    private const val KEY_POSITION = "position"
    private const val KEY_HAS_TEXT = "hasText"
    private const val KEY_TEXT = "text"

    /** Last Apple state a retiring bridge passes on; JDK and framework types only. */
    class State(
        val translationReceiver: IBinder?,
        val songPayload: ByteArray?,
        val playing: Boolean?,
        val position: Long?,
        val hasText: Boolean,
        val text: String?,
    )

    @Volatile
    private var relay: IBinder? = null

    /** Filled by the retiring bridge during teardown; read by the next generation. */
    @Volatile
    private var outgoing: HashMap<String, Any?>? = null

    @Volatile
    private var incoming: State? = null

    fun relay(): IBinder = relay ?: synchronized(this) {
        relay ?: GenerationRelay.create().also { relay = it }
    }

    fun attach(target: IBinder) = GenerationRelay.attach(relay(), target)

    fun detach() {
        relay?.let { GenerationRelay.attach(it, null) }
    }

    /** Old generation, before teardown: the returned map is completed by the bridge's stop(). */
    fun snapshotForReload(): HashMap<String, Any?>? {
        val current = relay ?: return null
        return hashMapOf<String, Any?>(KEY_RELAY to current).also { outgoing = it }
    }

    /** Old generation, inside the bridge's stop(): non-null only while a hot reload is handing over. */
    fun recordOutgoing(state: State): Boolean {
        val target = outgoing ?: return false
        target[KEY_RECEIVER] = state.translationReceiver
        target[KEY_SONG] = state.songPayload
        target[KEY_PLAYING] = state.playing
        target[KEY_POSITION] = state.position
        target[KEY_HAS_TEXT] = state.hasText
        target[KEY_TEXT] = state.text
        return true
    }

    val isHandingOver: Boolean get() = outgoing != null

    /** New generation, before its sources start. */
    fun receive(value: Any?) {
        val map = value as? Map<*, *> ?: return
        val inherited = map[KEY_RELAY]
        if (!GenerationRelay.isRelay(inherited)) return
        relay = inherited as IBinder
        incoming = State(
            translationReceiver = map[KEY_RECEIVER] as? IBinder,
            songPayload = map[KEY_SONG] as? ByteArray,
            playing = map[KEY_PLAYING] as? Boolean,
            position = map[KEY_POSITION] as? Long,
            hasText = map[KEY_HAS_TEXT] == true,
            text = map[KEY_TEXT] as? String,
        )
    }

    /** New generation's bridge start(): the inherited state, consumed once. */
    fun takeIncoming(): State? = synchronized(this) { incoming.also { incoming = null } }

    /** End of restore: a source that did not start this time must not adopt stale state later. */
    fun discardIncoming() {
        incoming = null
    }
}
