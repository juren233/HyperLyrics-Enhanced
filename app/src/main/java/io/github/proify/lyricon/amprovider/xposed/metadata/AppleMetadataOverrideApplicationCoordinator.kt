/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleFrameworkMetadataHooks
import java.util.concurrent.atomic.AtomicLong

/** Publishes resolved aliases to stores, active playback, and exact in-app consumers. */
internal class AppleMetadataOverrideApplicationCoordinator(
    private val runtime: AppleMusicProviderRuntime,
    private val metadataStore: AppleMetadataOverrideStore,
    private val registry: AppleInAppMetadataRegistry,
    private val resolutionCoordinator: AppleInAppMetadataResolutionCoordinator,
    private val catalogResolver: AppleInternalCatalogResolver,
    private val surfaceRuntime: AppleMetadataSurfaceRuntime,
    private val metadataApplier: AppleInAppMetadataApplier,
    private val librarySurfaceHooks: AppleLibrarySurfaceHooks,
    private val dataBindingHooks: AppleDataBindingMetadataHooks,
    private val listenNowHooks: AppleListenNowHooks,
    private val actionSheetMetadataHooks: AppleActionSheetMetadataHooks,
    private val playbackMetadataCoordinator: ApplePlaybackMetadataCoordinator,
    private val frameworkMetadataHooks: AppleFrameworkMetadataHooks,
    private val visibleMetadataDiagnostics: AppleVisibleMetadataDiagnostics,
    private val media3MetadataCoordinator: AppleMedia3MetadataCoordinator,
    private val configuredContentUiLanguage: () -> Int,
    private val traceSequence: AtomicLong,
) {
    fun rememberOriginalMetadataOverride(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        confirmed: Boolean,
    ) {
        metadataStore.rememberOriginalMetadata(
            mediaId = mediaId,
            alias = alias,
            confirmed = confirmed,
        )
    }

    fun apply(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        forceInAppRebind: Boolean = true,
        rememberLocalizedArtist: Boolean = true,
        originalMetadata: Boolean = false,
        originalMetadataConfirmed: Boolean = false,
        artistOnly: Boolean = false,
        propagateArtistEntity: Boolean = true,
    ) {
        if (artistOnly && propagateArtistEntity) {
            val artistId = resolutionCoordinator.sharedAssociatedArtistId(mediaId)
            if (artistId != null) {
                propagateSharedArtistOverride(
                    sourceMediaId = mediaId,
                    artistId = artistId,
                    alias = alias,
                    forceInAppRebind = forceInAppRebind,
                    rememberLocalizedArtist = rememberLocalizedArtist,
                    originalMetadata = originalMetadata,
                    originalMetadataConfirmed = originalMetadataConfirmed,
                )
                return
            }
        }
        val previousEffective = resolutionCoordinator.effectiveAlias(mediaId)
        when {
            artistOnly && originalMetadata -> {
                metadataStore.markOriginalArtistResolved(mediaId)
                metadataStore.rememberOriginalArtist(mediaId, alias)
            }
            artistOnly -> metadataStore.rememberConfiguredArtist(mediaId, alias)
            originalMetadata -> metadataStore.rememberOriginalMetadata(
                mediaId = mediaId,
                alias = alias,
                confirmed = originalMetadataConfirmed,
            )
            else -> metadataStore.rememberConfiguredMetadata(mediaId, alias)
        }
        if (rememberLocalizedArtist && !originalMetadata && artistOnly) {
            catalogResolver.rememberLocalizedArtist(
                selection = configuredContentUiLanguage(),
                artistKeys = localizedArtistCacheKeys(metadataStore.artistKeys(mediaId).orEmpty()),
                localizedArtist = alias.artist,
            )
        }
        val effectiveAlias = resolutionCoordinator.effectiveAlias(mediaId)
        if (effectiveAlias == null) {
            ProviderLogger.info(
                "Apple 设定地区元数据仅缓存: id=$mediaId, reason=original_region_pending"
            )
            return
        }
        val shouldForceInAppRebind = forceInAppRebind || previousEffective != effectiveAlias
        val identity = media3MetadataCoordinator.activePlaybackIdentity()
        val appliesToActivePlayback = identity.mediaId == mediaId
        val hasBoundConsumer = metadataApplier.hasLiveModelTarget(mediaId)
        val surfaceRelevant = surfaceRuntime.isCurrentMediaId(mediaId)
        val hasVisibleExactConsumer = surfaceRuntime.hasVisibleConsumer(mediaId)
        val hasActiveVisibleLease = surfaceRuntime.hasVisibleResolutionLease(mediaId)
        val allowModelRefresh = shouldRefreshInAppSurface(
            surfaceRelevant = surfaceRelevant,
            hasVisibleExactConsumer = hasVisibleExactConsumer,
            hasActiveVisibleLease = hasActiveVisibleLease,
        ) && shouldNotifyInAppModelChange(
            mediaId = mediaId,
            activeMediaId = identity.mediaId,
            hasBoundConsumer = hasBoundConsumer,
        )
        if (BuildConfig.DEBUG) {
            media3MetadataCoordinator.logIdentity(
                event = "model_refresh_policy",
                identity = identity,
                details = "overrideId=$mediaId, entityType=${metadataStore.entityType(mediaId)}, " +
                    "forceInAppRebind=$shouldForceInAppRebind, " +
                    "requestedForceInAppRebind=$forceInAppRebind, " +
                    "allowModelRefresh=$allowModelRefresh, surfaceRelevant=$surfaceRelevant, " +
                    "visibleExactConsumer=$hasVisibleExactConsumer, " +
                    "visibleLease=$hasActiveVisibleLease, hasBoundConsumer=$hasBoundConsumer, " +
                    "epoxyRefs=${librarySurfaceHooks.controllerRefCount(mediaId)}, " +
                    "composeRefs=${librarySurfaceHooks.composeStateRefCount(mediaId)}, " +
                    "dataBindingRefs=${dataBindingHooks.refCount(mediaId)}",
            )
        }
        if (playbackMetadataCoordinator.currentMetadataId() == mediaId) {
            metadataStore.updateCurrentPlaybackOverride(effectiveAlias)
        }
        MediaMetadataCache.updateDisplayMetadata(mediaId, effectiveAlias.title, effectiveAlias.artist)
        PlaybackManager.onCatalogMetadataResolved(mediaId)
        val listenNowDirectBindingTargets = if (
            !allowModelRefresh && listenNowHooks.hasDataBindingRefs(mediaId)
        ) {
            listenNowHooks.refreshDataBindings(mediaId, effectiveAlias)
        } else {
            0
        }
        if (allowModelRefresh) {
            metadataApplier.applyAliasToMetadataRefs(
                mediaId = mediaId,
                alias = effectiveAlias,
                forceRebind = shouldForceInAppRebind,
                notifyModelChange = true,
            )
        }
        if (appliesToActivePlayback) {
            actionSheetMetadataHooks.applyAlias(mediaId, effectiveAlias)
            if (BuildConfig.DEBUG) {
                runtime.mainHandler.post {
                    visibleMetadataDiagnostics.scan("active_alias_applied")
                }
            }
        }
        val (metadataRefCount, playbackItemRefCount, containerItemRefCount) =
            registry.refCounts(mediaId)
        media3MetadataCoordinator.logIdentity(
            event = "override_applied",
            identity = identity,
            details = "overrideId=$mediaId, active=$appliesToActivePlayback, " +
                "effective=${effectiveAlias.title}/${effectiveAlias.artist}/${effectiveAlias.album}, " +
                "original=$originalMetadata, confirmed=$originalMetadataConfirmed, " +
                "artistOnly=$artistOnly, changed=${previousEffective != effectiveAlias}, " +
                "listenNowDirectBindingTargets=$listenNowDirectBindingTargets, " +
                "metadataRefs=$metadataRefCount, itemRefs=$playbackItemRefCount, " +
                "containerRefs=$containerItemRefCount",
        )
        if (previousEffective == effectiveAlias) {
            if (appliesToActivePlayback) {
                metadataApplier.refreshMetadataCallbacks(mediaId, effectiveAlias)
            }
            return
        }
        ProviderLogger.info(
            "Apple 播放元数据已覆盖: id=$mediaId, title=${effectiveAlias.title}, " +
                "artist=${effectiveAlias.artist}, language=${effectiveAlias.language}, " +
                "original=$originalMetadata, confirmed=$originalMetadataConfirmed, " +
                "artistOnly=$artistOnly"
        )
        playbackMetadataCoordinator.invokeCurrentRefresh(mediaId)
        frameworkMetadataHooks.refreshMediaSessionMetadata(mediaId, effectiveAlias)
        frameworkMetadataHooks.refreshMediaSessionQueue(mediaId)
        metadataApplier.refreshMetadataCallbacks(mediaId, effectiveAlias)
    }

    private fun propagateSharedArtistOverride(
        sourceMediaId: String,
        artistId: String,
        alias: AppleInternalCatalogResolver.Alias,
        forceInAppRebind: Boolean,
        rememberLocalizedArtist: Boolean,
        originalMetadata: Boolean,
        originalMetadataConfirmed: Boolean,
    ) {
        val previousSharedAlias = if (originalMetadata) {
            metadataStore.rememberSharedOriginalArtist(artistId, alias)
        } else {
            metadataStore.rememberSharedConfiguredArtist(
                selection = configuredContentUiLanguage(),
                artistId = artistId,
                alias = alias,
            )
        }
        val targets = linkedSetOf(sourceMediaId, artistId).apply {
            addAll(metadataStore.associatedMediaIds("id:$artistId").orEmpty())
        }
        targets.forEach { targetMediaId ->
            if (
                targetMediaId != artistId &&
                !shouldShareAssociatedArtistAlias(
                    artistId = artistId,
                    targetArtistIds = metadataStore.associatedArtistIds(targetMediaId).orEmpty(),
                    targetArtistCredit = resolutionCoordinator.associatedArtistCredit(targetMediaId),
                )
            ) return@forEach
            apply(
                mediaId = targetMediaId,
                alias = alias,
                forceInAppRebind = forceInAppRebind ||
                    previousSharedAlias != alias || targetMediaId != sourceMediaId,
                rememberLocalizedArtist = rememberLocalizedArtist,
                originalMetadata = originalMetadata,
                originalMetadataConfirmed = originalMetadataConfirmed,
                artistOnly = true,
                propagateArtistEntity = false,
            )
        }
        if (BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 元数据链路: seq=${traceSequence.incrementAndGet()}, " +
                    "event=artist_id_alias_propagated, artistId=$artistId, " +
                    "sourceId=$sourceMediaId, targets=$targets, artist=${alias.artist}, " +
                    "original=$originalMetadata"
            )
        }
    }
}
