/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import java.util.concurrent.ConcurrentHashMap

/**
 * Single owner of catalog content caches: the in-memory original/localized LRU
 * tables, the catalog identity cache, the persistence switch, and warm
 * bookkeeping. Scheduling state lives in [AppleInternalCatalogDispatch]; the
 * persistent SQLite stores remain self-owned by AppleOriginalMetadataCache and
 * AppleLocalizedMetadataCache.
 *
 * Language/region isolation is preserved by cache keys embedding the content
 * selection and language tag (see localizedMetadataCacheKey), so a configuration
 * change cannot let old-locale entries answer new requests. Formats and sizes are
 * unchanged; do not retune capacities here.
 */
internal class AppleInternalCatalogCaches {

    /** Verified original-region song aliases; access-ordered LRU bounded by CACHE_SIZE. */
    val originalSongCache = object : LinkedHashMap<String, Alias>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Alias>?): Boolean =
            size > CACHE_SIZE
    }

    /** Localized song/album metadata; access-ordered LRU bounded by LOCALIZED_CACHE_SIZE. */
    val localizedCache = object : LinkedHashMap<String, Alias>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Alias>?): Boolean =
            size > LOCALIZED_CACHE_SIZE
    }

    /** Localized artist aliases; access-ordered LRU bounded by LOCALIZED_ARTIST_ALIAS_CACHE_SIZE. */
    val localizedArtistAliasCache =
        object : LinkedHashMap<String, Alias>(32, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Alias>?,
            ): Boolean = size > LOCALIZED_ARTIST_ALIAS_CACHE_SIZE
        }

    /** Catalog facts (ISRC, genres, artist IDs) that are locale-independent. */
    val catalogIdentityCache = ConcurrentHashMap<String, CatalogIdentity>()

    /**
     * Gates both persistent stores; false keeps memory caching and network
     * resolution while skipping all SQLite reads and writes.
     */
    @Volatile
    var persistentLocalizedCacheEnabled = true

    private val warmedSelections = mutableSetOf<Int>()
    private val warmingSelections = mutableSetOf<Int>()

    /**
     * Warm gate with the pre-split double-checked semantics: false while the
     * selection is already warmed or another warm is running for it.
     */
    fun beginLocalizedCacheWarm(selection: Int): Boolean {
        val shouldWarm = synchronized(warmedSelections) {
            if (selection in warmedSelections) false
            else synchronized(warmingSelections) { warmingSelections.add(selection) }
        }
        return shouldWarm
    }

    fun completeLocalizedCacheWarm(selection: Int) {
        synchronized(warmedSelections) { warmedSelections.add(selection) }
        synchronized(warmingSelections) { warmingSelections.remove(selection) }
    }

    fun abandonLocalizedCacheWarm(selection: Int) {
        synchronized(warmingSelections) { warmingSelections.remove(selection) }
    }

    /** Re-arms warming after the persistence switch is turned back on. */
    fun resetLocalizedCacheWarm(selection: Int) {
        synchronized(warmedSelections) { warmedSelections.remove(selection) }
        synchronized(warmingSelections) { warmingSelections.remove(selection) }
    }
}
