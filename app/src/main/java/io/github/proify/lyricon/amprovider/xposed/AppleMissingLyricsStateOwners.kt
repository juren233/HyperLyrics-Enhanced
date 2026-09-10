/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/** Owns native-model build de-duplication and the scoped native parser marker. */
internal class AppleMissingLyricsNativeBuildState {
    private val lock = Any()
    private val scope = AppleMissingLyricsNativeBuildScope()
    private var pendingKey: AppleMissingLyricsHooks.NativeBuildKey? = null

    fun begin(key: AppleMissingLyricsHooks.NativeBuildKey): Boolean = synchronized(lock) {
        if (pendingKey == key) return false
        pendingKey = key
        true
    }

    fun isCurrent(key: AppleMissingLyricsHooks.NativeBuildKey): Boolean = synchronized(lock) {
        pendingKey == key
    }

    fun clearIfCurrent(key: AppleMissingLyricsHooks.NativeBuildKey) {
        synchronized(lock) {
            if (pendingKey == key) pendingKey = null
        }
    }

    fun isScopeActive(): Boolean = scope.isActive()

    fun <T> withinScope(block: () -> T): T = scope.within(block)
}

/** Owns scheduled takeover rechecks; callers can only register, claim, or remove by identity. */
internal class AppleMissingLyricsTakeoverRechecks {
    private val scheduled = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun registerIfEarlier(songId: String, targetAt: Long): Boolean {
        val existing = scheduled[songId]
        if (existing != null && existing <= targetAt) return false
        scheduled[songId] = targetAt
        return true
    }

    fun claim(songId: String, targetAt: Long): Boolean = scheduled.remove(songId, targetAt)

    fun remove(songId: String) {
        scheduled.remove(songId)
    }
}

/** Owns the weak current PlaybackItem reference and keeps identity checks at the read boundary. */
internal class AppleMissingLyricsPlaybackItemBinding {
    data class Reference(
        val identity: AppleMissingLyricsPlaybackIdentity,
        val item: WeakReference<Any>,
    )

    @Volatile
    private var reference: Reference? = null

    fun snapshot(): Reference? = reference

    fun remember(identity: AppleMissingLyricsPlaybackIdentity, item: Any) {
        reference = Reference(identity, WeakReference(item))
    }
}

/** Debug-only availability signature de-duplication; no production state depends on it. */
internal class AppleMissingLyricsAvailabilityDiagnostics {
    @Volatile
    private var lastSignature: String? = null

    @Synchronized
    fun shouldLog(signature: String): Boolean {
        if (lastSignature == signature) return false
        lastSignature = signature
        return true
    }
}
