/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.cache

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.online.model.Source
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

/**
 * The one place that says how long online lyric data is kept. Every online cache (search
 * responses, lyrics, the TTML catalogs) reads these values instead of keeping its own.
 *
 * - A hit (a source returned lyrics) is kept until the user clears it.
 * - A miss (the source answered and had nothing usable) is trusted for [MISS_TTL_MS]; a source the
 *   user picks by hand ignores it and asks again.
 * - A failed request (network error, HTTP error, rate limit) is never remembered.
 */
object OnlineLyricCachePolicy {
    const val MISS_TTL_MS: Long = 6L * 60L * 60L * 1_000L

    const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"

    /** Song tag for an Apple Music track: the key the source dialog clears by. */
    fun appleMusicSongTag(songId: String): String = "apple:$songId"
}

/**
 * How many songs with lyrics each copy of the cache keeps (「缓存上限」); the least recently used
 * such song beyond it is dropped together with its entries and catalog files. Songs with only miss
 * records do not count and are not dropped by the limit; they go when their misses expire. The user picks a preset or a custom value;
 * both processes read it from the module preferences bound with [bind] (SystemUI: the remote
 * preferences, the app: its own), so a change applies to the next lookup without a restart.
 */
object OnlineLyricCacheLimit {
    const val KEY_MODE = "key_online_lyric_cache_limit_mode"
    const val KEY_CUSTOM = "key_online_lyric_cache_limit_custom"

    /** Song counts of the preset options, in menu order; [MODE_CUSTOM] follows them. */
    val PRESETS: List<Int> = listOf(100, 200, 500, 1_000)
    const val DEFAULT_MODE = 3
    val MODE_CUSTOM: Int get() = PRESETS.size
    val DEFAULT: Int get() = PRESETS[DEFAULT_MODE]

    @Volatile
    private var prefs: SharedPreferences? = null

    fun bind(preferences: SharedPreferences) {
        prefs = preferences
    }

    fun mode(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_MODE, DEFAULT_MODE).takeIf { it in 0..MODE_CUSTOM } ?: DEFAULT_MODE

    fun custom(prefs: SharedPreferences): Int = prefs.getInt(KEY_CUSTOM, DEFAULT).coerceAtLeast(1)

    fun read(prefs: SharedPreferences): Int = resolve(mode(prefs), custom(prefs))

    fun resolve(mode: Int, custom: Int): Int = PRESETS.getOrNull(mode) ?: custom.coerceAtLeast(1)

    /** The limit of this process; the default until preferences are bound. */
    fun current(): Int = prefs?.let { runCatching { read(it) }.getOrNull() } ?: DEFAULT
}

/**
 * Bumped whenever cached online data is deleted, so in-memory memos built on top of it (the
 * per-song fetch cache of the source dialog) stop serving what the user just cleared.
 */
object OnlineLyricCacheInvalidation {
    private val counter = AtomicLong()
    fun generation(): Long = counter.get()
    fun invalidate() {
        counter.incrementAndGet()
    }
}

/**
 * The song a cached response belongs to, with what the management page shows for it. [album] is
 * only shown, never part of [tag]; a blank one does not replace the album already recorded.
 */
@Serializable
data class OnlineLyricCacheSong(
    val tag: String,
    val title: String,
    val artist: String,
    val packageName: String,
    val album: String = "",
) {
    companion object {
        fun appleMusic(songId: String, title: String?, artist: String?, album: String?) = OnlineLyricCacheSong(
            tag = OnlineLyricCachePolicy.appleMusicSongTag(songId),
            title = title.orEmpty(),
            artist = artist.orEmpty(),
            packageName = OnlineLyricCachePolicy.APPLE_MUSIC_PACKAGE,
            album = album.orEmpty(),
        )

        /** Any other player: the song is what the search is made of. */
        fun forQuery(packageName: String, title: String, artist: String, durationMs: Long, album: String?) =
            OnlineLyricCacheSong(
                tag = "song:$packageName\u0000$title\u0000$artist\u0000$durationMs",
                title = title,
                artist = artist,
                packageName = packageName,
                album = album.orEmpty(),
            )
    }
}

/**
 * Who a search is for and which sources it must ask again even if they missed recently.
 *
 * @param retryMissSources sources the user picked by hand: their remembered misses are skipped.
 */
internal data class OnlineLyricCacheRequest(
    val song: OnlineLyricCacheSong,
    val retryMissSources: Set<Source> = emptySet(),
)

/** What an entry is, for statistics and selective clearing. */
internal enum class OnlineLyricCacheKind { SEARCH, METADATA, LYRICS, CATALOG }

internal class StoredCacheEntry(
    val payload: ByteArray,
    val permanent: Boolean,
    val storedAtMs: Long,
)

/** Which entries a clear removes; null means "any". */
@Serializable
data class OnlineLyricCacheSelection(
    val tags: Set<String>? = null,
    val sources: Set<String>? = null,
)

/**
 * [score] is the match score the title search gave this source's song (none for catalog sources,
 * which are addressed by song ID); [wordTimed] whether its lyrics are word-timed. Both are null
 * until the source has been matched for the song since they started being recorded.
 */
@Serializable
data class OnlineLyricCacheSourceStats(
    val source: String,
    val hits: Int,
    val misses: Int,
    val sizeBytes: Long,
    val score: Int? = null,
    val wordTimed: Boolean? = null,
)

/** The kinds of online content a shown song takes from a source. */
enum class OnlineLyricCacheUse { LYRICS, TRANSLATION, PRONUNCIATION }

/**
 * The sources the song showed each kind of online content from the last time it showed any
 * (「上次歌词」「上次翻译」「上次发音」 on the song page); a kind that display did not have is null.
 */
@Serializable
data class OnlineLyricCacheLastUsed(
    val lyrics: String? = null,
    val translation: String? = null,
    val pronunciation: String? = null,
) {
    val isEmpty: Boolean get() = lyrics == null && translation == null && pronunciation == null

    /** What the song took from [source]. */
    fun usesOf(source: String): Set<OnlineLyricCacheUse> = buildSet {
        if (lyrics == source) add(OnlineLyricCacheUse.LYRICS)
        if (translation == source) add(OnlineLyricCacheUse.TRANSLATION)
        if (pronunciation == source) add(OnlineLyricCacheUse.PRONUNCIATION)
    }

    /** Keeps the sources [keep] accepts. */
    fun filter(keep: (String) -> Boolean) = OnlineLyricCacheLastUsed(
        lyrics = lyrics?.takeIf(keep),
        translation = translation?.takeIf(keep),
        pronunciation = pronunciation?.takeIf(keep),
    )
}

@Serializable
data class OnlineLyricCacheSongSummary(
    val song: OnlineLyricCacheSong,
    val usedAtMs: Long,
    val sources: List<OnlineLyricCacheSourceStats>,
    val lastUsed: OnlineLyricCacheLastUsed = OnlineLyricCacheLastUsed(),
) {
    val sizeBytes: Long get() = sources.sumOf { it.sizeBytes }
}

/** A cached TTML catalog lookup; when it is cleared its file and remembered miss must go too. */
internal data class CatalogCacheRef(val source: Source, val platform: String, val songId: String) {
    val key: String get() = listOf("catalog", source.name, platform, songId).joinToString(KEY_SEPARATOR)

    companion object {
        const val KEY_SEPARATOR = "\u0000"

        fun parse(key: String): CatalogCacheRef? {
            val parts = key.split(KEY_SEPARATOR)
            if (parts.size != 4 || parts[0] != "catalog") return null
            val source = Source.entries.firstOrNull { it.name == parts[1] } ?: return null
            return CatalogCacheRef(source, parts[2], parts[3])
        }
    }
}

/** Persistence behind [OnlineLyricResponseCache]; every call may fail and is then treated as a cache miss. */
internal interface OnlineLyricCacheStore {
    fun get(key: String): StoredCacheEntry?

    /** Stores [key] and links it to [song] in one step, so a concurrent trim cannot drop it as unlinked. */
    fun put(
        key: String,
        payload: ByteArray,
        permanent: Boolean,
        storedAtMs: Long,
        song: OnlineLyricCacheSong,
        source: Source,
        kind: OnlineLyricCacheKind,
        sizeBytes: Long,
    )

    fun markPermanent(keys: Collection<String>)
    fun remove(key: String)

    /** Links [keys] to [song] and records that it was used at [usedAtMs]. */
    fun link(song: OnlineLyricCacheSong, keys: Collection<String>, usedAtMs: Long)

    /** Records how [source] matched the song [tag] (an existing song only): its score and lyric timing. */
    fun recordMatch(tag: String, source: Source, score: Int?, wordTimed: Boolean)

    /** Records what the song [tag] showed from which source, replacing the previous record. */
    fun markUsed(tag: String, used: OnlineLyricCacheLastUsed)

    /**
     * Removes misses stored at or before [expiredBeforeMs], then drops the least recently used songs
     * with lyrics (a permanent entry) beyond [maxSongs], and the entries no song links to any more;
     * returns the keys of the catalog lookups among those entries, whose files must go too.
     */
    fun trimSongs(maxSongs: Int, expiredBeforeMs: Long): List<String>

    /** Removes misses stored at or before [expiredBeforeMs] (same boundary as [OnlineLyricResponseCache.lookup]), then lists every song with per-source statistics. */
    fun summaries(expiredBeforeMs: Long): List<OnlineLyricCacheSongSummary>

    /** Removes the selected entries; returns the keys of the catalog lookups among them. */
    fun delete(selection: OnlineLyricCacheSelection): List<String>
}

/**
 * Hit/miss bookkeeping over an [OnlineLyricCacheStore]. Entries are stored temporary (a miss, or a
 * response not yet known to lead to lyrics) and become permanent through [store] or [promote].
 */
internal class OnlineLyricResponseCache(
    private val store: OnlineLyricCacheStore,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val maxSongs: () -> Int = OnlineLyricCacheLimit::current,
    private val onStoreFailure: (String, Throwable) -> Unit = { _, _ -> },
    /** Catalog lookups dropped by the trim after a store; the owner clears their files. */
    private val onTrimmed: (List<CatalogCacheRef>) -> Unit = {},
) {
    /**
     * The stored payload for [key], or null when it has to be fetched: absent, an expired
     * temporary entry, or a temporary entry the caller asked to retry ([retryMiss]).
     */
    fun lookup(key: String, retryMiss: Boolean): ByteArray? = guarded("lookup", null) {
        val entry = store.get(key) ?: return@guarded null
        when {
            entry.permanent -> entry.payload
            retryMiss -> null
            clockMs() - entry.storedAtMs >= OnlineLyricCachePolicy.MISS_TTL_MS -> {
                store.remove(key)
                null
            }
            else -> entry.payload
        }
    }

    fun store(
        song: OnlineLyricCacheSong,
        key: String,
        payload: ByteArray,
        permanent: Boolean,
        source: Source,
        kind: OnlineLyricCacheKind,
        sizeBytes: Long = payload.size.toLong(),
    ) = guarded("store", Unit) {
        store.put(key, payload, permanent, clockMs(), song, source, kind, sizeBytes)
        val dropped = store.trimSongs(maxSongs(), expiredBefore()).mapNotNull(CatalogCacheRef::parse)
        if (dropped.isNotEmpty()) onTrimmed(dropped)
    }

    /** Applies a new limit at once; returns the dropped catalog lookups, whose files the caller clears. */
    fun trim(maxSongs: Int): List<CatalogCacheRef> =
        guarded("trim", emptyList()) { store.trimSongs(maxSongs, expiredBefore()) }
            .mapNotNull(CatalogCacheRef::parse)
            .also { OnlineLyricCacheInvalidation.invalidate() }

    fun invalidate(key: String) = guarded("invalidate", Unit) { store.remove(key) }

    fun promote(keys: Collection<String>) = guarded("promote", Unit) {
        if (keys.isNotEmpty()) store.markPermanent(keys)
    }

    fun link(song: OnlineLyricCacheSong, keys: Collection<String>) = guarded("link", Unit) {
        if (keys.isEmpty()) return@guarded
        store.link(song, keys, clockMs())
    }

    fun recordMatch(song: OnlineLyricCacheSong, source: Source, score: Int?, wordTimed: Boolean) =
        guarded("recordMatch", Unit) { store.recordMatch(song.tag, source, score, wordTimed) }

    fun markUsed(tag: String, used: OnlineLyricCacheLastUsed) = guarded("markUsed", Unit) { store.markUsed(tag, used) }

    fun summaries(): List<OnlineLyricCacheSongSummary> = guarded("summaries", emptyList()) {
        store.summaries(expiredBeforeMs = expiredBefore())
    }

    private fun expiredBefore(): Long = clockMs() - OnlineLyricCachePolicy.MISS_TTL_MS

    /** Removes the selection; returns the catalog lookups among it, whose files the caller clears. */
    fun delete(selection: OnlineLyricCacheSelection): List<CatalogCacheRef> =
        guarded("delete", emptyList()) { store.delete(selection) }
            .mapNotNull(CatalogCacheRef::parse)
            .also { OnlineLyricCacheInvalidation.invalidate() }

    private inline fun <T> guarded(operation: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        onStoreFailure(operation, e)
        fallback
    }
}
