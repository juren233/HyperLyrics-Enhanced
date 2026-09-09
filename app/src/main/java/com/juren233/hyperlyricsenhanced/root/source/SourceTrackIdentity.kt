/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.model.Song
import kotlin.math.abs

/** Immutable projection of the existing matcher, not a new canonical song ID. */
internal data class SourceTrackIdentity(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Long,
) {
    fun matches(other: SourceTrackIdentity): Boolean {
        if (id.isNotEmpty() && other.id.isNotEmpty() && id == other.id) return true
        if (title != other.title || artist != other.artist) return false
        return duration <= 0L || other.duration <= 0L || abs(duration - other.duration) <= 2_000L
    }

    // Existing cache/request keys deliberately do not include the provider ID.
    fun legacyKey(): String = listOf(title, artist, duration.toString()).joinToString("|")

    companion object {
        fun of(song: Song) = SourceTrackIdentity(
            song.id?.trim().orEmpty(),
            song.name.orEmpty().trim().lowercase(),
            song.artist.orEmpty().trim().lowercase(),
            song.duration,
        )
    }
}

internal object ThirdPartySongUpdatePolicy {
    fun preserveOnline(
        sameTrack: Boolean,
        sameContent: Boolean,
        enrichmentRunningOrMatched: Boolean,
        fallbackRunning: Boolean,
        fallbackPending: Boolean,
        fallbackSelected: Boolean,
        preferOnline: Boolean,
        incomingHasLyrics: Boolean,
    ): Boolean = (sameContent && (enrichmentRunningOrMatched || fallbackRunning || fallbackSelected)) ||
        (sameTrack && (fallbackPending || fallbackSelected) && (preferOnline || !incomingHasLyrics))
}
