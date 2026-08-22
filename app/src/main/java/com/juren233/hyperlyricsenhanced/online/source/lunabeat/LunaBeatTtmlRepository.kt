/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.source.lunabeat

import android.content.Context
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class LunaBeatLyricMatch(
    val hubId: String,
    val sha256: String,
    val rawTtml: String,
    val parsed: LunaBeatParsedLyrics,
)

internal data class LunaBeatLookupResult(
    val match: LunaBeatLyricMatch?,
    val searched: Boolean,
)

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
internal object LunaBeatTtmlRepository {
    private const val TAG = "LunaBeatTtml"
    private const val BASE_URL = "https://2755337087.github.io/ttml-hub/"
    private const val MANIFEST_URL = "${BASE_URL}api/v1/manifest.json"
    private const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1_000L
    private const val MAX_MANIFEST_BYTES = 64 * 1_024
    private const val MAX_INDEX_BYTES = 8 * 1_024 * 1_024
    private const val MAX_TTML_BYTES = 256 * 1_024
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

    private data class CatalogIndexSnapshot(
        val index: CatalogIndex,
        val networkChecked: Boolean,
    )

    @Volatile
    private var memoryIndex: CatalogIndex? = null
    @Volatile
    private var appleMusicEntries: Map<String, CatalogSong> = emptyMap()

    suspend fun findWordTimedByAppleMusicId(
        context: Context,
        appleMusicId: String,
        forceRefresh: Boolean = false,
    ): LunaBeatLookupResult = mutex.withLock {
        val normalizedId = appleMusicId.trim().takeIf(String::isNotEmpty)
            ?: return@withLock LunaBeatLookupResult(null, searched = false)
        val appContext = context.applicationContext
        val snapshot = ensureIndex(appContext, forceRefresh)
            ?: return@withLock LunaBeatLookupResult(null, searched = false)
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
            return@withLock LunaBeatLookupResult(null, searched = true)
        }
        val bytes = loadTtml(appContext, entry)
            ?: return@withLock LunaBeatLookupResult(null, searched = true)
        val rawTtml = bytes.decodeUtf8Strict()
            ?: return@withLock LunaBeatLookupResult(null, searched = true)
        val parsed = LunaBeatTtmlParser.parseWordTimed(bytes)
            ?: return@withLock LunaBeatLookupResult(null, searched = true)
        LunaBeatLookupResult(
            match = LunaBeatLyricMatch(
                hubId = entry.id,
                sha256 = entry.sha256.lowercase(),
                rawTtml = rawTtml,
                parsed = parsed,
            ),
            searched = true,
        )
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
            .header("User-Agent", "HyperLyrics-Enhanced/${BuildConfig.VERSION_NAME}")
            .apply {
                prefs.getString(KEY_ETAG, null)?.takeIf(String::isNotBlank)?.let {
                    header("If-None-Match", it)
                }
            }
            .build()
        val manifestResponse = runCatching { client.newCall(request).execute() }
            .onFailure { debug("manifest request failed: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return cachedAfterNetworkCheck()
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
                .header("User-Agent", "HyperLyrics-Enhanced/${BuildConfig.VERSION_NAME}")
                .build()
            val indexBytes = runCatching {
                client.newCall(indexRequest).execute().use { indexResponse ->
                    if (!indexResponse.isSuccessful) return@use null
                    indexResponse.body?.readLimited(MAX_INDEX_BYTES)
                }
            }.getOrNull() ?: return cachedAfterNetworkCheck()
            if (!sha256(indexBytes).equals(manifest.indexSha256, ignoreCase = true)) {
                return cachedAfterNetworkCheck()
            }
            val downloaded = parseIndex(indexBytes) ?: return cachedAfterNetworkCheck()
            if (downloaded.revision != manifest.revision) return cachedAfterNetworkCheck()
            writeAtomically(indexFile(context), indexBytes)
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

    private fun loadTtml(context: Context, song: CatalogSong): ByteArray? {
        val cacheFile = File(cacheDir(context), "${song.id}.ttml")
        if (cacheFile.isFile && cacheFile.length() in 1..MAX_TTML_BYTES.toLong()) {
            val cached = runCatching { cacheFile.readBytes() }.getOrNull()
            if (cached != null && sha256(cached).equals(song.sha256, ignoreCase = true)) return cached
            cacheFile.delete()
        }
        val normalizedPath = song.path.trimStart('/')
        if (!normalizedPath.startsWith("lyrics/") || ".." in normalizedPath) return null
        val request = Request.Builder()
            .url(BASE_URL + normalizedPath)
            .header("Accept", "application/ttml+xml, application/xml, text/xml")
            .header("User-Agent", "HyperLyrics-Enhanced/${BuildConfig.VERSION_NAME}")
            .build()
        val bytes = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.readLimited(MAX_TTML_BYTES)
            }
        }.getOrNull() ?: return null
        if (!sha256(bytes).equals(song.sha256, ignoreCase = true)) return null
        writeAtomically(cacheFile, bytes)
        return bytes
    }

    private fun cacheDir(context: Context): File =
        File(context.filesDir, "lunabeat_ttml_hub").apply { mkdirs() }

    private fun indexFile(context: Context): File = File(cacheDir(context), INDEX_FILE_NAME)

    private fun writeAtomically(target: File, bytes: ByteArray) {
        runCatching {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                target.writeBytes(bytes)
                temporary.delete()
            }
        }.onFailure { debug("cache write failed: ${it.javaClass.simpleName}") }
    }

    private fun okhttp3.ResponseBody.readLimited(maxBytes: Int): ByteArray? {
        val declared = contentLength()
        if (declared > maxBytes) return null
        byteStream().use { input ->
            val output = ByteArrayOutputStream(minOf(maxBytes, declared.coerceAtLeast(0L).toInt()))
            val buffer = ByteArray(8 * 1_024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ByteArray.decodeUtf8Strict(): String? = runCatching {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    }.getOrNull()

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
