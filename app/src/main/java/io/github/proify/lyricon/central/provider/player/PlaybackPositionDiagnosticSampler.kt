/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

/** Debug sampling must not hide the first read after changing the authoritative state. */
internal class PlaybackPositionDiagnosticSampler {
    private var lastSequence: Long? = null
    private var lastAutomatic: Boolean? = null
    private var lastAt: Long? = null

    @Synchronized
    fun sample(now: Long, sequence: Long, automatic: Boolean): String? {
        val reason = when {
            lastAt == null -> "first_read"
            lastSequence != sequence || lastAutomatic != automatic -> "state_changed"
            now - requireNotNull(lastAt) >= 5_000L -> "periodic"
            else -> return null
        }
        lastAt = now
        lastSequence = sequence
        lastAutomatic = automatic
        return reason
    }
}
