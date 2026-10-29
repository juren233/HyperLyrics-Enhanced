/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.source.amll

import android.content.Context
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.source.ttml.CatalogPlatform
import com.juren233.hyperlyricsenhanced.online.source.ttml.TtmlCatalogSource
import com.juren233.hyperlyricsenhanced.online.source.ttml.TtmlCatalogIo
import com.juren233.hyperlyricsenhanced.online.source.ttml.TtmlCatalogIo.readLimited
import com.juren233.hyperlyricsenhanced.online.source.ttml.TtmlLookupResult
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import java.util.concurrent.TimeUnit

/**
 * Client for AMLL TTML DB (https://github.com/amll-dev/amll-ttml-db), addressed directly by
 * platform song ID: `{am|ncm|qq|spotify}-lyrics/{id}.ttml`. The author's mirror serves the same files
 * at `/{am|ncm|qq|spotify}/{id}` and is used when the primary host is unreachable or failing.
 */
internal object AmllTtmlRepository : TtmlCatalogSource {
    override val source: Source = Source.AMLL

    private const val TAG = "AmllTtml"
    private const val PRIMARY_URL =
        "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main/"
    private const val MIRROR_URL = "https://amll-ttml-db.stevexmh.net/"
    private const val CACHE_DIR_NAME = "amll_ttml_db"
    private const val CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val MISS_TTL_MS = 6L * 60L * 60L * 1_000L
    private const val PRIMARY_BACKOFF_MS = 10L * 60L * 1_000L
    private const val MAX_REMEMBERED_MISSES = 512

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val misses = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_REMEMBERED_MISSES
    }

    @Volatile
    private var primaryFailedAtMs = 0L

    /** Catalog folder on the primary host and path segment on the mirror, per platform. */
    private data class Layout(val folder: String, val mirrorSegment: String)

    private fun layoutOf(platform: CatalogPlatform): Layout = when (platform) {
        CatalogPlatform.APPLE_MUSIC -> Layout("am-lyrics", "am")
        CatalogPlatform.NETEASE -> Layout("ncm-lyrics", "ncm")
        CatalogPlatform.QQ_MUSIC -> Layout("qq-lyrics", "qq")
        CatalogPlatform.SPOTIFY -> Layout("spotify-lyrics", "spotify")
    }

    override fun supports(platform: CatalogPlatform): Boolean = true

    private sealed interface Fetch {
        data class Hit(val bytes: ByteArray) : Fetch
        data object NotFound : Fetch
        data object Failed : Fetch
    }

    override suspend fun findWordTimed(
        context: Context,
        platform: CatalogPlatform,
        songId: String,
        forceRefresh: Boolean,
    ): TtmlLookupResult = withContext(Dispatchers.IO) {
        // IDs become URL path segments and file names: plain alphanumerics only.
        val rawId = songId.trim().takeIf { it.isNotEmpty() && it.length <= 64 && it.all(Char::isLetterOrDigit) }
            ?: return@withContext TtmlLookupResult(null, searched = false)
        val layout = layoutOf(platform)
        val id = "${layout.folder}/$rawId"
        val now = System.currentTimeMillis()
        val cacheFile = File(cacheDir(context), "${layout.folder}_$rawId.ttml")
        val cached = readCache(cacheFile)
        val cacheFresh = cached != null && now - cacheFile.lastModified() < CACHE_TTL_MS
        if (cached != null && cacheFresh && !forceRefresh) {
            return@withContext lookupFrom(id, cached)
        }
        if (cached == null && !forceRefresh && isRecentMiss(id, now)) {
            return@withContext TtmlLookupResult(null, searched = true)
        }
        when (val fetched = fetch(layout, rawId)) {
            is Fetch.Hit -> {
                synchronized(misses) { misses.remove(id) }
                TtmlCatalogIo.writeAtomically(cacheFile, fetched.bytes)
                lookupFrom(id, fetched.bytes)
            }
            Fetch.NotFound -> {
                // The catalog has no file for this ID any more; a stale copy must not linger.
                cacheFile.delete()
                synchronized(misses) { misses[id] = now }
                TtmlLookupResult(null, searched = true)
            }
            // Network trouble: a stale copy still beats nothing, and nothing is remembered.
            Fetch.Failed -> cached?.let { lookupFrom(id, it) }
                ?: TtmlLookupResult(null, searched = false)
        }
    }

    private fun lookupFrom(id: String, bytes: ByteArray): TtmlLookupResult {
        val match = TtmlCatalogIo.buildMatch(
            source = source,
            sourceLyricId = id,
            sha256 = TtmlCatalogIo.sha256(bytes),
            bytes = bytes,
        )
        return TtmlLookupResult(match, searched = true)
    }

    private fun isRecentMiss(id: String, now: Long): Boolean = synchronized(misses) {
        val at = misses[id] ?: return false
        if (now - at < MISS_TTL_MS) true else { misses.remove(id); false }
    }

    /**
     * Asks the primary host and the mirror at the same time and takes the first file found, so a
     * slow or blocked GitHub no longer holds up a lookup the mirror can answer. A missing file is
     * only trusted from the primary host, or once the mirror is the only one still running; while
     * the primary is backed off the mirror goes first and the primary is the fallback.
     */
    private suspend fun fetch(layout: Layout, id: String): Fetch = coroutineScope {
        val primaryUrl = "$PRIMARY_URL${layout.folder}/$id.ttml"
        val mirrorUrl = "$MIRROR_URL${layout.mirrorSegment}/$id"
        val primaryBackedOff =
            System.currentTimeMillis() - primaryFailedAtMs < PRIMARY_BACKOFF_MS
        val results = Channel<Pair<Boolean, Fetch>>(Channel.UNLIMITED)
        fun start(isPrimary: Boolean, url: String) {
            launch { results.send(isPrimary to request(url)) }
        }
        var running = 0
        if (primaryBackedOff) {
            start(false, mirrorUrl)
            running = 1
        } else {
            start(true, primaryUrl)
            start(false, mirrorUrl)
            running = 2
        }
        var primaryStarted = !primaryBackedOff
        var mirrorNotFound = false
        var answer: Fetch? = null
        while (running > 0 && answer == null) {
            val (isPrimary, result) = results.receive()
            running--
            when {
                result is Fetch.Hit -> answer = result
                isPrimary && result == Fetch.NotFound -> answer = result
                isPrimary && result == Fetch.Failed ->
                    primaryFailedAtMs = System.currentTimeMillis()
                !isPrimary && result == Fetch.NotFound -> mirrorNotFound = true
            }
            if (isPrimary && result != Fetch.Failed) primaryFailedAtMs = 0L
            if (answer == null && running == 0 && !primaryStarted) {
                start(true, primaryUrl)
                primaryStarted = true
                running = 1
            }
        }
        coroutineContext.cancelChildren()
        answer ?: if (mirrorNotFound) Fetch.NotFound else Fetch.Failed
    }

    private suspend fun request(url: String): Fetch = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/ttml+xml, application/xml, text/xml, text/plain")
            .header("User-Agent", TtmlCatalogIo.userAgent(BuildConfig.VERSION_NAME))
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                debug("request failed: ${e.javaClass.simpleName}")
                if (continuation.isActive) continuation.resume(Fetch.Failed)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        when {
                            it.code == 404 -> Fetch.NotFound
                            !it.isSuccessful -> Fetch.Failed
                            else -> it.body?.readLimited(TtmlCatalogIo.MAX_TTML_BYTES)
                                ?.takeIf { bytes -> bytes.isNotEmpty() }
                                ?.let(Fetch::Hit)
                                ?: Fetch.Failed
                        }
                    }
                }.getOrElse {
                    debug("request failed: ${it.javaClass.simpleName}")
                    Fetch.Failed
                }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }

    private fun readCache(file: File): ByteArray? {
        if (!file.isFile || file.length() !in 1..TtmlCatalogIo.MAX_TTML_BYTES.toLong()) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    private fun cacheDir(context: Context): File =
        File(context.applicationContext.filesDir, CACHE_DIR_NAME).apply { mkdirs() }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) HookLogger.w(TAG, "[debug] $message")
    }
}
