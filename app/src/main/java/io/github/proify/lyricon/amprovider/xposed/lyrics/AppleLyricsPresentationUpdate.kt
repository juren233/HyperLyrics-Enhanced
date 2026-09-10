/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

/** Immutable refresh input, never a native pointer or proof that a View has rebound. */
internal data class AppleLyricsPresentationUpdate(
    val songId: String?,
    val revision: Long?,
)

/**
 * Owns only the latest menu-deferred refresh and its scheduled-drain state.
 * Handler scheduling stays in the Android adapter; no requests or native objects live here.
 * The adapter runs on the main thread. The monitor keeps identity/version inseparable.
 */
internal class AppleLyricsDeferredPresentation {
    private var pending: AppleLyricsPresentationUpdate? = null
    private var scheduled = false

    /** Latest arrival wins, matching the original single-slot queue. */
    @Synchronized
    fun offer(update: AppleLyricsPresentationUpdate): Boolean {
        pending = update
        if (scheduled) return false
        scheduled = true
        return true
    }

    @Synchronized
    fun take(): AppleLyricsPresentationUpdate? {
        scheduled = false
        return pending.also { pending = null }
    }
}

/** Separate revision domain: never use the native model rebuild revision for row updates. */
internal data class AppleMissingLyricsPresentationUpdate(val songId: String?, val revision: Long)

/** Sanitization stays with each lane; official content is authoritative and fallback is lazy. */
internal data class AppleLyricsSelectedText(val text: String?, val fromFallback: Boolean)

internal fun selectAppleLyricsText(official: String?, fallback: () -> String?): AppleLyricsSelectedText {
    if (official != null) return AppleLyricsSelectedText(official, false)
    val text = fallback()
    return AppleLyricsSelectedText(text, text != null)
}
