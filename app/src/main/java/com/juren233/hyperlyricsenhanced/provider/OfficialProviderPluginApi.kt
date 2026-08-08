/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.app.Application
import android.media.MediaMetadata
import android.media.session.PlaybackState
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Stable boundary implemented by code loaded from an official Provider Pack.
 *
 * This API deliberately contains no libxposed types. The static host owns every
 * Xposed API call, which keeps Pack code compatible with runtime API protection.
 */
interface OfficialProviderPlugin {
    fun install(host: OfficialProviderHost)
}

interface OfficialProviderHost {
    val packageName: String
    val processName: String

    fun hookApplication(callback: OfficialProviderApplicationCallback)

    fun hookMediaSession(
        playbackStateCallback: OfficialProviderPlaybackStateCallback,
        metadataCallback: OfficialProviderMetadataCallback,
    )

    /**
     * Installs an after-call Hook for an exact method descriptor in the target app.
     *
     * Provider Packs must source every identifier from the original DEX rather than
     * a decompiler display alias. Keeping libxposed objects inside the host preserves
     * the stable Pack boundary while still allowing app-specific lyric entry points.
     */
    fun hookAfterMethod(
        target: OfficialProviderMethodTarget,
        callback: OfficialProviderMethodCallback,
    )

    /**
     * Resolves an obfuscation-sensitive method from the original target DEX.
     *
     * The host first tries a previously verified exact descriptor. DexKit is
     * opened only when that cached descriptor cannot be hooked. A fresh result
     * is persisted only after the exact method Hook has been installed.
     */
    fun hookAfterDexMethod(
        application: Application,
        query: OfficialProviderDexMethodQuery,
        callback: OfficialProviderMethodCallback,
    )
}

fun interface OfficialProviderApplicationCallback {
    fun onApplicationCreated(application: Application)
}

fun interface OfficialProviderPlaybackStateCallback {
    fun onPlaybackStateChanged(state: PlaybackState?)
}

fun interface OfficialProviderMetadataCallback {
    fun onMetadataChanged(metadata: MediaMetadata?)
}

data class OfficialProviderMethodTarget(
    val className: String,
    val methodName: String,
    val parameterTypeNames: List<String> = emptyList(),
    val returnTypeName: String,
    val isStatic: Boolean,
)

/**
 * Stable, DexKit-independent method query passed across the Provider Pack ABI.
 *
 * Null constraints are intentionally left unconstrained. The host requires a
 * unique result after applying every declared constraint.
 */
data class OfficialProviderDexMethodQuery(
    val cacheKey: String,
    val declaringClassName: String? = null,
    val requiredStrings: List<String>,
    val parameterTypeNames: List<String>? = null,
    val returnTypeName: String? = null,
    val isStatic: Boolean? = null,
)

fun interface OfficialProviderMethodCallback {
    fun onMethodCalled(receiver: Any?, arguments: Array<Any?>)
}

data class OfficialProviderNextTrackFrame(
    val clear: Boolean,
    val currentId: String,
    val currentTitle: String,
    val currentArtist: String,
    val nextId: String,
    val nextTitle: String,
    val nextArtist: String,
    val nextAlbum: String,
    val nextDurationMs: Long,
)

/**
 * Additive control channel transported through Lyricon's existing sendText call.
 * Reserved frames are consumed by Central and must never reach plain-text lyrics.
 */
object OfficialProviderControlProtocol {
    const val NEXT_TRACK_PREFIX = "\u001eHLE_OFFICIAL_NEXT_TRACK_V1|"

    private const val UPDATE_OPERATION = "U"
    private const val CLEAR_OPERATION = "C"
    private const val FIELD_COUNT = 9
    private const val MAX_FRAME_LENGTH = 16 * 1024
    private const val MAX_FIELD_LENGTH = 1024

    fun encodeNextTrack(
        currentId: String,
        currentTitle: String,
        currentArtist: String,
        nextId: String,
        nextTitle: String,
        nextArtist: String,
        nextAlbum: String = "",
        nextDurationMs: Long = -1L,
    ): String = encode(
        operation = UPDATE_OPERATION,
        currentId = currentId,
        currentTitle = currentTitle,
        currentArtist = currentArtist,
        nextId = nextId,
        nextTitle = nextTitle,
        nextArtist = nextArtist,
        nextAlbum = nextAlbum,
        nextDurationMs = nextDurationMs,
    )

    fun encodeNextTrackClear(
        currentId: String = "",
        currentTitle: String = "",
        currentArtist: String = "",
    ): String = encode(
        operation = CLEAR_OPERATION,
        currentId = currentId,
        currentTitle = currentTitle,
        currentArtist = currentArtist,
        nextId = "",
        nextTitle = "",
        nextArtist = "",
        nextAlbum = "",
        nextDurationMs = -1L,
    )

    fun isReservedFrame(text: String?): Boolean = text?.startsWith(NEXT_TRACK_PREFIX) == true

    fun decodeNextTrack(text: String?): OfficialProviderNextTrackFrame? {
        if (!isReservedFrame(text) || text == null || text.length > MAX_FRAME_LENGTH) return null
        val fields = text.removePrefix(NEXT_TRACK_PREFIX).split('|')
        if (fields.size != FIELD_COUNT) return null
        val operation = fields[0]
        if (operation != UPDATE_OPERATION && operation != CLEAR_OPERATION) return null
        val decoded = fields.drop(1).dropLast(1).map { decodeField(it) ?: return null }
        val duration = fields.last().toLongOrNull()?.takeIf { it >= -1L } ?: return null
        val frame = OfficialProviderNextTrackFrame(
            clear = operation == CLEAR_OPERATION,
            currentId = decoded[0],
            currentTitle = decoded[1],
            currentArtist = decoded[2],
            nextId = decoded[3],
            nextTitle = decoded[4],
            nextArtist = decoded[5],
            nextAlbum = decoded[6],
            nextDurationMs = duration,
        )
        return frame.takeIf { it.clear || it.nextTitle.isNotBlank() }
    }

    private fun encode(
        operation: String,
        currentId: String,
        currentTitle: String,
        currentArtist: String,
        nextId: String,
        nextTitle: String,
        nextArtist: String,
        nextAlbum: String,
        nextDurationMs: Long,
    ): String = buildString {
        append(NEXT_TRACK_PREFIX)
        append(operation)
        listOf(
            currentId,
            currentTitle,
            currentArtist,
            nextId,
            nextTitle,
            nextArtist,
            nextAlbum,
        ).forEach { value ->
            append('|')
            append(encodeField(value.take(MAX_FIELD_LENGTH)))
        }
        append('|')
        append(nextDurationMs.coerceAtLeast(-1L))
    }

    private fun encodeField(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeField(value: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }.getOrNull()?.takeIf { it.length <= MAX_FIELD_LENGTH }
}
