/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import java.lang.reflect.Method

internal data class PlaybackMetadataRefresh(
    val mediaId: String,
    val refresh: () -> Unit,
)

internal data class ActivePlaybackMediaIdentity(
    val mediaId: String?,
    val source: String,
    val candidates: String,
)

internal data class AccountMetadata(
    val title: String?,
    val artist: String?,
)

internal data class AppliedMetadataAlias(
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String,
    val language: String,
) {
    constructor(mediaId: String, alias: AppleInternalCatalogResolver.Alias) : this(
        mediaId = mediaId,
        title = alias.title,
        artist = alias.artist,
        album = alias.album,
        language = alias.language,
    )
}

internal data class PendingMetadataLookup(
    val requestKey: String,
    val lookup: AppleInternalCatalogResolver.LocalizedLookup,
)

internal data class MetadataSurfaceSignature(
    val coordinatorRevision: Long,
    val visibleMediaIds: Set<String>,
    val activePageMediaIds: Set<String>,
)

internal data class PlaybackPositionSource(
    val player: Any,
    val getCurrentPosition: Method,
) {
    fun readPosition(): Long? = getCurrentPosition.invoke(player) as? Long
}

internal data class CatalogMetadataResolutionPlan(
    val resolveConfiguredRegion: Boolean,
    val resolveOriginalRegion: Boolean,
)

internal enum class InAppOriginalResolutionMode {
    AFTER_LOCALIZED,
    ORIGINAL_FIRST,
}

internal data class DeferredMetadataResolution(
    val priority: AppleInternalCatalogResolver.RequestPriority,
    val originalResolutionMode: InAppOriginalResolutionMode,
)

internal data class InAppOriginalResolutionPlan(
    val beforeLocalized: List<String>,
    val afterLocalized: List<String>,
    val resolveLocalizedImmediately: Boolean,
)
