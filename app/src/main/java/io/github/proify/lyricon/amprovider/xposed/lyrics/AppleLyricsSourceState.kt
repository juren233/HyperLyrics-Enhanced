/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

/** Positive native availability only; pending/empty results remain owned by the takeover gate. */
internal class AppleNativeLyricsKnowledge(private val limit: Int) {
    private val adamIds = mutableSetOf<String>()
    private val contentIds = mutableSetOf<String>()

    @Synchronized
    fun remember(adamId: String, contentId: String?) {
        if (adamIds.size >= limit || contentIds.size >= limit) {
            adamIds.clear()
            contentIds.clear()
        }
        adamIds.add(adamId)
        contentId?.let(contentIds::add)
    }

    @Synchronized
    fun contains(contentId: String, adamId: String?, storedAdamId: String?): Boolean =
        contentId in contentIds || adamId in adamIds || storedAdamId in adamIds
}

/** Manual choices are session state, not evidence of native or fallback availability. */
internal class AppleLyricsSourceSelection {
    private var playbackSongId: String? = null
    private val selections = mutableMapOf<String, String>()

    @Synchronized fun selected(songId: String): String? = selections[songId]
    @Synchronized fun select(songId: String, source: String) { selections[songId] = source }
    @Synchronized fun remove(songId: String) { selections.remove(songId) }

    /** Same-song queue callbacks retain the choice; a new track resets it. */
    @Synchronized fun beginPlayback(songId: String): Boolean {
        if (playbackSongId == songId) return false
        selections.clear()
        playbackSongId = songId
        return true
    }
}

/**
 * Exact native alternatives retained for manual switching, not a general pointer cache.
 * Keep the previous strong-reference and track-pruning semantics. No deallocation here;
 * generated supplement pointers keep their independent process-lifetime store ownership.
 */
internal class AppleLyricsNativeAlternatives(private val limit: Int) {
    private val pointers = mutableMapOf<String, Any>()
    private val timing = mutableMapOf<String, AppleNativeLyricsTimingStats>()

    @Synchronized fun pointer(songId: String): Any? = pointers[songId]
    @Synchronized fun rememberPointer(songId: String, pointer: Any) { pointers[songId] = pointer }
    @Synchronized fun timing(songId: String): AppleNativeLyricsTimingStats? = timing[songId]
    @Synchronized fun rememberTiming(songId: String, contentId: String, stats: AppleNativeLyricsTimingStats) {
        if (timing.size >= limit) timing.clear()
        timing[songId] = stats
        timing[contentId] = stats
    }
    @Synchronized fun retainTrack(songId: String) {
        pointers.keys.removeAll { it != songId }
        timing.keys.removeAll { it != songId }
    }
}
