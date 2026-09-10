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
 *
 * Every map and the persistence switch are private. Callers outside this file use the named
 * query/write/invalidate/switch operations; diagnostics and tests read immutable copies via
 * the snapshot helpers, never the live maps. Because the LRU tables are access-ordered, a
 * read is itself a mutation of recency, so every read runs under the same monitor the
 * pre-split inline `synchronized(cache)` blocks used — the helper methods are the only way to
 * touch a table, and they never hold a caller-supplied monitor while invoking host code.
 */
internal class AppleInternalCatalogCaches {

    // ---- original-region song aliases (access-ordered LRU, bounded by CACHE_SIZE) ----

    private val originalSongCache = object : LinkedHashMap<String, Alias>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Alias>?): Boolean =
            size > CACHE_SIZE
    }

    /**
     * Looks up a reusable original alias for [mediaId] in one critical section.
     *
     * [isReusable] and [canonicalize] run for every hit, matching the inline predicate order;
     * a hit that is not reusable is evicted, exactly as before. [onHit] runs while the cache
     * monitor is still held — the pre-split code invoked the resolve callback in the same
     * position, and that placement is preserved here.
     *
     * Returns true when a reusable alias was found and [onHit] was invoked.
     */
    fun withReusableOriginalSongAlias(
        mediaId: String,
        canonicalize: (Alias) -> Alias?,
        isReusable: (Alias) -> Boolean,
        onHit: (Alias) -> Unit,
    ): Boolean {
        synchronized(originalSongCache) {
            val alias = originalSongCache[mediaId] ?: return false
            val cachedAlias = canonicalize(alias)?.takeIf(isReusable)
            if (cachedAlias == null) {
                originalSongCache.remove(mediaId)
                return false
            }
            if (cachedAlias != alias) originalSongCache[mediaId] = cachedAlias
            onHit(cachedAlias)
            return true
        }
    }

    fun putOriginalSongAlias(mediaId: String, alias: Alias) {
        synchronized(originalSongCache) { originalSongCache[mediaId] = alias }
    }

    fun evictOriginalSongAlias(mediaId: String) {
        synchronized(originalSongCache) { originalSongCache.remove(mediaId) }
    }

    // ---- localized song/album metadata (access-ordered LRU) ----

    private val localizedCache = object : LinkedHashMap<String, Alias>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Alias>?): Boolean =
            size > LOCALIZED_CACHE_SIZE
    }

    fun localizedAlias(cacheKey: String): Alias? =
        synchronized(localizedCache) { localizedCache[cacheKey] }

    /** First present alias among [cacheKeys]; the scan order is caller-defined and preserved. */
    fun firstLocalizedAlias(cacheKeys: List<String>): Alias? =
        synchronized(localizedCache) { cacheKeys.firstNotNullOfOrNull(localizedCache::get) }

    fun putLocalizedAlias(cacheKey: String, alias: Alias) {
        synchronized(localizedCache) { localizedCache[cacheKey] = alias }
    }

    fun putAllLocalizedAliases(aliases: Map<String, Alias>) {
        if (aliases.isEmpty()) return
        synchronized(localizedCache) { localizedCache.putAll(aliases) }
    }

    // ---- localized artist aliases (access-ordered LRU) ----

    private val localizedArtistAliasCache =
        object : LinkedHashMap<String, Alias>(32, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Alias>?,
            ): Boolean = size > LOCALIZED_ARTIST_ALIAS_CACHE_SIZE
        }

    fun firstLocalizedArtistAlias(cacheKeys: List<String>): Alias? =
        synchronized(localizedArtistAliasCache) {
            cacheKeys.firstNotNullOfOrNull(localizedArtistAliasCache::get)
        }

    /**
     * Writes only the [entries] whose stored value differs, then returns the changed subset.
     * The filter-then-putAll order and the single monitor are unchanged.
     */
    fun mergeLocalizedArtistAliases(entries: Map<String, Alias>): Map<String, Alias> =
        synchronized(localizedArtistAliasCache) {
            entries.filter { (key, value) -> localizedArtistAliasCache[key] != value }
                .also(localizedArtistAliasCache::putAll)
        }

    /**
     * Warm-path write: re-inserts every entry in map order, matching the pre-split
     * unconditional `putAll` and its recency update for already-present keys.
     */
    fun putAllLocalizedArtistAliases(entries: Map<String, Alias>) {
        synchronized(localizedArtistAliasCache) {
            localizedArtistAliasCache.putAll(entries)
        }
    }

    // ---- catalog identity cache (locale-independent facts) ----

    private val catalogIdentityCache = ConcurrentHashMap<String, CatalogIdentity>()

    fun catalogIdentity(mediaId: String): CatalogIdentity? = catalogIdentityCache[mediaId]

    fun catalogArtistIds(mediaId: String): List<String> =
        catalogIdentityCache[mediaId]?.artistIds.orEmpty()

    fun catalogGenres(mediaId: String): List<String> =
        catalogIdentityCache[mediaId]?.genres.orEmpty()

    fun putCatalogIdentity(mediaId: String, identity: CatalogIdentity) {
        catalogIdentityCache[mediaId] = identity
    }

    fun removeCatalogIdentity(mediaId: String) {
        catalogIdentityCache.remove(mediaId)
    }

    // ---- persistence switch ----

    /**
     * Gates both persistent stores; false keeps memory caching and network
     * resolution while skipping all SQLite reads and writes.
     */
    @Volatile
    private var persistentLocalizedCacheEnabled = true

    fun isPersistentCacheEnabled(): Boolean = persistentLocalizedCacheEnabled

    /** Sets the switch and returns its previous value, for the caller's change detection. */
    fun setPersistentCacheEnabled(enabled: Boolean): Boolean {
        val wasEnabled = persistentLocalizedCacheEnabled
        persistentLocalizedCacheEnabled = enabled
        return wasEnabled
    }

    // ---- warm bookkeeping ----

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

    // ---- immutable snapshots for diagnostics and tests ----

    /** Copy of the localized alias table for diagnostics/tests; never the live map. */
    fun localizedAliasSnapshot(): Map<String, Alias> =
        synchronized(localizedCache) { LinkedHashMap(localizedCache) }

    /** Copy of the original-region song alias table for diagnostics/tests. */
    fun originalSongAliasSnapshot(): Map<String, Alias> =
        synchronized(originalSongCache) { LinkedHashMap(originalSongCache) }

    /** Copy of the localized artist alias table in LRU order, for diagnostics/tests. */
    fun localizedArtistAliasSnapshot(): Map<String, Alias> =
        synchronized(localizedArtistAliasCache) { LinkedHashMap(localizedArtistAliasCache) }
}
