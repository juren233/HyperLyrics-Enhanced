/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.cache

import com.juren233.hyperlyricsenhanced.common.coroutines.ModuleDispatchers
import android.content.Context
import com.juren233.hyperlyricsenhanced.online.isWordTimedOnlineLyrics
import com.juren233.hyperlyricsenhanced.online.model.LyricsResult
import com.juren233.hyperlyricsenhanced.online.model.SearchSource
import com.juren233.hyperlyricsenhanced.online.model.SongSearchResult
import com.juren233.hyperlyricsenhanced.utils.LogManager
import kotlinx.coroutines.withContext

/**
 * A [SearchSource] that answers from [OnlineLyricResponseCache] first. It caches what the source
 * sent back (candidate lists and lyrics), never a matching decision, so matcher changes still
 * apply to cached data.
 *
 * - Lyrics with content are permanent; a song with no lyrics is a miss.
 * - A candidate list stays temporary until [confirm] reports that one of its songs gave lyrics.
 * - A request that throws is not cached: sources throw on network, HTTP and rate-limit failures,
 *   and only return an empty answer when the service really has nothing.
 * - Metadata matches are cached only when non-empty: that endpoint does not tell "not found"
 *   from "failed".
 *
 * One instance serves one search ([request]) and remembers which candidate lists it handed out.
 */
internal class CachedSearchSource(
    private val delegate: SearchSource,
    private val cache: OnlineLyricResponseCache,
    private val request: OnlineLyricCacheRequest,
) : SearchSource {
    override val sourceType = delegate.sourceType

    private val retryMiss = sourceType in request.retryMissSources
    private val listKeysBySong = HashMap<String, MutableSet<String>>()

    override suspend fun search(
        keyword: String,
        page: Int,
        separator: String,
        pageSize: Int,
        durationMs: Long,
    ): List<SongSearchResult> {
        val key = listOf("search", sourceType.name, keyword, page, separator, pageSize, durationMs)
            .joinToString(KEY_SEPARATOR)
        return cachedList(key, OnlineLyricCacheKind.SEARCH, cacheEmpty = true) {
            delegate.search(keyword, page, separator, pageSize, durationMs)
        }
    }

    override suspend fun searchByMetadata(
        title: String,
        artist: String,
        durationMs: Long,
        separator: String,
    ): List<SongSearchResult> {
        val key = listOf("metadata", sourceType.name, title, artist, durationMs, separator)
            .joinToString(KEY_SEPARATOR)
        return cachedList(key, OnlineLyricCacheKind.METADATA, cacheEmpty = false) {
            delegate.searchByMetadata(title, artist, durationMs, separator)
        }
    }

    override suspend fun getLyrics(song: SongSearchResult): LyricsResult? {
        val key = listOf("lyrics", sourceType.name, song.id, song.extras.toSortedMap().toString())
            .joinToString(KEY_SEPARATOR)
        val cached = io { cache.lookup(key, retryMiss) }
        if (cached != null) {
            decodeOrNull(key) { OnlineLyricCacheCodec.decodeLyrics(cached) }?.let { lyrics ->
                io { cache.link(request.song, listOf(key)) }
                return lyrics.value
            }
        }
        val lyrics = delegate.getLyrics(song)
        val hasContent = lyrics != null &&
            (lyrics.original.isNotEmpty() || !lyrics.translated.isNullOrEmpty())
        io {
            cache.store(
                song = request.song,
                key = key,
                payload = OnlineLyricCacheCodec.encodeLyrics(lyrics.takeIf { hasContent }),
                permanent = hasContent,
                source = sourceType,
                kind = OnlineLyricCacheKind.LYRICS,
            )
        }
        return lyrics
    }

    /**
     * [song] gave [lyrics]: the candidate lists it came from are hits from now on, and the match
     * ([score], word timing) is recorded for the cache management page.
     */
    suspend fun confirm(song: SongSearchResult, score: Int, lyrics: LyricsResult) {
        val keys = synchronized(listKeysBySong) { listKeysBySong[songKey(song)]?.toList().orEmpty() }
        io {
            if (keys.isNotEmpty()) cache.promote(keys)
            cache.recordMatch(request.song, sourceType, score, isWordTimedOnlineLyrics(lyrics.original))
        }
    }

    private suspend fun cachedList(
        key: String,
        kind: OnlineLyricCacheKind,
        cacheEmpty: Boolean,
        fetch: suspend () -> List<SongSearchResult>,
    ): List<SongSearchResult> {
        val cached = io { cache.lookup(key, retryMiss) }
        val songs = cached?.let { payload ->
            decodeOrNull(key) { OnlineLyricCacheCodec.decodeSongs(payload) }?.value
        }
        val result = if (songs != null) {
            io { cache.link(request.song, listOf(key)) }
            songs
        } else {
            val fetched = fetch()
            if (fetched.isNotEmpty() || cacheEmpty) {
                io {
                    cache.store(
                        song = request.song,
                        key = key,
                        payload = OnlineLyricCacheCodec.encodeSongs(fetched),
                        permanent = false,
                        source = sourceType,
                        kind = kind,
                    )
                }
            }
            fetched
        }
        synchronized(listKeysBySong) {
            result.forEach { song -> listKeysBySong.getOrPut(songKey(song)) { linkedSetOf() } += key }
        }
        return result
    }

    private class Decoded<T>(val value: T)

    /** A payload that no longer decodes (format change, corruption) is dropped and fetched again. */
    private suspend fun <T> decodeOrNull(key: String, decode: () -> T): Decoded<T>? = try {
        Decoded(decode())
    } catch (e: Exception) {
        LogManager.w(TAG, "缓存内容无法解析，重新获取: key=$key, ${e.javaClass.simpleName}")
        io { cache.invalidate(key) }
        null
    }

    private fun songKey(song: SongSearchResult) = "${song.source.name}$KEY_SEPARATOR${song.id}"

    private suspend fun <T> io(block: () -> T): T = withContext(ModuleDispatchers.IO) { block() }

    companion object {
        private const val TAG = "OnlineLyricCache"
        private const val KEY_SEPARATOR = "\u0000"

        @Volatile
        private var shared: Pair<OnlineLyricResponseCache, SqliteOnlineLyricCacheStore>? = null

        /** The process-wide cache, stored in the data directory of the calling process. */
        fun cacheFor(context: Context): OnlineLyricResponseCache = sharedFor(context).first

        internal fun storeFor(context: Context): SqliteOnlineLyricCacheStore = sharedFor(context).second

        @Volatile
        private var retired = false

        /**
         * SystemUI hot reload, once the generation is retired: its database closes as soon as no
         * operation on it is running ([SqliteOnlineLyricCacheStore.retire]). A store first asked for
         * after this is created closed, so a late caller never opens the database again.
         */
        fun retireForReload() {
            val store = synchronized(this) {
                retired = true
                shared?.second
            }
            store?.retire()
        }

        /** Unit tests share one JVM: a test that retires the shared store must restore it. */
        internal fun resetForTest() {
            synchronized(this) {
                retired = false
                shared = null
            }
        }

        private fun sharedFor(context: Context) =
            shared ?: synchronized(this) {
                shared ?: run {
                    val appContext = context.applicationContext
                    val store = SqliteOnlineLyricCacheStore(appContext)
                    if (retired) store.retire()
                    OnlineLyricResponseCache(
                        store = store,
                        onStoreFailure = { operation, error ->
                            LogManager.w(TAG, "在线歌词缓存操作失败: $operation, ${error.javaClass.simpleName}: ${error.message}")
                        },
                        onTrimmed = { refs -> OnlineLyricCacheManager.clearTrimmedInBackground(appContext, refs) },
                    ) to store
                }.also { shared = it }
            }
    }
}
