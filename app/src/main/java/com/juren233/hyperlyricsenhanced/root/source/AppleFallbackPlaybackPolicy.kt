/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

/**
 * Online fallback replaces Apple Music lyric content, not playback authority.
 * Central playback state must continue to reach the sink while fallback lyrics are active.
 */
internal fun shouldForwardCentralPlaybackState(
    hasActiveCentralPlayer: Boolean,
    @Suppress("UNUSED_PARAMETER") centralAppleProviderActive: Boolean,
    @Suppress("UNUSED_PARAMETER") fallbackSongActive: Boolean,
): Boolean = hasActiveCentralPlayer
