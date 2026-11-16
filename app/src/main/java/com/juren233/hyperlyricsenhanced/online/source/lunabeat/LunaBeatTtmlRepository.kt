/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.source.lunabeat

import android.content.Context
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCachePolicy
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.source.ttml.CatalogPlatform
import com.juren233.hyperlyricsenhanced.online.source.ttml.TtmlCatalogSource
import com.juren233.hyperlyricsenhanced.online.source.ttml.TtmlCatalogIo
import com.juren233.hyperlyricsenhanced.online.source.ttml.TtmlCatalogIo.readLimited
import com.juren233.hyperlyricsenhanced.online.source.ttml.TtmlLookupResult
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

internal inline fun <T> resolveLunaBeatCatalogEntry(
    initialEntry: T?,
    forceRefreshRequested: Boolean,
    networkChecked: Boolean,
    refreshAfterMiss: () -> T?,
): T? {
    if (initialEntry != null) return initialEntry
    if (forceRefreshRequested || networkChecked) return null
    return refreshAfterMiss()
}

/** Revisioned, local-first client for LunaBeat TTML Hub's public static catalog. */
internal object LunaBeatTtmlRepository : TtmlCatalogSource {
    override val source: Source = Source.LB

    private const val TAG = "LunaBeatTtml"
    private const val BASE_URL = "https://2755337087.github.io/ttml-hub/"
    private const val MANIFEST_URL = "${BASE_URL}api/v1/manifest.json"
    private const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1_000L
    private const val MAX_MANIFEST_BYTES = 64 * 1_024
    private const val MAX_INDEX_BYTES = 8 * 1_024 * 1_024
        private const val MAX_CATALOG_SONGS = 100_000
    private const val PREFS_NAME = "lunabeat_ttml_hub"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_ETAG = "manifest_etag"
    private const val INDEX_FILE_NAME = "songs-v2.json"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val mutex = Mutex()
    private const val MAX_REMEMBERED_MISSES = 512
    private val misses = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_REMEMBERED_MISSES
    }

    private data class CatalogIndexSnapshot(
        val index: CatalogIndex,
        val networkChecked: Boolean,
    )

    @Volatile
    private var memoryIndex: CatalogIndex? = null
    @Volatile
    private var appleMusicEntries: Map<String, CatalogSong> = emptyMap()

    // The hub index is keyed by Apple Music ID only.
    override fun supports(platform: CatalogPlatform): Boolean = platform == CatalogPlatform.APPLE_MUSIC

    override suspend fun findWordTimed(
        context: Context,
        platform: CatalogPlatform,
        songId: String,
        retryMiss: Boolean,
    ): TtmlLookupResult =
        if (supports(platform)) {
            findWordTimedByAppleMusicId(context, songId, retryMiss)
        } else {
            TtmlLookupResult(null, searched = false)
        }

    override suspend fun clear(context: Context, platform: CatalogPlatform, songId: String) {
        if (!supports(platform)) return
        val normalizedId = songId.trim().takeIf(String::isNotEmpty) ?: return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val appContext = context.applicationContext
                hitRecordFile(appContext, normalizedId)?.let { record ->
                    readHitRecord(record)?.let { (entryId, _) -> ttmlFile(appContext, entryId).delete() }
                    record.delete()
                }
                synchronized(misses) { misses.remove(normalizedId) }
            }
        }
    }

    /** Removes cached lyric files and hit records; the catalog index stays. */
    override suspend fun clearAll(context: Context) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                cacheDir(context.applicationContext).listFiles()
                    ?.filter { it.name != INDEX_FILE_NAME }
                    ?.forEach { it.delete() }
                clearMisses()
            }
        }
    }

    private fun clearMisses() {
        synchronized(misses) { misses.clear() }
    }

    override fun cacheBytes(context: Context): Long =
        cacheDir(context.applicationContext).listFiles()
            ?.filter { it.isFile && it.name != INDEX_FILE_NAME }
            ?.sumOf(File::length) ?: 0L

    private suspend fun findWordTimedByAppleMusicId(
        context: Context,
        appleMusicId: String,
        retryMiss: Boolean,
    ): TtmlLookupResult = mutex.withLock {
        val normalizedId = appleMusicId.trim().takeIf(String::isNotEmpty)
            ?: return@withLock TtmlLookupResult(null, searched = false)
        val appContext = context.applicationContext
        // The lookup below does blocking network I/O. Without this, cancelling a superseded
        // search would wait for the request to time out while holding the lock.
        coroutineScope {
            val watcher = launch {
                try {
                    awaitCancellation()
                } catch (cancelled: CancellationException) {
                    activeCall?.cancel()
                    throw cancelled
                }
            }
            try {
                lookupLocked(appContext, normalizedId, retryMiss)
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun lookupLocked(
        appContext: Context,
        normalizedId: String,
        retryMiss: Boolean,
    ): TtmlLookupResult {
        // A file found once is kept until the user clears it (OnlineLyricCachePolicy): it is
        // served without consulting the index, so later catalog edits need a manual clear.
        readHit(appContext, normalizedId)?.let { return it }
        val now = System.currentTimeMillis()
        if (!retryMiss && isRecentMiss(normalizedId, now)) {
            return TtmlLookupResult(null, searched = true)
        }
        val forceRefresh = retryMiss
        val snapshot = ensureIndex(appContext, forceRefresh)
            ?: return TtmlLookupResult(null, searched = false)
        if (memoryIndex !== snapshot.index || appleMusicEntries.isEmpty()) {
            rebuildReverseIndex(snapshot.index)
        }
        val entry = resolveLunaBeatCatalogEntry(
            initialEntry = appleMusicEntries[normalizedId],
            forceRefreshRequested = forceRefresh,
            networkChecked = snapshot.networkChecked,
        ) {
            debug("catalog miss; refreshing before retry: appleMusicId=$normalizedId")
            val refreshed = ensureIndex(appContext, forceRefresh = true)
                ?: return@resolveLunaBeatCatalogEntry null
            if (memoryIndex !== refreshed.index || appleMusicEntries.isEmpty()) {
                rebuildReverseIndex(refreshed.index)
            }
            appleMusicEntries[normalizedId]
        } ?: run {
            debug("catalog miss after current-query refresh: appleMusicId=$normalizedId")
            synchronized(misses) { misses[normalizedId] = now }
            return TtmlLookupResult(null, searched = true)
        }
        // A download failure is not a miss: nothing is remembered.
        val bytes = loadTtml(appContext, entry)
            ?: return TtmlLookupResult(null, searched = true)
        synchronized(misses) { misses.remove(normalizedId) }
        hitRecordFile(appContext, normalizedId)?.let { record ->
            TtmlCatalogIo.writeAtomically(record, "${entry.id}\n${entry.sha256}".toByteArray())
        }
        return TtmlLookupResult(
            match = TtmlCatalogIo.buildMatch(
                source = source,
                sourceLyricId = entry.id,
                sha256 = entry.sha256,
                bytes = bytes,
            ),
            searched = true,
        )
    }

    @Volatile
    private var activeCall: Call? = null

    /** Runs [request] so that [activeCall] can abort it from the cancelling coroutine. */
    private fun <T> executeTracked(request: Request, handle: (Response) -> T?): T? {
        val call = client.newCall(request)
        activeCall = call
        return try {
            call.execute().use(handle)
        } finally {
            activeCall = null
        }
    }

    private fun ensureIndex(
        context: Context,
        forceRefresh: Boolean,
    ): CatalogIndexSnapshot? {
        val cached = memoryIndex ?: loadCachedIndex(context)?.also(::rememberIndex)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val shouldRefresh = forceRefresh || cached == null ||
            now - prefs.getLong(KEY_LAST_CHECK, 0L) >= CHECK_INTERVAL_MS
        if (!shouldRefresh) {
            return CatalogIndexSnapshot(cached, networkChecked = false)
        }

        fun cachedAfterNetworkCheck(): CatalogIndexSnapshot? =
            cached?.let { CatalogIndexSnapshot(it, networkChecked = true) }

        val request = Request.Builder()
            .url(MANIFEST_URL)
            .header("Accept", "application/json")
            .header("User-Agent", TtmlCatalogIo.userAgent(BuildConfig.VERSION_NAME))
            .apply {
                prefs.getString(KEY_ETAG, null)?.takeIf(String::isNotBlank)?.let {
                    header("If-None-Match", it)
                }
            }
            .build()
        val manifestCall = client.newCall(request)
        activeCall = manifestCall
        val manifestResponse = runCatching { manifestCall.execute() }
            .onFailure { debug("manifest request failed: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return cachedAfterNetworkCheck().also { activeCall = null }
        manifestResponse.use { response ->
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            if (response.code == 304) return cachedAfterNetworkCheck()
            if (!response.isSuccessful) return cachedAfterNetworkCheck()
            val manifestBytes = response.body?.readLimited(MAX_MANIFEST_BYTES)
                ?: return cachedAfterNetworkCheck()
            val manifest = runCatching {
                json.decodeFromString<CatalogManifest>(manifestBytes.decodeToString())
            }.getOrNull() ?: return cachedAfterNetworkCheck()
            if (manifest.schemaVersion != 2 || manifest.index.isBlank()) {
                return cachedAfterNetworkCheck()
            }
            if (cached?.revision == manifest.revision) {
                prefs.edit().putString(KEY_ETAG, response.header("ETag")).apply()
                return cachedAfterNetworkCheck()
            }
            val indexUrl = BASE_URL + "api/v1/" + manifest.index.trimStart('/')
            val indexRequest = Request.Builder()
                .url(indexUrl)
                .header("Accept", "application/json")
                .header("User-Agent", TtmlCatalogIo.userAgent(BuildConfig.VERSION_NAME))
                .build()
            val indexBytes = runCatching {
                executeTracked(indexRequest) { indexResponse ->
                    if (!indexResponse.isSuccessful) return@executeTracked null
                    indexResponse.body?.readLimited(MAX_INDEX_BYTES)
                }
            }.getOrNull() ?: return cachedAfterNetworkCheck()
            if (!TtmlCatalogIo.sha256(indexBytes).equals(manifest.indexSha256, ignoreCase = true)) {
                return cachedAfterNetworkCheck()
            }
            val downloaded = parseIndex(indexBytes) ?: return cachedAfterNetworkCheck()
            if (downloaded.revision != manifest.revision) return cachedAfterNetworkCheck()
            TtmlCatalogIo.writeAtomically(indexFile(context), indexBytes)
            prefs.edit().putString(KEY_ETAG, response.header("ETag")).apply()
            rememberIndex(downloaded)
            return CatalogIndexSnapshot(downloaded, networkChecked = true)
        }
    }

    private fun loadCachedIndex(context: Context): CatalogIndex? {
        val file = indexFile(context)
        if (!file.isFile || file.length() !in 1..MAX_INDEX_BYTES.toLong()) return null
        return runCatching { parseIndex(file.readBytes()) }.getOrNull()
    }

    private fun parseIndex(bytes: ByteArray): CatalogIndex? {
        if (bytes.isEmpty() || bytes.size > MAX_INDEX_BYTES) return null
        val index = runCatching {
            json.decodeFromString<CatalogIndex>(bytes.decodeToString())
        }.getOrNull() ?: return null
        if (index.schemaVersion != 2 || index.revision.isBlank()) return null
        if (index.songs.isEmpty() || index.songs.size > MAX_CATALOG_SONGS) return null
        if (index.songs.any { song ->
                song.id.isBlank() || song.path.isBlank() || song.sha256.length != 64
            }
        ) return null
        return index
    }

    private fun rememberIndex(index: CatalogIndex) {
        memoryIndex = index
        rebuildReverseIndex(index)
    }

    private fun rebuildReverseIndex(index: CatalogIndex) {
        appleMusicEntries = buildMap {
            index.songs.forEach { song ->
                song.sourceIds["appleMusicId"].orEmpty().forEach { id ->
                    id.trim().takeIf(String::isNotEmpty)?.let { put(it, song) }
                }
            }
        }
    }

    private fun isRecentMiss(appleMusicId: String, now: Long): Boolean = synchronized(misses) {
        val at = misses[appleMusicId] ?: return false
        if (now - at < OnlineLyricCachePolicy.MISS_TTL_MS) true else { misses.remove(appleMusicId); false }
    }

    /** The lyric file this Apple Music ID resolved to before, if it is still intact on disk. */
    private fun readHit(context: Context, appleMusicId: String): TtmlLookupResult? {
        val record = hitRecordFile(context, appleMusicId) ?: return null
        val (entryId, sha256) = readHitRecord(record) ?: return null
        val file = ttmlFile(context, entryId)
        val bytes = file.takeIf { it.isFile && it.length() in 1..TtmlCatalogIo.MAX_TTML_BYTES.toLong() }
            ?.let { runCatching { it.readBytes() }.getOrNull() }
        if (bytes == null || !TtmlCatalogIo.sha256(bytes).equals(sha256, ignoreCase = true)) {
            record.delete()
            return null
        }
        return TtmlLookupResult(
            match = TtmlCatalogIo.buildMatch(
                source = source,
                sourceLyricId = entryId,
                sha256 = sha256,
                bytes = bytes,
            ),
            searched = true,
        )
    }

    private fun readHitRecord(record: File): Pair<String, String>? {
        if (!record.isFile) return null
        val parts = runCatching { record.readText() }.getOrNull()?.lines() ?: return null
        val entryId = parts.getOrNull(0)?.trim()?.takeIf(::isSafeFileName) ?: return null
        val sha256 = parts.getOrNull(1)?.trim()?.takeIf { it.length == 64 } ?: return null
        return entryId to sha256
    }

    private fun hitRecordFile(context: Context, appleMusicId: String): File? =
        appleMusicId.takeIf(::isSafeFileName)?.let { File(cacheDir(context), "hit_am_$it") }

    private fun ttmlFile(context: Context, entryId: String): File = File(cacheDir(context), "$entryId.ttml")

    private fun isSafeFileName(value: String): Boolean =
        value.isNotEmpty() && value.length <= 128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun loadTtml(context: Context, song: CatalogSong): ByteArray? {
        val cacheFile = ttmlFile(context, song.id)
        if (cacheFile.isFile && cacheFile.length() in 1..TtmlCatalogIo.MAX_TTML_BYTES.toLong()) {
            val cached = runCatching { cacheFile.readBytes() }.getOrNull()
            if (cached != null && TtmlCatalogIo.sha256(cached).equals(song.sha256, ignoreCase = true)) return cached
            cacheFile.delete()
        }
        val normalizedPath = song.path.trimStart('/')
        if (!normalizedPath.startsWith("lyrics/") || ".." in normalizedPath) return null
        val request = Request.Builder()
            .url(BASE_URL + normalizedPath)
            .header("Accept", "application/ttml+xml, application/xml, text/xml")
            .header("User-Agent", TtmlCatalogIo.userAgent(BuildConfig.VERSION_NAME))
            .build()
        val bytes = runCatching {
            executeTracked(request) { response ->
                if (!response.isSuccessful) return@executeTracked null
                response.body?.readLimited(TtmlCatalogIo.MAX_TTML_BYTES)
            }
        }.getOrNull() ?: return null
        if (!TtmlCatalogIo.sha256(bytes).equals(song.sha256, ignoreCase = true)) return null
        TtmlCatalogIo.writeAtomically(cacheFile, bytes)
        return bytes
    }

    private fun cacheDir(context: Context): File =
        File(context.filesDir, "lunabeat_ttml_hub").apply { mkdirs() }

    private fun indexFile(context: Context): File = File(cacheDir(context), INDEX_FILE_NAME)

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) HookLogger.w(TAG, "[debug] $message")
    }

    @Serializable
    private data class CatalogManifest(
        val schemaVersion: Int,
        val revision: String,
        val index: String,
        val indexSha256: String,
    )

    @Serializable
    private data class CatalogIndex(
        val schemaVersion: Int,
        val revision: String,
        val songs: List<CatalogSong>,
    )

    @Serializable
    private data class CatalogSong(
        val id: String,
        val sourceIds: Map<String, List<String>> = emptyMap(),
        val path: String,
        val sha256: String,
    )
}
