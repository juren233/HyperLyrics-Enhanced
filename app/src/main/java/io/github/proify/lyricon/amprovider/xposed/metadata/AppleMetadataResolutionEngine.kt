/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy

/**
 * Pure metadata resolution policy for Apple Music.
 *
 * This component decides which requests and aliases should win. It deliberately owns no Android
 * views, Hook callbacks, resolver instances, caches, or mutable request state.
 */
internal object AppleMetadataResolutionEngine {
    internal const val ORIGINAL_METADATA_CACHE_MISS_RETRY_MS = 750L

    fun catalogMetadataResolutionPlan(
        overrideAccountLanguage: Boolean,
        restoreCjkOriginalMetadata: Boolean,
    ): CatalogMetadataResolutionPlan = CatalogMetadataResolutionPlan(
        resolveConfiguredRegion = overrideAccountLanguage,
        resolveOriginalRegion = restoreCjkOriginalMetadata,
    )

    fun inAppOriginalResolutionPlan(
        mediaIds: Collection<String>,
        awaitingLocalizedIds: Set<String>,
        mode: InAppOriginalResolutionMode,
    ): InAppOriginalResolutionPlan {
        val normalizedIds = mediaIds.distinct()
        return when (mode) {
            InAppOriginalResolutionMode.ORIGINAL_FIRST -> InAppOriginalResolutionPlan(
                beforeLocalized = normalizedIds,
                afterLocalized = emptyList(),
                resolveLocalizedImmediately = false,
            )

            InAppOriginalResolutionMode.AFTER_LOCALIZED -> InAppOriginalResolutionPlan(
                beforeLocalized = emptyList(),
                afterLocalized = normalizedIds.filterNot(awaitingLocalizedIds::contains),
                resolveLocalizedImmediately = true,
            )
        }
    }

    fun collectionPageOriginalResolutionMode(
        @Suppress("UNUSED_PARAMETER") pageType: String,
    ): InAppOriginalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST

    fun selectEffectiveMetadataAlias(
        restoreOriginalEnabled: Boolean,
        originalMetadataResolved: Boolean,
        originalMetadata: AppleInternalCatalogResolver.Alias?,
        originalArtistResolved: Boolean,
        originalArtist: AppleInternalCatalogResolver.Alias?,
        localizedMetadata: AppleInternalCatalogResolver.Alias?,
        localizedArtist: AppleInternalCatalogResolver.Alias?,
    ): AppleInternalCatalogResolver.Alias? {
        if (restoreOriginalEnabled) {
            if (originalMetadata != null) {
                return mergeMetadataArtist(
                    originalMetadata,
                    originalArtist.takeIf { originalArtistResolved },
                )
            }
            if (!originalMetadataResolved) {
                return mergeMetadataArtist(
                    localizedMetadata,
                    originalArtist.takeIf { originalArtistResolved } ?: localizedArtist,
                )
            }
            val localizedMetadataAllowed = AppleOriginalMetadataPolicy.shouldExposeLocalizedMetadata(
                restoreOriginalEnabled = true,
                originalResolved = originalMetadataResolved,
                hasOriginalMetadata = false,
            )
            if (!localizedMetadataAllowed) return null
            return mergeMetadataArtist(
                localizedMetadata,
                originalArtist.takeIf { originalArtistResolved } ?: localizedArtist,
            )
        }
        return mergeMetadataArtist(localizedMetadata, localizedArtist)
    }

    fun selectIndependentArtistAlias(
        restoreOriginalEnabled: Boolean,
        canUseAssociatedArtist: Boolean,
        originalArtist: AppleInternalCatalogResolver.Alias?,
        localizedArtist: AppleInternalCatalogResolver.Alias?,
    ): AppleInternalCatalogResolver.Alias? {
        if (!canUseAssociatedArtist) return null
        val selected = if (restoreOriginalEnabled) {
            originalArtist ?: localizedArtist
        } else {
            localizedArtist
        } ?: return null
        val artist = selected.artist.trim().takeIf(String::isNotEmpty) ?: return null
        return selected.copy(
            title = "",
            artist = artist,
            album = "",
        )
    }

    fun shouldRequestEffectiveMetadataResolution(
        restoreOriginalEnabled: Boolean,
        originalMetadataResolved: Boolean,
        hasOriginalMetadata: Boolean,
        hasAssociatedArtists: Boolean,
        originalArtistResolved: Boolean,
        hasLocalizedMetadata: Boolean,
    ): Boolean {
        if (!restoreOriginalEnabled) return !hasLocalizedMetadata
        val metadataPending = if (originalMetadataResolved) {
            !hasOriginalMetadata && !hasLocalizedMetadata
        } else {
            !hasOriginalMetadata
        }
        val artistPending = hasAssociatedArtists && !originalArtistResolved
        return metadataPending || artistPending
    }

    fun shouldRetryOriginalMetadataCacheProbe(
        originalResolved: Boolean,
        lastMissUptimeMillis: Long?,
        nowUptimeMillis: Long,
        retryAfterMillis: Long = ORIGINAL_METADATA_CACHE_MISS_RETRY_MS,
    ): Boolean {
        if (!originalResolved) return true
        val lastMiss = lastMissUptimeMillis ?: return false
        return nowUptimeMillis >= lastMiss + retryAfterMillis
    }

    fun shouldExposeOriginalMetadataOverride(
        mediaId: String,
        currentPlaybackMediaId: String?,
        confirmed: Boolean,
    ): Boolean = mediaId != currentPlaybackMediaId || confirmed

    fun associatedArtistAlias(
        artistIds: List<String>,
        aliases: Map<String, AppleInternalCatalogResolver.Alias>,
        language: String,
    ): AppleInternalCatalogResolver.Alias? {
        if (artistIds.isEmpty()) return null
        val names = artistIds.map { artistId ->
            val alias = aliases[artistId] ?: return null
            alias.artist.ifBlank { alias.title }.trim().takeIf(String::isNotEmpty) ?: return null
        }.distinct()
        val separator = if (language.startsWith("zh-", ignoreCase = true)) "、" else ", "
        return AppleInternalCatalogResolver.Alias(
            title = "",
            artist = names.joinToString(separator),
            language = language,
            album = "",
        )
    }

    fun artistIdsFromAssociationKeys(keys: Collection<String>): List<String> =
        keys.mapNotNull(::artistIdFromAssociationKey).distinct()

    fun sharedAssociatedArtistId(
        artistIds: Collection<String>,
        artistCredit: String? = null,
    ): String? {
        val artistId = normalizedAssociatedArtistIds(artistIds).singleOrNull() ?: return null
        val normalizedCredit = artistCredit?.trim().orEmpty()
        return artistId.takeIf {
            normalizedCredit.isNotEmpty() &&
                !AppleInternalCatalogResolver.isCollaborationArtistName(normalizedCredit)
        }
    }

    fun shouldUseAssociatedArtistEntities(
        artistIds: Collection<String>,
        artistCredit: String? = null,
    ): Boolean = sharedAssociatedArtistId(artistIds, artistCredit) != null

    fun shouldShareAssociatedArtistAlias(
        artistId: String,
        targetArtistIds: Collection<String>,
        targetArtistCredit: String?,
    ): Boolean = sharedAssociatedArtistId(
        artistIds = targetArtistIds,
        artistCredit = targetArtistCredit,
    ) == artistId

    fun associatedArtistCredit(
        entityType: AppleInternalCatalogResolver.LocalizedEntityType?,
        accountTitle: String?,
        accountArtist: String?,
    ): String? = if (
        entityType == AppleInternalCatalogResolver.LocalizedEntityType.ARTIST
    ) {
        accountTitle?.takeIf(String::isNotBlank) ?: accountArtist
    } else {
        accountArtist
    }

    fun shouldAcceptAssociatedArtistResolution(
        requestedArtistIds: Collection<String>,
        currentArtistIds: Collection<String>,
        artistCredit: String?,
    ): Boolean {
        val requested = normalizedAssociatedArtistIds(requestedArtistIds)
        val current = normalizedAssociatedArtistIds(currentArtistIds)
        return requested == current && shouldUseAssociatedArtistEntities(current, artistCredit)
    }

    fun localizedEntityTypeForQueueItem(
        historyEntry: Boolean,
        classNames: Collection<String>,
    ): AppleInternalCatalogResolver.LocalizedEntityType? =
        if (historyEntry) {
            AppleInternalCatalogResolver.LocalizedEntityType.SONG
        } else {
            localizedEntityTypeForContentItemClassNames(classNames)
        }

    fun localizedEntityTypeForContentItemClassNames(
        classNames: Collection<String>,
    ): AppleInternalCatalogResolver.LocalizedEntityType? {
        val excludedTokens = listOf(
            "Radio",
            "Station",
            "Playlist",
            "Editorial",
            "Recommendation",
            "Curator",
        )
        if (classNames.any { className ->
                excludedTokens.any { token -> className.contains(token, ignoreCase = true) }
            }
        ) return null
        return when {
            classNames.any { it.contains("MusicVideo", ignoreCase = true) } ->
                AppleInternalCatalogResolver.LocalizedEntityType.SONG
            classNames.any { it.contains("Song", ignoreCase = true) } ->
                AppleInternalCatalogResolver.LocalizedEntityType.SONG
            classNames.any { it.contains("Album", ignoreCase = true) } ->
                AppleInternalCatalogResolver.LocalizedEntityType.ALBUM
            classNames.any { it.contains("Artist", ignoreCase = true) } ->
                AppleInternalCatalogResolver.LocalizedEntityType.ARTIST
            else -> null
        }
    }

    fun stableArtistCacheKeys(keys: Collection<String>): Set<String> {
        val ids = keys.filterTo(linkedSetOf()) { it.startsWith("id:") }
        if (ids.isNotEmpty()) return ids
        return keys.filterTo(linkedSetOf()) { it.startsWith("name:") }
    }

    fun localizedArtistCacheKeys(keys: Collection<String>): Set<String> =
        keys.filterTo(linkedSetOf()) { key ->
            key.startsWith("id:") && key.removePrefix("id:").let { id ->
                id.isNotEmpty() && id.all(Char::isDigit)
            }
        }

    private fun artistIdFromAssociationKey(key: String): String? = key
        .removePrefix("id:")
        .takeIf { it != key && it.isNotEmpty() && it.all(Char::isDigit) }

    fun normalizedAssociatedArtistIds(artistIds: Collection<String>): Set<String> =
        artistIds.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it != "0" && it.all(Char::isDigit) }
            .toCollection(linkedSetOf())

    private fun mergeMetadataArtist(
        metadata: AppleInternalCatalogResolver.Alias?,
        artist: AppleInternalCatalogResolver.Alias?,
    ): AppleInternalCatalogResolver.Alias? {
        metadata ?: return null
        val artistName = artist?.artist?.takeIf(String::isNotBlank) ?: return metadata
        return metadata.copy(artist = artistName)
    }
}
