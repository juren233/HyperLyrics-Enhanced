/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.Song

internal object AppleOnlineTranslationSearchDurationPolicy {
    data class MediaSnapshot(
        val title: String,
        val artist: String,
        val durationMs: Long,
    )

    data class Resolution(
        val durationMs: Long,
        val mediaIdentityMatched: Boolean,
    )

    fun resolve(song: Song, media: MediaSnapshot): Resolution {
        val fallbackDuration = song.duration
        if (media.durationMs <= 0L) {
            return Resolution(fallbackDuration, mediaIdentityMatched = false)
        }

        val titleMatches = matchesAny(
            media.title,
            song.name,
            song.metadata?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE),
        )
        val artistMatches = media.artist.isBlank() || matchesAny(
            media.artist,
            song.artist,
            song.metadata?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ARTIST),
        )
        val identityMatches = titleMatches && artistMatches
        return Resolution(
            durationMs = if (identityMatches) media.durationMs else fallbackDuration,
            mediaIdentityMatched = identityMatches,
        )
    }

    private fun matchesAny(actual: String, vararg candidates: String?): Boolean {
        val normalizedActual = normalize(actual)
        if (normalizedActual.isEmpty()) return false
        return candidates.any { normalize(it) == normalizedActual }
    }

    private fun normalize(value: String?): String = value
        .orEmpty()
        .lowercase()
        .filter(Char::isLetterOrDigit)
}
