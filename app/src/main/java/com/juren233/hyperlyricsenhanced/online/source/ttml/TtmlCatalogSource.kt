/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.source.ttml

import com.juren233.hyperlyricsenhanced.common.coroutines.ModuleDispatchers
import android.content.Context
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceStatus
import com.juren233.hyperlyricsenhanced.online.cache.CachedSearchSource
import com.juren233.hyperlyricsenhanced.online.cache.CatalogCacheRef
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheKind
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheSong
import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
import com.juren233.hyperlyricsenhanced.online.isWordTimedOnlineLyrics
import kotlinx.coroutines.withContext
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.source.amll.AmllTtmlRepository
import com.juren233.hyperlyricsenhanced.online.source.lunabeat.LunaBeatTtmlRepository
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal data class TtmlLyricMatch(
    val source: Source,
    /** Identifier of the lyric file inside the catalog (hub id / catalog file id). */
    val sourceLyricId: String,
    val sha256: String,
    val rawTtml: String,
    val parsed: TtmlParsedLyrics,
)

internal data class TtmlLookupResult(
    val match: TtmlLyricMatch?,
    val searched: Boolean,
)

/** Maps a lookup to the per-source status shown by the source menu. */
internal fun TtmlLookupResult.toSourceStatus(source: Source): AppleMissingLyricsSourceStatus =
    AppleMissingLyricsSourceStatus(
        source = source.name,
        searched = searched,
        found = match != null,
        wordTimed = isWordTimedOnlineLyrics(match?.parsed?.wordLines),
        lineCount = match?.parsed?.wordLines?.size ?: 0,
    )

/** The music platform whose song ID addresses a catalog entry. */
internal enum class CatalogPlatform { APPLE_MUSIC, NETEASE, QQ_MUSIC, SPOTIFY;

    companion object {
        const val APPLE_MUSIC_PACKAGE = OnlineTranslationSourcePreferences.APPLE_MUSIC_PACKAGE
        const val NETEASE_PACKAGE = OnlineTranslationSourcePreferences.NETEASE_PACKAGE
        const val QQ_MUSIC_PACKAGE = OnlineTranslationSourcePreferences.QQ_MUSIC_PACKAGE
        const val SPOTIFY_PACKAGE = OnlineTranslationSourcePreferences.SPOTIFY_PACKAGE
        private const val SPOTIFY_TRACK_URI_PREFIX = "spotify:track:"

        /**
         * Platform for a player package, whose MediaSession `MEDIA_ID` is that platform's song ID.
         * NetEase, QQ Music and Spotify were verified on a device (2026-10-28/29); other players are unknown.
         */
        fun ofPlayerPackage(packageName: String?): CatalogPlatform? = when (packageName) {
            APPLE_MUSIC_PACKAGE -> APPLE_MUSIC
            NETEASE_PACKAGE -> NETEASE
            QQ_MUSIC_PACKAGE -> QQ_MUSIC
            SPOTIFY_PACKAGE -> SPOTIFY
            else -> null
        }

        /** The catalog song ID carried by a player's `MEDIA_ID`; Spotify wraps it as `spotify:track:<id>`. */
        fun songIdOf(platform: CatalogPlatform, mediaId: String?): String? {
            val id = mediaId?.trim().orEmpty()
            return when (platform) {
                SPOTIFY -> id.removePrefix(SPOTIFY_TRACK_URI_PREFIX).takeIf {
                    id.startsWith(SPOTIFY_TRACK_URI_PREFIX) && it.isNotEmpty()
                }
                else -> id.takeIf { it.isNotEmpty() }
            }
        }
    }
}

/**
 * A word-timed TTML catalog addressed by a platform song ID. Catalog sources never run a
 * title/artist search; a miss means the catalog has no file for that ID.
 */
internal interface TtmlCatalogSource {
    val source: Source

    fun supports(platform: CatalogPlatform): Boolean

    /**
     * [searched] is false when the platform is unsupported, the ID is unusable or the catalog could
     * not be reached. A file found once is kept until [clear]; a miss is trusted for
     * [com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCachePolicy.MISS_TTL_MS] unless
     * [retryMiss] (the user picked this source by hand).
     */
    suspend fun findWordTimed(
        context: Context,
        platform: CatalogPlatform,
        songId: String,
        retryMiss: Boolean = false,
    ): TtmlLookupResult

    /** Forgets the cached file and remembered miss for one song ID. */
    suspend fun clear(context: Context, platform: CatalogPlatform, songId: String)

    /** Forgets every cached file and remembered miss of this catalog (catalog indexes are kept). */
    suspend fun clearAll(context: Context)

    /** Bytes of cached lyric files on disk. */
    fun cacheBytes(context: Context): Long
}

/**
 * [TtmlCatalogSource.findWordTimed] that also files the answer under [cacheSong] in the online
 * lyric cache index, so the cache management page lists it and can clear it with the song.
 * A lookup that could not run ([TtmlLookupResult.searched] false) is not filed.
 */
internal suspend fun TtmlCatalogSource.lookup(
    context: Context,
    platform: CatalogPlatform,
    songId: String,
    retryMiss: Boolean = false,
    cacheSong: OnlineLyricCacheSong?,
): TtmlLookupResult {
    val result = findWordTimed(context, platform, songId, retryMiss)
    if (cacheSong != null && result.searched) {
        withContext(ModuleDispatchers.IO) {
            val found = result.match != null
            val cache = CachedSearchSource.cacheFor(context)
            cache.store(
                song = cacheSong,
                key = CatalogCacheRef(source, platform.name, songId).key,
                payload = ByteArray(0),
                permanent = found,
                source = source,
                kind = OnlineLyricCacheKind.CATALOG,
                sizeBytes = result.match?.rawTtml?.toByteArray()?.size?.toLong() ?: 0L,
            )
            // Addressed by song ID: there is no match score to record, only the timing.
            result.match?.let { match ->
                cache.recordMatch(cacheSong, source, score = null, wordTimed = isWordTimedOnlineLyrics(match.parsed.wordLines))
            }
        }
    }
    return result
}

/** Bytes of the files directly inside [dir]. */
internal fun directorySize(dir: File): Long =
    dir.listFiles()?.filter(File::isFile)?.sumOf(File::length) ?: 0L

/** The single registry of catalog sources; [Source.isTtmlCatalog] must stay in step with it. */
internal object TtmlCatalogSources {
    private val catalogs: List<TtmlCatalogSource> by lazy {
        listOf(AmllTtmlRepository, LunaBeatTtmlRepository)
    }

    fun of(source: Source): TtmlCatalogSource? = catalogs.firstOrNull { it.source == source }

    val all: List<TtmlCatalogSource> get() = catalogs
}

/** Limits and I/O helpers shared by every TTML catalog client. */
internal object TtmlCatalogIo {
    const val MAX_TTML_BYTES = 256 * 1_024

    fun userAgent(versionName: String): String = "HyperLyrics-Enhanced/$versionName"

    fun ResponseBody.readLimited(maxBytes: Int): ByteArray? {
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

    fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun ByteArray.decodeUtf8Strict(): String? = runCatching {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    }.getOrNull()

    /**
     * Replaces [target] with [bytes] in one rename, so a reader sees the old file or the new one,
     * never a part. Each write has a temporary file of its own: concurrent writers of one target
     * (two lookups of one song, or two module generations after a SystemUI hot reload) would
     * otherwise truncate each other's temporary file and could rename a partly written one into place.
     * Returns false when the cache could not be written, leaving [target] as it was; callers treat
     * the cache as optional.
     */
    fun writeAtomically(target: File, bytes: ByteArray): Boolean {
        val directory = target.absoluteFile.parentFile ?: return false
        var temporary: File? = null
        val written = runCatching {
            directory.mkdirs()
            val file = File.createTempFile("${target.name}.", TEMPORARY_SUFFIX, directory)
            temporary = file
            file.writeBytes(bytes)
            // Not File.renameTo: on the build host it returned false now and then while other
            // writers renamed onto the same target, where an immediate Files.move succeeded
            // (TtmlCatalogIoWriteTest, 2026-11-23). Files.move reports why it failed.
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            true
        }.getOrDefault(false)
        if (!written) temporary?.delete()
        return written
    }

    /** Suffix of [writeAtomically]'s temporary files; a crash between write and rename can leave one. */
    const val TEMPORARY_SUFFIX = ".tmp"

    /**
     * Whether [bytes] are a whole TTML file worth keeping even when [buildMatch] finds no word
     * timing: strict UTF-8 (what [buildMatch] requires of a match) and a complete TTML document
     * ([TtmlLyricsParser.isTtmlDocument]). False for a file cut short or corrupted.
     */
    fun isCompleteTtml(bytes: ByteArray): Boolean =
        bytes.decodeUtf8Strict() != null && TtmlLyricsParser.isTtmlDocument(bytes)

    /** Builds a match from raw bytes, or null when they are not strict UTF-8 word-timed TTML. */
    fun buildMatch(
        source: Source,
        sourceLyricId: String,
        sha256: String,
        bytes: ByteArray,
    ): TtmlLyricMatch? {
        val rawTtml = bytes.decodeUtf8Strict() ?: return null
        val parsed = TtmlLyricsParser.parseWordTimed(bytes) ?: return null
        return TtmlLyricMatch(
            source = source,
            sourceLyricId = sourceLyricId,
            sha256 = sha256.lowercase(),
            rawTtml = rawTtml,
            parsed = parsed,
        )
    }
}
