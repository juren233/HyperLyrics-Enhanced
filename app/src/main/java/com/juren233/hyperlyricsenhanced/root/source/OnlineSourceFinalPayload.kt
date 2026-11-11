/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song

/**
 * The last complete (non-progressive) source-dialog payload sent for one search target.
 *
 * Reopening the dialog for the same song, same attempt and same lyric lines resends this payload
 * once instead of searching every source again. Validity of the underlying fetches is not decided
 * here: the caller also requires live [OnlineTranslationFetchCache] entries, so the 10 minute
 * lifetime stays defined in one place.
 */
internal class OnlineSourceFinalPayload {
    private class Stored(
        val attemptKey: String,
        val baseLyrics: List<RichLyricLine>?,
        val payload: Song,
    )

    private var stored: Stored? = null

    @Synchronized
    fun record(attemptKey: String, baseSong: Song, payload: Song) {
        stored = Stored(attemptKey, baseSong.lyrics, payload)
    }

    /** The payload recorded for exactly this attempt and these lyric lines, or null. */
    @Synchronized
    fun reusable(attemptKey: String, baseSong: Song): Song? =
        stored?.takeIf { it.attemptKey == attemptKey && it.baseLyrics == baseSong.lyrics }?.payload

    @Synchronized
    fun clear() {
        stored = null
    }
}
