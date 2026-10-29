/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.source.ttml

import android.content.Context
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceStatus
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.source.amll.AmllTtmlRepository
import com.juren233.hyperlyricsenhanced.online.source.lunabeat.LunaBeatTtmlRepository
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
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
        wordTimed = match != null,
        lineCount = match?.parsed?.wordLines?.size ?: 0,
    )

/** The music platform whose song ID addresses a catalog entry. */
internal enum class CatalogPlatform { APPLE_MUSIC, NETEASE, QQ_MUSIC, SPOTIFY;

    companion object {
        const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
        const val NETEASE_PACKAGE = "com.netease.cloudmusic"
        const val QQ_MUSIC_PACKAGE = "com.tencent.qqmusic"
        const val SPOTIFY_PACKAGE = "com.spotify.music"
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

    /** [searched] is false when the platform is unsupported or the ID is unusable. */
    suspend fun findWordTimed(
        context: Context,
        platform: CatalogPlatform,
        songId: String,
        forceRefresh: Boolean = false,
    ): TtmlLookupResult
}

/** The single registry of catalog sources; [Source.isTtmlCatalog] must stay in step with it. */
internal object TtmlCatalogSources {
    private val catalogs: List<TtmlCatalogSource> by lazy {
        listOf(AmllTtmlRepository, LunaBeatTtmlRepository)
    }

    fun of(source: Source): TtmlCatalogSource? = catalogs.firstOrNull { it.source == source }
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

    /** Returns false when the cache could not be written; callers treat the cache as optional. */
    fun writeAtomically(target: File, bytes: ByteArray): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(target)) {
            target.writeBytes(bytes)
            temporary.delete()
        }
    }.isSuccess

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
