/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import com.juren233.hyperlyricsenhanced.lyric.model.Song

/** Keeps Xiaomi Music's car-lyric MediaSession title out of the active lyric view. */
internal object XiaomiMusicMetadataRefreshPolicy {
    private const val XIAOMI_MUSIC_PACKAGE = "com.miui.player"

    fun shouldForward(
        packageName: String,
        sameAppliedTrack: Boolean,
        activeSong: Song?,
        title: String,
        artist: String,
    ): Boolean {
        if (packageName != XIAOMI_MUSIC_PACKAGE || !sameAppliedTrack ||
            activeSong?.lyrics.isNullOrEmpty()
        ) return true

        val authoritativeTitle = activeSong.name?.trim().orEmpty()
        if (authoritativeTitle.isEmpty()) return true
        if (!authoritativeTitle.equals(title.trim(), ignoreCase = true)) return false

        val authoritativeArtist = activeSong.artist?.trim().orEmpty()
        return authoritativeArtist.isEmpty() ||
            authoritativeArtist.equals(artist.trim(), ignoreCase = true)
    }
}
