/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.online.OnlineLyricTargeter
import com.juren233.hyperlyricsenhanced.online.model.Source

/**
 * Per-song memory of what each online source returned, so switching the translation or
 * pronunciation source re-matches locally instead of searching every source again.
 *
 * Only the most recent song is kept. A [Entry.Miss] is remembered too, so a switch does not
 * wait on sources that already failed; the explicitly requested source bypasses it and retries.
 *
 * An entry is only valid for the query it was searched with. When the search inputs change
 * (for example the original title arrives), the earlier result, a miss above all, says nothing
 * about the new query and is not reused.
 */
internal class OnlineTranslationFetchCache(
    private val clockMs: () -> Long,
    private val ttlMs: Long = TTL_MS,
) {
    sealed interface Entry {
        data class Hit(val outcome: OnlineLyricTargeter.FetchOutcome) : Entry
        data object Miss : Entry
    }

    private data class Stamped(val entry: Entry, val queryKey: String, val storedAtMs: Long)

    private var songId: String? = null
    private val entries = HashMap<Source, Stamped>()

    @Synchronized
    fun get(songId: String, source: Source, queryKey: String): Entry? {
        if (this.songId != songId) return null
        val stamped = entries[source] ?: return null
        if (stamped.queryKey != queryKey) {
            entries.remove(source)
            return null
        }
        if (clockMs() - stamped.storedAtMs > ttlMs) {
            entries.remove(source)
            return null
        }
        return stamped.entry
    }

    @Synchronized
    fun put(songId: String, source: Source, queryKey: String, entry: Entry) {
        if (this.songId != songId) {
            entries.clear()
            this.songId = songId
        }
        entries[source] = Stamped(entry, queryKey, clockMs())
    }

    private companion object {
        const val TTL_MS = 10L * 60L * 1_000L
    }
}
