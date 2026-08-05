/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import java.util.concurrent.ConcurrentHashMap

/** Owns Apple Music metadata overrides, identity facts, and request lifecycle state. */
internal class AppleMetadataOverrideStore {
    private val configuredMetadataOverrides =
        ConcurrentHashMap<String, AppleInternalCatalogResolver.Alias>()
    private val originalMetadataOverrides =
        ConcurrentHashMap<String, AppleInternalCatalogResolver.Alias>()
    private val confirmedOriginalMetadataIds = ConcurrentHashMap.newKeySet<String>()
    private val configuredArtistOverrides =
        ConcurrentHashMap<String, AppleInternalCatalogResolver.Alias>()
    private val originalArtistOverrides =
        ConcurrentHashMap<String, AppleInternalCatalogResolver.Alias>()
    private val sharedConfiguredArtistOverrides =
        ConcurrentHashMap<String, AppleInternalCatalogResolver.Alias>()
    private val sharedOriginalArtistOverrides =
        ConcurrentHashMap<String, AppleInternalCatalogResolver.Alias>()
    private val originalArtistResolvedIds = ConcurrentHashMap.newKeySet<String>()

    private val accountMetadataValues = ConcurrentHashMap<String, AccountMetadata>()
    private val metadataLookupIds = ConcurrentHashMap<String, Set<String>>()
    private val metadataEntityTypes =
        ConcurrentHashMap<String, AppleInternalCatalogResolver.LocalizedEntityType>()
    private val metadataArtistKeys = ConcurrentHashMap<String, Set<String>>()
    private val metadataAssociatedArtistIds = ConcurrentHashMap<String, List<String>>()
    private val associatedMediaIdsByArtistKey =
        ConcurrentHashMap<String, MutableSet<String>>()
    private val nonCatalogContentItemIds = ConcurrentHashMap.newKeySet<String>()

    private val configuredResolveRequests = ConcurrentHashMap.newKeySet<String>()
    private val configuredResolveMisses = ConcurrentHashMap.newKeySet<String>()
    private val originalResolveRequests = ConcurrentHashMap.newKeySet<String>()
    private val associatedArtistResolveRequests = ConcurrentHashMap.newKeySet<String>()
    private val originalResolvedIds = ConcurrentHashMap.newKeySet<String>()
    private val originalPendingIds = ConcurrentHashMap.newKeySet<String>()
    private val originalCacheMissUptimeMillis = ConcurrentHashMap<String, Long>()
    private val originalLanguageByArtistKey = ConcurrentHashMap<String, String>()

    @Volatile
    private var currentPlaybackOverride: AppleInternalCatalogResolver.Alias? = null

    fun onConfigurationChanged() {
        configuredMetadataOverrides.clear()
        originalMetadataOverrides.clear()
        confirmedOriginalMetadataIds.clear()
        configuredArtistOverrides.clear()
        originalArtistOverrides.clear()
        sharedConfiguredArtistOverrides.clear()
        sharedOriginalArtistOverrides.clear()
        originalArtistResolvedIds.clear()
        configuredResolveRequests.clear()
        configuredResolveMisses.clear()
        originalResolveRequests.clear()
        associatedArtistResolveRequests.clear()
        originalResolvedIds.clear()
        originalPendingIds.clear()
        originalCacheMissUptimeMillis.clear()
        originalLanguageByArtistKey.clear()
        currentPlaybackOverride = null
    }

    fun configuredMetadata(mediaId: String): AppleInternalCatalogResolver.Alias? =
        configuredMetadataOverrides[mediaId]

    fun hasConfiguredMetadata(mediaId: String): Boolean =
        configuredMetadataOverrides.containsKey(mediaId)

    fun rememberConfiguredMetadata(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ) {
        configuredMetadataOverrides[mediaId] = alias
    }

    fun rememberConfiguredMetadataIfAbsent(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ): AppleInternalCatalogResolver.Alias =
        configuredMetadataOverrides.putIfAbsent(mediaId, alias) ?: alias

    fun originalMetadata(mediaId: String): AppleInternalCatalogResolver.Alias? =
        originalMetadataOverrides[mediaId]

    fun hasOriginalMetadata(mediaId: String): Boolean =
        originalMetadataOverrides.containsKey(mediaId)

    fun rememberOriginalMetadata(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        confirmed: Boolean,
    ) {
        clearOriginalCacheMiss(mediaId)
        markOriginalResolved(mediaId)
        if (confirmed) {
            confirmedOriginalMetadataIds.add(mediaId)
            originalMetadataOverrides[mediaId] = alias
        } else {
            originalMetadataOverrides.putIfAbsent(mediaId, alias)
        }
    }

    fun isOriginalMetadataConfirmed(mediaId: String): Boolean =
        mediaId in confirmedOriginalMetadataIds

    fun configuredArtist(mediaId: String): AppleInternalCatalogResolver.Alias? =
        configuredArtistOverrides[mediaId]

    fun rememberConfiguredArtist(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ) {
        configuredArtistOverrides[mediaId] = alias
    }

    fun rememberConfiguredArtistIfAbsent(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ): AppleInternalCatalogResolver.Alias =
        configuredArtistOverrides.putIfAbsent(mediaId, alias) ?: alias

    fun removeConfiguredArtist(mediaId: String) {
        configuredArtistOverrides.remove(mediaId)
    }

    fun originalArtist(mediaId: String): AppleInternalCatalogResolver.Alias? =
        originalArtistOverrides[mediaId]

    fun rememberOriginalArtist(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ) {
        originalArtistOverrides[mediaId] = alias
    }

    fun rememberOriginalArtistIfAbsent(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ): AppleInternalCatalogResolver.Alias =
        originalArtistOverrides.putIfAbsent(mediaId, alias) ?: alias

    fun removeOriginalArtist(mediaId: String) {
        originalArtistOverrides.remove(mediaId)
    }

    fun sharedConfiguredArtist(
        selection: Int,
        artistId: String,
    ): AppleInternalCatalogResolver.Alias? =
        sharedConfiguredArtistOverrides[configuredArtistKey(selection, artistId)]

    fun rememberSharedConfiguredArtist(
        selection: Int,
        artistId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ): AppleInternalCatalogResolver.Alias? =
        sharedConfiguredArtistOverrides.put(configuredArtistKey(selection, artistId), alias)

    fun sharedOriginalArtist(artistId: String): AppleInternalCatalogResolver.Alias? =
        sharedOriginalArtistOverrides[artistId]

    fun rememberSharedOriginalArtist(
        artistId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ): AppleInternalCatalogResolver.Alias? = sharedOriginalArtistOverrides.put(artistId, alias)

    fun isOriginalArtistResolved(mediaId: String): Boolean =
        mediaId in originalArtistResolvedIds

    fun markOriginalArtistResolved(mediaId: String) {
        originalArtistResolvedIds.add(mediaId)
    }

    fun markOriginalArtistUnresolved(mediaId: String) {
        originalArtistResolvedIds.remove(mediaId)
    }

    fun accountMetadata(mediaId: String): AccountMetadata? = accountMetadataValues[mediaId]

    fun accountMetadataSnapshot(): Map<String, AccountMetadata> = HashMap(accountMetadataValues)

    fun mergeAccountMetadata(mediaId: String, incoming: AccountMetadata): AccountMetadata =
        accountMetadataValues.merge(mediaId, incoming) { previous, next ->
            AccountMetadata(
                title = previous.title ?: next.title,
                artist = previous.artist ?: next.artist,
            )
        } ?: incoming

    fun lookupIds(mediaId: String): Set<String> = metadataLookupIds[mediaId].orEmpty()

    fun mergeLookupIds(mediaId: String, lookupIds: Collection<String>) {
        metadataLookupIds.merge(mediaId, lookupIds.toSet()) { previous, incoming ->
            previous + incoming
        }
    }

    fun entityType(mediaId: String): AppleInternalCatalogResolver.LocalizedEntityType? =
        metadataEntityTypes[mediaId]

    fun rememberEntityType(
        mediaId: String,
        entityType: AppleInternalCatalogResolver.LocalizedEntityType,
    ) {
        metadataEntityTypes[mediaId] = entityType
    }

    fun artistKeys(mediaId: String): Set<String> = metadataArtistKeys[mediaId].orEmpty()

    fun mergeArtistKeys(mediaId: String, artistKeys: Collection<String>) {
        metadataArtistKeys.merge(mediaId, artistKeys.toSet()) { previous, incoming ->
            previous + incoming
        }
    }

    fun associatedArtistIds(mediaId: String): List<String> =
        metadataAssociatedArtistIds[mediaId].orEmpty()

    fun mergeAssociatedArtistIds(mediaId: String, artistIds: Collection<String>): Boolean {
        val normalizedIds = AppleMetadataResolutionEngine
            .normalizedAssociatedArtistIds(artistIds)
            .toList()
        if (normalizedIds.isEmpty()) return false
        var changed = false
        metadataAssociatedArtistIds.compute(mediaId) { _, previous ->
            val merged = (previous.orEmpty() + normalizedIds).distinct()
            changed = merged != previous
            merged
        }
        return changed
    }

    fun trackAssociatedMediaId(artistKey: String, mediaId: String) {
        associatedMediaIdsByArtistKey.computeIfAbsent(artistKey) {
            ConcurrentHashMap.newKeySet()
        }.add(mediaId)
    }

    fun associatedMediaIds(artistKey: String): Set<String> =
        associatedMediaIdsByArtistKey[artistKey]?.toSet().orEmpty()

    fun markNonCatalogContent(mediaId: String) {
        nonCatalogContentItemIds.add(mediaId)
    }

    fun markCatalogContent(mediaId: String) {
        nonCatalogContentItemIds.remove(mediaId)
    }

    fun isNonCatalogContent(mediaId: String): Boolean = mediaId in nonCatalogContentItemIds

    fun beginConfiguredRequest(requestKey: String): Boolean =
        configuredResolveRequests.add(requestKey)

    fun finishConfiguredRequest(requestKey: String) {
        configuredResolveRequests.remove(requestKey)
    }

    fun isConfiguredMiss(requestKey: String): Boolean = requestKey in configuredResolveMisses

    fun markConfiguredMiss(requestKey: String) {
        configuredResolveMisses.add(requestKey)
    }

    fun beginOriginalRequest(requestKey: String): Boolean = originalResolveRequests.add(requestKey)

    fun finishOriginalRequest(requestKey: String) {
        originalResolveRequests.remove(requestKey)
    }

    fun beginAssociatedArtistRequest(requestKey: String): Boolean =
        associatedArtistResolveRequests.add(requestKey)

    fun finishAssociatedArtistRequest(requestKey: String) {
        associatedArtistResolveRequests.remove(requestKey)
    }

    fun isOriginalResolved(mediaId: String): Boolean = mediaId in originalResolvedIds

    fun markOriginalResolved(mediaId: String) {
        originalResolvedIds.add(mediaId)
    }

    fun markOriginalPending(mediaId: String): Boolean = originalPendingIds.add(mediaId)

    fun clearOriginalPending(mediaId: String) {
        originalPendingIds.remove(mediaId)
    }

    fun isOriginalPending(mediaId: String): Boolean = mediaId in originalPendingIds

    fun pendingOriginalIds(): Set<String> = originalPendingIds.toSet()

    fun recordOriginalCacheMiss(mediaId: String, uptimeMillis: Long) {
        originalCacheMissUptimeMillis[mediaId] = uptimeMillis
    }

    fun originalCacheMissUptimeMillis(mediaId: String): Long? =
        originalCacheMissUptimeMillis[mediaId]

    fun clearOriginalCacheMiss(mediaId: String) {
        originalCacheMissUptimeMillis.remove(mediaId)
    }

    fun originalLanguage(artistKey: String): String? = originalLanguageByArtistKey[artistKey]

    fun rememberOriginalLanguage(artistKey: String, language: String) {
        originalLanguageByArtistKey[artistKey] = language
    }

    fun removeOriginalLanguage(artistKey: String, language: String): Boolean =
        originalLanguageByArtistKey.remove(artistKey, language)

    fun currentPlaybackOverride(): AppleInternalCatalogResolver.Alias? = currentPlaybackOverride

    fun updateCurrentPlaybackOverride(alias: AppleInternalCatalogResolver.Alias?) {
        currentPlaybackOverride = alias
    }

    private fun configuredArtistKey(selection: Int, artistId: String): String =
        "$selection:$artistId"
}
