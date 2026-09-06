/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.island

/** Allocated only in Debug. Keep first-hit evidence even when steady-state snapshots are sampled. */
internal class IslandMediaVisibilityDiagnosticSampler {
    var draws = 0L
        private set
    var corrections = 0L
        private set
    private var lastShown: Boolean? = null
    private var lastLogAt: Long? = null

    fun record(now: Long, shown: Boolean, corrected: Boolean): String? {
        draws++
        if (corrected) corrections++
        val event = when {
            corrected && corrections == 1L -> "visibility_guard_first_correction"
            draws == 1L -> "visibility_guard_first_predraw"
            lastShown != shown -> "visibility_guard_shown_changed"
            (shown || corrected) && now - (lastLogAt ?: now) >= 5_000L ->
                "visibility_guard_status"
            else -> null
        }
        lastShown = shown
        if (event != null) lastLogAt = now
        return event
    }
}
