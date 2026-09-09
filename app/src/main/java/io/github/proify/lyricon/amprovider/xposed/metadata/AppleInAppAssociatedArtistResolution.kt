/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal fun AppleInAppMetadataResolutionCoordinator.trackAssociatedMediaIds(
    mediaId: String,
    artistIds: Collection<String>,
) {
    AppleMetadataResolutionEngine.normalizedAssociatedArtistIds(artistIds).forEach { artistId ->
        metadataStore.trackAssociatedMediaId("id:$artistId", mediaId)
    }
}

internal fun AppleInAppMetadataResolutionCoordinator.associatedArtistCredit(mediaId: String): String? {
    val account = metadataStore.accountMetadata(mediaId)
    return associatedArtistCredit(
        entityType = metadataStore.entityType(mediaId),
        accountTitle = account?.title,
        accountArtist = account?.artist,
    )
}

internal fun AppleInAppMetadataResolutionCoordinator.enforceAssociatedArtistIsolation(
    mediaId: String,
    resetSafeResolution: Boolean = false,
): Boolean {
    val artistIds = metadataStore.associatedArtistIds(mediaId).orEmpty()
    if (artistIds.isEmpty()) return false
    val canUseAssociatedArtist = shouldUseAssociatedArtistEntities(
        artistIds = artistIds,
        artistCredit = associatedArtistCredit(mediaId),
    )
    if (canUseAssociatedArtist && resetSafeResolution) {
        metadataStore.markOriginalArtistUnresolved(mediaId)
    } else if (!canUseAssociatedArtist) {
        metadataStore.markOriginalArtistResolved(mediaId)
        metadataStore.removeOriginalArtist(mediaId)
        metadataStore.removeConfiguredArtist(mediaId)
    }
    return canUseAssociatedArtist
}

internal fun AppleInAppMetadataResolutionCoordinator.sharedAssociatedArtistId(mediaId: String): String? =
    sharedAssociatedArtistId(
        artistIds = metadataStore.associatedArtistIds(mediaId).orEmpty(),
        artistCredit = associatedArtistCredit(mediaId),
    )

internal fun AppleInAppMetadataResolutionCoordinator.hydrateSharedArtistOverrides(mediaId: String) {
    val artistId = sharedAssociatedArtistId(mediaId) ?: return
    metadataStore.sharedConfiguredArtist(host.configuredContentUiLanguage(), artistId)?.let { alias ->
        metadataStore.rememberConfiguredArtist(mediaId, alias)
    }
    metadataStore.sharedOriginalArtist(artistId)?.let { alias ->
        metadataStore.markOriginalArtistResolved(mediaId)
        metadataStore.rememberOriginalArtist(mediaId, alias)
    }
}

internal fun AppleInAppMetadataResolutionCoordinator.effectiveAlias(
    mediaId: String,
): Alias? {
    val selection = host.configuredContentUiLanguage()
    val associatedArtistIds = metadataStore.associatedArtistIds(mediaId).orEmpty()
    val sharedArtistId = sharedAssociatedArtistId(mediaId)
    val canUseAssociatedArtist = sharedArtistId != null
    val localizedMetadata = metadataStore.configuredMetadata(mediaId) ?: if (
        host.shouldOverrideAccountLanguage(selection)
    ) {
        val entityType = metadataStore.entityType(mediaId)
            ?: LocalizedEntityType.SONG
        catalogResolver.cachedLocalizedMetadata(
            selection = selection,
            entityType = entityType,
            mediaId = mediaId,
        )?.let { alias ->
            metadataStore.rememberConfiguredMetadataIfAbsent(mediaId, alias)
        }
    } else {
        null
    }
    val localizedArtist = if (canUseAssociatedArtist) {
        val artistKeys = buildSet {
            addAll(metadataStore.artistKeys(mediaId).orEmpty())
            associatedArtistIds.forEach { artistId -> add("id:$artistId") }
        }
        metadataStore.configuredArtist(mediaId)
            ?: sharedArtistId.let { artistId ->
                metadataStore.sharedConfiguredArtist(selection, artistId)?.also { alias ->
                    metadataStore.rememberConfiguredArtistIfAbsent(mediaId, alias)
                }
            }
            ?: catalogResolver.cachedLocalizedArtist(
                selection = selection,
                artistKeys = localizedArtistCacheKeys(artistKeys),
            )?.also { alias ->
                metadataStore.rememberConfiguredArtistIfAbsent(mediaId, alias)
            }
    } else {
        null
    }
    val originalMetadata = metadataStore.originalMetadata(mediaId)?.takeIf {
        AppleMetadataResolutionEngine.shouldExposeOriginalMetadataOverride(
            mediaId = mediaId,
            currentPlaybackMediaId = host.currentPlaybackMetadataId(),
            confirmed = metadataStore.isOriginalMetadataConfirmed(mediaId),
        )
    }
    val originalArtist = if (canUseAssociatedArtist) {
        metadataStore.originalArtist(mediaId)
            ?: sharedArtistId.let { artistId ->
                metadataStore.sharedOriginalArtist(artistId)?.also { alias ->
                    metadataStore.markOriginalArtistResolved(mediaId)
                    metadataStore.rememberOriginalArtistIfAbsent(mediaId, alias)
                }
            }
    } else {
        null
    }
    val originalArtistResolved = associatedArtistIds.isEmpty() ||
        !canUseAssociatedArtist ||
        metadataStore.isOriginalArtistResolved(mediaId)
    return selectEffectiveMetadataAlias(
        restoreOriginalEnabled = host.isRestoreOriginalEnabled(),
        originalMetadataResolved = metadataStore.isOriginalResolved(mediaId),
        originalMetadata = originalMetadata,
        originalArtistResolved = originalArtistResolved,
        originalArtist = originalArtist,
        localizedMetadata = localizedMetadata,
        localizedArtist = localizedArtist,
    ) ?: selectIndependentArtistAlias(
        restoreOriginalEnabled = host.isRestoreOriginalEnabled(),
        canUseAssociatedArtist = canUseAssociatedArtist,
        originalArtist = originalArtist,
        localizedArtist = localizedArtist,
    )
}

internal fun AppleInAppMetadataResolutionCoordinator.ensureAssociatedArtistOverride(
    mediaId: String,
    preBind: Boolean = false,
    priority: RequestPriority =
        RequestPriority.ACTIVE_PAGE,
) {
    val artistIds = metadataStore.associatedArtistIds(mediaId).orEmpty()
    if (artistIds.isEmpty()) return
    if (!shouldUseAssociatedArtistEntities(
            artistIds = artistIds,
            artistCredit = associatedArtistCredit(mediaId),
        )
    ) {
        metadataStore.markOriginalArtistResolved(mediaId)
        metadataStore.removeOriginalArtist(mediaId)
        metadataStore.removeConfiguredArtist(mediaId)
        return
    }
    val artistKeys = artistIds.mapTo(linkedSetOf()) { artistId -> "id:$artistId" }
    val selection = host.configuredContentUiLanguage()
    val cachedLocalizedArtist = catalogResolver.cachedLocalizedArtist(
        selection = selection,
        artistKeys = artistKeys,
    )
    if (cachedLocalizedArtist != null) {
        metadataStore.rememberConfiguredArtistIfAbsent(
            mediaId,
            cachedLocalizedArtist,
        )
        host.applyPlaybackMetadataOverride(
            mediaId = mediaId,
            alias = cachedLocalizedArtist,
            forceInAppRebind = !preBind,
            rememberLocalizedArtist = false,
            artistOnly = true,
        )
    }
    if (!host.isRestoreOriginalEnabled()) {
        if (cachedLocalizedArtist == null) {
            resolveLocalizedAssociatedArtist(mediaId, artistIds, preBind, priority = priority)
        }
        return
    }

    val originalLanguage = if (artistIds.size == 1) originalLanguageFor(mediaId) else null
    if (originalLanguage != null) {
        resolveOriginalAssociatedArtist(
            mediaId = mediaId,
            artistIds = artistIds,
            language = originalLanguage,
            preBind = preBind,
            priority = priority,
        )
    } else {
        resolveCachedOriginalAssociatedArtist(
            mediaId = mediaId,
            artistIds = artistIds,
            preBind = preBind,
            priority = priority,
        )
    }
}

internal fun AppleInAppMetadataResolutionCoordinator.resolveCachedOriginalAssociatedArtist(
    mediaId: String,
    artistIds: List<String>,
    preBind: Boolean,
    priority: RequestPriority,
) {
    val requestKey = "original-artist-cache:$mediaId:" + artistIds.joinToString(",")
    if (!metadataStore.beginAssociatedArtistRequest(requestKey)) return
    var bindingPhase = true
    collectAssociatedArtistAliases(
        artistIds = artistIds,
        request = { artistId, callback ->
            catalogResolver.resolveCachedOriginalEntity(
                mediaId = artistId,
                entityType = LocalizedEntityType.ARTIST,
                lookupIds = metadataStore.lookupIds(artistId).orEmpty(),
                onResolved = callback,
            )
        },
    ) { resolved ->
        val callbackPreBind = preBind && bindingPhase
        metadataStore.finishAssociatedArtistRequest(requestKey)
        if (!shouldAcceptAssociatedArtistResolution(
                requestedArtistIds = artistIds,
                currentArtistIds =
                    metadataStore.associatedArtistIds(mediaId).orEmpty(),
                artistCredit = associatedArtistCredit(mediaId),
            )
        ) {
            enforceAssociatedArtistIsolation(mediaId)
            publishResolvedAssociatedArtistFallback(mediaId, callbackPreBind)
            return@collectAssociatedArtistAliases
        }
        val language = resolved.values.firstOrNull()?.language.orEmpty()
        val alias = associatedArtistAlias(artistIds, resolved, language)
        if (alias != null) {
            metadataStore.markOriginalArtistResolved(mediaId)
            alias.language.takeIf(String::isNotBlank)?.let { originalLanguage ->
                rememberOriginalLanguageForArtist(mediaId, originalLanguage)
            }
            host.applyPlaybackMetadataOverride(
                mediaId = mediaId,
                alias = alias,
                forceInAppRebind = !callbackPreBind,
                rememberLocalizedArtist = false,
                originalMetadata = true,
                artistOnly = true,
            )
        } else {
            resolveLocalizedAssociatedArtist(
                mediaId = mediaId,
                artistIds = artistIds,
                preBind = callbackPreBind,
                priority = priority,
                completesOriginalArtistResolution = true,
            )
        }
    }
    bindingPhase = false
}

internal fun AppleInAppMetadataResolutionCoordinator.resolveOriginalAssociatedArtist(
    mediaId: String,
    artistIds: List<String>,
    language: String,
    preBind: Boolean,
    priority: RequestPriority,
) {
    val canonicalLanguage = canonicalOriginalLanguage(language)
    val cached = metadataStore.originalArtist(mediaId)
    if (cached != null &&
        shouldAcceptAssociatedArtistResolution(
            requestedArtistIds = artistIds,
            currentArtistIds = metadataStore.associatedArtistIds(mediaId).orEmpty(),
            artistCredit = associatedArtistCredit(mediaId),
        ) &&
        canonicalOriginalLanguage(cached.language) == canonicalLanguage
    ) {
        metadataStore.markOriginalArtistResolved(mediaId)
        return
    }
    val requestKey = "original-artist:$canonicalLanguage:$mediaId:" +
        artistIds.joinToString(",")
    if (!metadataStore.beginAssociatedArtistRequest(requestKey)) return
    var bindingPhase = true
    collectAssociatedArtistAliases(
        artistIds = artistIds,
        request = { artistId, callback ->
            catalogResolver.resolveOriginalEntityForLanguage(
                mediaId = artistId,
                lookupIds = listOf(artistId),
                entityType = LocalizedEntityType.ARTIST,
                language = canonicalLanguage,
                priority = priority,
                onResolved = callback,
            )
        },
    ) { resolved ->
        val callbackPreBind = preBind && bindingPhase
        metadataStore.finishAssociatedArtistRequest(requestKey)
        if (!shouldAcceptAssociatedArtistResolution(
                requestedArtistIds = artistIds,
                currentArtistIds =
                    metadataStore.associatedArtistIds(mediaId).orEmpty(),
                artistCredit = associatedArtistCredit(mediaId),
            )
        ) {
            enforceAssociatedArtistIsolation(mediaId)
            publishResolvedAssociatedArtistFallback(mediaId, callbackPreBind)
            return@collectAssociatedArtistAliases
        }
        val alias = associatedArtistAlias(artistIds, resolved, canonicalLanguage)
        if (alias != null) {
            metadataStore.markOriginalArtistResolved(mediaId)
            host.applyPlaybackMetadataOverride(
                mediaId = mediaId,
                alias = alias,
                forceInAppRebind = !callbackPreBind,
                rememberLocalizedArtist = false,
                originalMetadata = true,
                artistOnly = true,
            )
        } else {
            resolveLocalizedAssociatedArtist(
                mediaId = mediaId,
                artistIds = artistIds,
                preBind = callbackPreBind,
                priority = priority,
                completesOriginalArtistResolution = true,
            )
        }
    }
    bindingPhase = false
}

internal fun AppleInAppMetadataResolutionCoordinator.resolveLocalizedAssociatedArtist(
    mediaId: String,
    artistIds: List<String>,
    preBind: Boolean,
    priority: RequestPriority,
    completesOriginalArtistResolution: Boolean = false,
) {
    val selection = host.configuredContentUiLanguage()
    val requestKey = "localized-artist:$selection:$mediaId:" +
        artistIds.joinToString(",")
    if (!metadataStore.beginAssociatedArtistRequest(requestKey)) return
    var bindingPhase = true
    collectAssociatedArtistAliases(
        artistIds = artistIds,
        request = { artistId, callback ->
            catalogResolver.resolveForContentUiLanguage(
                mediaId = artistId,
                lookupIds = listOf(artistId),
                entityType = LocalizedEntityType.ARTIST,
                selection = selection,
                priority = priority,
                onResolved = callback,
            )
        },
    ) { resolved ->
        val callbackPreBind = preBind && bindingPhase
        metadataStore.finishAssociatedArtistRequest(requestKey)
        if (host.configuredContentUiLanguage() != selection) return@collectAssociatedArtistAliases
        if (!shouldAcceptAssociatedArtistResolution(
                requestedArtistIds = artistIds,
                currentArtistIds =
                    metadataStore.associatedArtistIds(mediaId).orEmpty(),
                artistCredit = associatedArtistCredit(mediaId),
            )
        ) {
            enforceAssociatedArtistIsolation(mediaId)
            if (completesOriginalArtistResolution) {
                publishResolvedAssociatedArtistFallback(mediaId, callbackPreBind)
            }
            return@collectAssociatedArtistAliases
        }
        val language = languageTagForContentUiLanguage(selection)
            .orEmpty()
        val alias = associatedArtistAlias(artistIds, resolved, language)
        if (completesOriginalArtistResolution) {
            metadataStore.markOriginalArtistResolved(mediaId)
        }
        if (alias == null) {
            if (completesOriginalArtistResolution) {
                publishResolvedAssociatedArtistFallback(mediaId, callbackPreBind)
            }
            return@collectAssociatedArtistAliases
        }
        catalogResolver.rememberLocalizedArtist(
            selection = selection,
            artistKeys = artistIds.map { artistId -> "id:$artistId" },
            localizedArtist = alias.artist,
            language = language,
        )
        host.applyPlaybackMetadataOverride(
            mediaId = mediaId,
            alias = alias,
            forceInAppRebind = !callbackPreBind,
            rememberLocalizedArtist = false,
            artistOnly = true,
        )
    }
    bindingPhase = false
}

internal fun AppleInAppMetadataResolutionCoordinator.publishResolvedAssociatedArtistFallback(
    mediaId: String,
    preBind: Boolean,
) {
    val original = metadataStore.originalMetadata(mediaId)
    if (original != null) {
        host.applyPlaybackMetadataOverride(
            mediaId = mediaId,
            alias = original,
            forceInAppRebind = !preBind,
            rememberLocalizedArtist = false,
            originalMetadata = true,
            originalMetadataConfirmed = metadataStore.isOriginalMetadataConfirmed(mediaId),
        )
        return
    }
    metadataStore.configuredMetadata(mediaId)?.let { localized ->
        host.applyPlaybackMetadataOverride(
            mediaId = mediaId,
            alias = localized,
            forceInAppRebind = !preBind,
            rememberLocalizedArtist = false,
        )
    }
}

internal fun AppleInAppMetadataResolutionCoordinator.collectAssociatedArtistAliases(
    artistIds: List<String>,
    request: (String, (Alias?) -> Unit) -> Unit,
    onComplete: (Map<String, Alias>) -> Unit,
) {
    if (artistIds.isEmpty()) {
        onComplete(emptyMap())
        return
    }
    val resolved = ConcurrentHashMap<String, Alias>()
    val remaining = AtomicInteger(artistIds.size)
    artistIds.forEach { artistId ->
        request(artistId) { alias ->
            if (alias != null) resolved[artistId] = alias
            if (remaining.decrementAndGet() == 0) onComplete(resolved)
        }
    }
}

