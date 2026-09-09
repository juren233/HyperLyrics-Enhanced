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

/**
 * Owns deferred, configured-region, original-region, and associated-artist metadata resolution.
 * Page/model mutation is only published through the narrow host callbacks above.
 */
internal class AppleInAppMetadataResolutionCoordinator(
    private val runtime: AppleMusicProviderRuntime,
    internal val metadataStore: AppleMetadataOverrideStore,
    internal val catalogResolver: AppleInternalCatalogResolver,
    internal val host: AppleInAppMetadataResolutionHost,
) {
    private val deferredMetadataResolutions =
        linkedMapOf<String, DeferredMetadataResolution>()
    private var deferredMetadataResolutionScheduled = false

    fun schedule(
        mediaIds: Collection<String>,
        priority: RequestPriority,
        originalResolutionMode: InAppOriginalResolutionMode =
            InAppOriginalResolutionMode.AFTER_LOCALIZED,
    ) {
        if (priority == RequestPriority.BACKGROUND) return
        host.enrichLibraryEntitiesForResolution(mediaIds)
        val unresolvedIds = normalizedRecyclerBindingMediaIds(mediaIds)
            .filter(::shouldRequestOverride)
        if (unresolvedIds.isEmpty()) return
        val incoming = DeferredMetadataResolution(
            priority = priority,
            originalResolutionMode = originalResolutionMode,
        )
        val shouldPost = synchronized(deferredMetadataResolutions) {
            unresolvedIds.forEach { mediaId ->
                val previous = deferredMetadataResolutions[mediaId]
                deferredMetadataResolutions[mediaId] =
                    mergeDeferredMetadataResolution(previous, incoming)
            }
            if (deferredMetadataResolutionScheduled) {
                false
            } else {
                deferredMetadataResolutionScheduled = true
                true
            }
        }
        if (!shouldPost) return
        runtime.mainHandler.post {
            val pending = synchronized(deferredMetadataResolutions) {
                deferredMetadataResolutions.entries
                    .map { it.key to it.value }
                    .also {
                        deferredMetadataResolutions.clear()
                        deferredMetadataResolutionScheduled = false
                    }
            }
            pending
                .groupBy(keySelector = { it.second }, valueTransform = { it.first })
                .entries
                .sortedByDescending { it.key.priority.ordinal }
                .forEach { (resolution, pendingIds) ->
                    val stillUnresolved = pendingIds.filter(::shouldRequestOverride)
                    if (stillUnresolved.isNotEmpty()) {
                        ensureOverrides(
                            mediaIds = stillUnresolved,
                            preBind = true,
                            priority = resolution.priority,
                            originalResolutionMode = resolution.originalResolutionMode,
                        )
                    }
                }
        }
    }

    fun mergePlaybackAssociatedArtistIds(
        mediaId: String,
        artistIds: Collection<String>,
    ) {
        val normalizedIds = artistIds.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it != "0" && it.all(Char::isDigit) }
            .distinct()
            .toList()
        if (normalizedIds.isEmpty()) return
        val previousAlias = effectiveAlias(mediaId)
        trackAssociatedMediaIds(mediaId, normalizedIds)
        val changed = metadataStore.mergeAssociatedArtistIds(mediaId, normalizedIds)
        if (changed) {
            enforceAssociatedArtistIsolation(
                mediaId = mediaId,
                resetSafeResolution = true,
            )
            hydrateSharedArtistOverrides(mediaId)
            host.refreshRequestScope()
            changedAssociatedArtistAlias(
                previousAlias = previousAlias,
                updatedAlias = effectiveAlias(mediaId),
            )?.let { updatedAlias ->
                if (BuildConfig.DEBUG) {
                    host.logMetadataIdentity(
                        event = "associated_artist_alias_hydrated",
                        details = "contentId=$mediaId, artistIds=$normalizedIds, " +
                            "before=${previousAlias?.title}/${previousAlias?.artist}/" +
                            "${previousAlias?.album}, " +
                            "after=${updatedAlias.title}/${updatedAlias.artist}/" +
                            updatedAlias.album,
                    )
                }
                host.applyAliasToMetadataRefs(
                    mediaId = mediaId,
                    alias = updatedAlias,
                    forceRebind = true,
                    notifyModelChange = true,
                )
            }
        }
    }

    fun ensureOverride(
        mediaId: String,
        preBind: Boolean = false,
        priority: RequestPriority =
            RequestPriority.ACTIVE_PAGE,
        originalResolutionMode: InAppOriginalResolutionMode =
            InAppOriginalResolutionMode.AFTER_LOCALIZED,
    ) {
        ensureOverrides(
            mediaIds = listOf(mediaId),
            preBind = preBind,
            priority = priority,
            originalResolutionMode = originalResolutionMode,
        )
    }

    fun ensureOverrides(
        mediaIds: Collection<String>,
        preBind: Boolean = false,
        originalResolutionLimit: Int = Int.MAX_VALUE,
        priority: RequestPriority =
            RequestPriority.BACKGROUND,
        originalResolutionMode: InAppOriginalResolutionMode =
            InAppOriginalResolutionMode.AFTER_LOCALIZED,
    ) {
        val normalizedIds = mediaIds.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it.all(Char::isDigit) }
            .filterNot(metadataStore::isNonCatalogContent)
            .distinct()
            .toList()
        if (normalizedIds.isEmpty()) return
        catalogResolver.promotePendingRequests(
            mediaIds = normalizedIds + normalizedIds.flatMap { mediaId ->
                metadataStore.associatedArtistIds(mediaId).orEmpty()
            },
            priority = priority,
        )
        if (!host.isRestoreOriginalEnabled()) {
            ensureLocalizedInAppMetadataOverrides(normalizedIds, preBind, priority)
            return
        }

        val originalResolutionIds = normalizedIds.take(originalResolutionLimit.coerceAtLeast(0))
        originalResolutionIds.forEach { mediaId ->
            if (
                !metadataStore.associatedArtistIds(mediaId).isNullOrEmpty() &&
                !metadataStore.isOriginalArtistResolved(mediaId)
            ) {
                ensureAssociatedArtistOverride(mediaId, preBind, priority)
            }
        }
        val beforeLocalizedPlan = inAppOriginalResolutionPlan(
            mediaIds = originalResolutionIds,
            awaitingLocalizedIds = emptySet(),
            mode = originalResolutionMode,
        )
        if (BuildConfig.DEBUG &&
            beforeLocalizedPlan.beforeLocalized.isNotEmpty()
        ) {
            beforeLocalizedPlan.beforeLocalized.forEach { mediaId ->
                val account = metadataStore.accountMetadata(mediaId)
                ProviderLogger.info(
                    "Apple Music 元数据链路: " +
                        "seq=${host.nextTraceSequence()}, " +
                        "event=original_song_visible_dispatch, contentId=$mediaId, " +
                        "title=${account?.title}, artist=${account?.artist}, " +
                        "priority=$priority, " +
                        "resolved=${metadataStore.isOriginalResolved(mediaId)}, " +
                        "hasAlias=${metadataStore.hasOriginalMetadata(mediaId)}"
                )
            }
        }
        ensureOriginalInAppMetadataOverrides(
            beforeLocalizedPlan.beforeLocalized,
            preBind,
            priority,
        )
        val awaitingLocalizedIds = if (beforeLocalizedPlan.resolveLocalizedImmediately) {
            ensureLocalizedInAppMetadataOverrides(
                normalizedIds,
                preBind,
                priority,
            )
        } else {
            emptySet()
        }
        val afterLocalizedPlan = inAppOriginalResolutionPlan(
            mediaIds = originalResolutionIds,
            awaitingLocalizedIds = awaitingLocalizedIds,
            mode = originalResolutionMode,
        )
        ensureOriginalInAppMetadataOverrides(
            afterLocalizedPlan.afterLocalized,
            preBind,
            priority,
        )
        val localizedFallbackIds = normalizedIds.filter { mediaId ->
            metadataStore.isOriginalResolved(mediaId) &&
                !metadataStore.hasOriginalMetadata(mediaId)
        }
        ensureLocalizedInAppMetadataOverrides(localizedFallbackIds, preBind, priority)
    }

    private fun ensureOriginalInAppMetadataOverrides(
        mediaIds: Collection<String>,
        preBind: Boolean,
        priority: RequestPriority =
            RequestPriority.BACKGROUND,
    ) {
        mediaIds.forEach { mediaId ->
            if ((metadataStore.isOriginalResolved(mediaId) &&
                !shouldRetryOriginalMetadataCacheProbe(mediaId)) ||
                metadataStore.hasOriginalMetadata(mediaId)
            ) return@forEach
            val account = metadataStore.accountMetadata(mediaId)
            if (account?.title.isNullOrBlank()) {
                metadataStore.markOriginalPending(mediaId)
                return@forEach
            }
            val entityType = metadataStore.entityType(mediaId)
                ?: LocalizedEntityType.SONG
            if (entityType == LocalizedEntityType.SONG) {
                resolveOriginalSongForInApp(mediaId, account, preBind, priority)
            } else {
                val language = originalLanguageFor(mediaId)
                if (language == null) {
                    if (!metadataStore.markOriginalPending(mediaId)) return@forEach
                    resolveCachedOriginalEntity(
                        mediaId = mediaId,
                        entityType = entityType,
                        preBind = preBind,
                        priority = priority,
                    )
                } else {
                    resolveOriginalEntityForInApp(
                        mediaId = mediaId,
                        entityType = entityType,
                        language = language,
                        preBind = preBind,
                        priority = priority,
                    )
                }
            }
        }
    }

    fun resolveCachedOriginalEntity(
        mediaId: String,
        entityType: LocalizedEntityType,
        preBind: Boolean,
        priority: RequestPriority,
    ) {
        val requestKey = "original-cache:$entityType:$mediaId"
        if (!metadataStore.beginOriginalRequest(requestKey)) return
        var bindingPhase = true
        catalogResolver.resolveCachedOriginalEntity(
            mediaId = mediaId,
            entityType = entityType,
            lookupIds = metadataStore.lookupIds(mediaId).orEmpty(),
            onResolved = { alias ->
                metadataStore.finishOriginalRequest(requestKey)
                if (!host.isRestoreOriginalEnabled()) return@resolveCachedOriginalEntity
                if (alias == null) {
                    // 这是一次暂时的缓存未命中，不代表原名永久不存在；资料库页稍后可能写入同一 ID。
                    metadataStore.recordOriginalCacheMiss(
                        mediaId,
                        SystemClock.uptimeMillis(),
                    )
                    metadataStore.markOriginalResolved(mediaId)
                    metadataStore.clearOriginalPending(mediaId)
                    if (BuildConfig.DEBUG) {
                        host.logMetadataIdentity(
                            event = "original_cache_resolve_finished",
                            details = "contentId=$mediaId, entityType=$entityType, hit=false, " +
                                "confirmed=false, preBind=$preBind",
                        )
                    }
                    ensureLocalizedInAppMetadataOverrides(
                        mediaIds = listOf(mediaId),
                        preBind = false,
                        priority = priority,
                    )
                    return@resolveCachedOriginalEntity
                }
                metadataStore.clearOriginalPending(mediaId)
                alias.language.takeIf(String::isNotBlank)?.let { language ->
                    rememberOriginalLanguageForArtist(mediaId, language)
                }
                host.applyPlaybackMetadataOverride(
                    mediaId = mediaId,
                    alias = alias,
                    forceInAppRebind = !preBind || !bindingPhase,
                    rememberLocalizedArtist = false,
                    originalMetadata = true,
                    originalMetadataConfirmed = true,
                )
                if (BuildConfig.DEBUG) {
                    host.logMetadataIdentity(
                        event = "original_cache_resolve_finished",
                        details = "contentId=$mediaId, entityType=$entityType, hit=true, " +
                            "confirmed=true, preBind=$preBind, " +
                            "resolved=${alias.title}/${alias.artist}/${alias.album}",
                    )
                }
            },
        )
        bindingPhase = false
    }

    private fun resolveOriginalSongForInApp(
        mediaId: String,
        account: AccountMetadata,
        preBind: Boolean,
        priority: RequestPriority,
    ) {
        val requestKey = "original:SONG:$mediaId"
        if (!metadataStore.beginOriginalRequest(requestKey)) return
        val metadata = MediaMetadataCache.Metadata(
            id = mediaId,
            title = account.title,
            artist = account.artist,
            genre = null,
            duration = 0L,
            queueId = 0L,
        )
        ProviderLogger.info(
            "Apple App 原地区歌曲解析开始: id=$mediaId, " +
                "title=${account.title}, artist=${account.artist}, preBind=$preBind"
        )
        var bindingPhase = true
        catalogResolver.resolveOriginalMetadata(
            metadata = metadata,
            priority = priority,
            onCandidate = candidate@{ candidate ->
                if (!host.isRestoreOriginalEnabled()) return@candidate
                val safeCandidate = validatedOriginalSongAlias(
                    alias = candidate,
                    localizedTitle = account.title,
                    localizedArtist = account.artist,
                ) ?: return@candidate
                host.applyPlaybackMetadataOverride(
                    mediaId = mediaId,
                    alias = safeCandidate,
                    forceInAppRebind = !preBind || !bindingPhase,
                    rememberLocalizedArtist = false,
                )
            },
            onResolved = { resolution ->
                if (!host.isRestoreOriginalEnabled()) {
                    metadataStore.finishOriginalRequest(requestKey)
                    return@resolveOriginalMetadata
                }
                mergePlaybackAssociatedArtistIds(mediaId, resolution.artistIds)
                originalArtistLanguageFromSongResolution(
                    resolution = resolution,
                    localizedArtist = account.artist,
                )?.let { language ->
                    rememberOriginalLanguageForArtist(mediaId, language)
                }
                fun finishResolution(alias: Alias?) {
                    metadataStore.finishOriginalRequest(requestKey)
                    if (!host.isRestoreOriginalEnabled()) return
                    metadataStore.markOriginalResolved(mediaId)
                    metadataStore.clearOriginalPending(mediaId)
                    val safeAlias = validatedOriginalSongAlias(
                        alias = alias,
                        localizedTitle = account.title,
                        localizedArtist = account.artist,
                    )
                    if (alias != null && safeAlias == null) {
                        catalogResolver.invalidateOriginalEntity(
                            mediaId = mediaId,
                            entityType =
                            LocalizedEntityType.SONG,
                        )
                        ProviderLogger.info(
                            "Apple App 原地区合作歌曲别名拒绝: id=$mediaId, " +
                                "account=${account.title}/${account.artist}, " +
                                "candidate=${alias.title}/${alias.artist}"
                        )
                    }
                    safeAlias?.language?.takeIf {
                        shouldShareOriginalSongLanguage(
                            localizedTitle = account.title,
                            localizedArtist = account.artist,
                            alias = safeAlias,
                        )
                    }?.let { language ->
                        rememberOriginalLanguageForArtist(mediaId, language)
                    }
                    if (safeAlias != null) {
                        host.applyPlaybackMetadataOverride(
                            mediaId = mediaId,
                            alias = safeAlias,
                            forceInAppRebind = !preBind || !bindingPhase,
                            rememberLocalizedArtist = false,
                            originalMetadata = true,
                            originalMetadataConfirmed = true,
                        )
                    } else {
                        ensureLocalizedInAppMetadataOverrides(
                            mediaIds = listOf(mediaId),
                            preBind = false,
                            priority = priority,
                        )
                    }
                    if (!metadataStore.associatedArtistIds(mediaId).isNullOrEmpty()) {
                        ensureAssociatedArtistOverride(
                            mediaId = mediaId,
                            preBind = false,
                            priority = priority,
                        )
                    }
                }

                val alias = confirmedOriginalSongAlias(resolution)
                val retryLanguage = originalSongRetryLanguage(resolution)
                if (retryLanguage == null) {
                    finishResolution(alias)
                } else {
                    ProviderLogger.info(
                        "Apple App 原地区歌曲精确重试: id=$mediaId, language=$retryLanguage"
                    )
                    catalogResolver.resolveOriginalEntityForLanguage(
                        mediaId = mediaId,
                        lookupIds = metadataStore.lookupIds(mediaId).orEmpty()
                            .ifEmpty { setOf(mediaId) },
                        entityType = LocalizedEntityType.SONG,
                        language = retryLanguage,
                        priority = priority,
                        onResolved = ::finishResolution,
                    )
                }
            },
        )
        bindingPhase = false
    }

    private fun resolveOriginalEntityForInApp(
        mediaId: String,
        entityType: LocalizedEntityType,
        language: String,
        preBind: Boolean,
        priority: RequestPriority,
    ) {
        val requestKey = "original:$entityType:$language:$mediaId"
        if (!metadataStore.beginOriginalRequest(requestKey)) return
        var bindingPhase = true
        catalogResolver.resolveOriginalEntityForLanguage(
            mediaId = mediaId,
            lookupIds = metadataStore.lookupIds(mediaId).orEmpty(),
            entityType = entityType,
            language = language,
            priority = priority,
        ) { alias ->
            metadataStore.finishOriginalRequest(requestKey)
            if (!host.isRestoreOriginalEnabled()) return@resolveOriginalEntityForLanguage
            metadataStore.markOriginalResolved(mediaId)
            metadataStore.clearOriginalPending(mediaId)
            if (alias != null) {
                host.applyPlaybackMetadataOverride(
                    mediaId = mediaId,
                    alias = alias,
                    forceInAppRebind = !preBind || !bindingPhase,
                    rememberLocalizedArtist = false,
                    originalMetadata = true,
                    originalMetadataConfirmed = true,
                )
            } else {
                ensureLocalizedInAppMetadataOverrides(
                    mediaIds = listOf(mediaId),
                    preBind = false,
                    priority = priority,
                )
            }
        }
        bindingPhase = false
    }

    fun rememberOriginalLanguageForArtist(mediaId: String, language: String) {
        val artistKeys = originalArtistKeysForMedia(mediaId)
        val regionKeys = persistentOriginalArtistKeys(artistKeys)
        if (regionKeys.isEmpty()) return
        val canonicalLanguage = supportedOriginalLanguageOrNull(language)
        if (canonicalLanguage == null) {
            ProviderLogger.info(
                "Apple 艺人原地区语言忽略: id=$mediaId, language=$language, " +
                    "reason=unsupported_language"
            )
            return
        }
        val changed = regionKeys.any { key ->
            metadataStore.originalLanguage(key) != canonicalLanguage
        }
        if (!changed) return
        regionKeys.forEach { key ->
            metadataStore.rememberOriginalLanguage(key, canonicalLanguage)
        }
        catalogResolver.rememberOriginalArtistRegion(
            regionKeys,
            canonicalLanguage,
        )
        val readyIds = metadataStore.pendingOriginalIds().filter { pendingId ->
            metadataStore.artistKeys(pendingId).orEmpty().any { key ->
                key in regionKeys
            }
        }
        val associatedMediaIds = regionKeys.asSequence()
            .flatMap { key -> metadataStore.associatedMediaIds(key).orEmpty().asSequence() }
            .distinct()
            .toList()
        if (readyIds.isNotEmpty() || associatedMediaIds.isNotEmpty()) {
            runtime.mainHandler.post {
                readyIds.forEach(metadataStore::clearOriginalPending)
                if (readyIds.isNotEmpty()) {
                    ensureOriginalInAppMetadataOverrides(readyIds, preBind = false)
                }
                associatedMediaIds.forEach { candidateId ->
                    ensureAssociatedArtistOverride(candidateId, preBind = false)
                }
            }
        }
    }

    fun shouldShareOriginalSongLanguage(
        localizedTitle: String?,
        localizedArtist: String?,
        alias: Alias?,
    ): Boolean {
        alias ?: return false
        val artist = localizedArtist.orEmpty()
        if (isCollaborationArtistName(artist)) return false
        return isConfidentOriginalSongAlias(
            alias = alias,
            localizedTitle = localizedTitle.orEmpty(),
            localizedArtist = artist,
        )
    }

    internal fun originalLanguageFor(mediaId: String): String? {
        val artistKeys = persistentOriginalArtistKeys(originalArtistKeysForMedia(mediaId))
        artistKeys.forEach { key ->
            val cached = metadataStore.originalLanguage(key) ?: return@forEach
            val supported = supportedOriginalLanguageOrNull(cached)
            if (supported != null) return supported
            metadataStore.removeOriginalLanguage(key, cached)
        }
        val restored = catalogResolver.cachedOriginalArtistRegion(
            persistentOriginalArtistKeys(artistKeys)
        ) ?: return null
        artistKeys.forEach { key -> metadataStore.rememberOriginalLanguage(key, restored) }
        return restored
    }

    private fun originalArtistKeysForMedia(mediaId: String): Set<String> = buildSet {
        addAll(metadataStore.artistKeys(mediaId).orEmpty())
        metadataStore.associatedArtistIds(mediaId).orEmpty().forEach { artistId ->
            add("id:$artistId")
        }
    }

    private fun persistentOriginalArtistKeys(keys: Collection<String>): Set<String> =
        stableArtistCacheKeys(keys)

    private fun ensureLocalizedInAppMetadataOverrides(
        mediaIds: Collection<String>,
        preBind: Boolean = false,
        priority: RequestPriority =
            RequestPriority.BACKGROUND,
    ): Set<String> {
        val selection = host.configuredContentUiLanguage()
        if (!host.shouldOverrideAccountLanguage(selection)) {
            return emptySet()
        }
        val awaitingIds = linkedSetOf<String>()
        val requests = mediaIds.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it.all(Char::isDigit) }
            .distinct()
            .filterNot(metadataStore::hasConfiguredMetadata)
            .filterNot(metadataStore::isNonCatalogContent)
            .mapNotNull { mediaId ->
                val lookupIds = metadataStore.lookupIds(mediaId).orEmpty()
                    .ifEmpty { setOf(mediaId) }
                    .sorted()
                val entityType = metadataStore.entityType(mediaId)
                    ?: LocalizedEntityType.SONG
                val requestKey =
                    "$selection:$entityType:$mediaId:${lookupIds.joinToString(",")}".trim()
                if (metadataStore.isConfiguredMiss(requestKey)) return@mapNotNull null
                awaitingIds += mediaId
                if (!metadataStore.beginConfiguredRequest(requestKey)) {
                    return@mapNotNull null
                }
                PendingMetadataLookup(
                    requestKey = requestKey,
                    lookup = LocalizedLookup(
                        mediaId = mediaId,
                        lookupIds = lookupIds,
                        entityType = entityType,
                    ),
                )
            }
            .toList()
        if (requests.isEmpty()) return awaitingIds
        val byMediaId = requests.associateBy { it.lookup.mediaId }
        ProviderLogger.info(
            "Apple 地区元数据批量解析开始: count=${requests.size}, " +
                "selection=$selection, preBind=$preBind, priority=$priority"
        )
        var bindingPhase = true
        catalogResolver.resolveManyForContentUiLanguage(
            lookups = requests.map(PendingMetadataLookup::lookup),
            selection = selection,
            priority = priority,
        ) { mediaId, alias ->
            val request = byMediaId[mediaId] ?: return@resolveManyForContentUiLanguage
            val lookupIds = request.lookup.lookupIds
            val entityType = request.lookup.entityType
            metadataStore.finishConfiguredRequest(request.requestKey)
            host.logMetadataIdentity(
                event = "catalog_resolve_finished",
                details = "requestedId=$mediaId, lookupIds=$lookupIds, " +
                    "entityType=$entityType, selection=$selection, hit=${alias != null}, " +
                    "resolved=${alias?.title}/${alias?.artist}/${alias?.album}",
            )
            if (
                alias != null &&
                host.configuredContentUiLanguage() == selection &&
                host.shouldOverrideAccountLanguage(selection)
            ) {
                host.applyPlaybackMetadataOverride(
                    mediaId = mediaId,
                    alias = alias,
                    forceInAppRebind = !preBind || !bindingPhase,
                )
            } else if (alias == null) {
                metadataStore.markConfiguredMiss(request.requestKey)
            }
            if (host.isRestoreOriginalEnabled()) {
                ensureOriginalInAppMetadataOverrides(
                    mediaIds = listOf(mediaId),
                    preBind = false,
                    priority = priority,
                )
            }
        }
        bindingPhase = false
        return awaitingIds
    }


    fun clearDeferredResolutions() {
        synchronized(deferredMetadataResolutions) {
            deferredMetadataResolutions.clear()
            deferredMetadataResolutionScheduled = false
        }
    }

    fun shouldRequestOverride(mediaId: String): Boolean {
        val associatedArtistIds = metadataStore.associatedArtistIds(mediaId).orEmpty()
        return shouldRequestEffectiveMetadataResolution(
            restoreOriginalEnabled = host.isRestoreOriginalEnabled(),
            originalMetadataResolved = metadataStore.isOriginalResolved(mediaId) &&
                !shouldRetryOriginalMetadataCacheProbe(mediaId),
            hasOriginalMetadata = metadataStore.hasOriginalMetadata(mediaId),
            hasAssociatedArtists = shouldUseAssociatedArtistEntities(
                artistIds = associatedArtistIds,
                artistCredit = associatedArtistCredit(mediaId),
            ),
            originalArtistResolved = metadataStore.isOriginalArtistResolved(mediaId),
            hasLocalizedMetadata = metadataStore.hasConfiguredMetadata(mediaId),
        )
    }

    private fun shouldRetryOriginalMetadataCacheProbe(mediaId: String): Boolean =
        io.github.proify.lyricon.amprovider.xposed.shouldRetryOriginalMetadataCacheProbe(
            originalResolved = metadataStore.isOriginalResolved(mediaId),
            lastMissUptimeMillis = metadataStore.originalCacheMissUptimeMillis(mediaId),
            nowUptimeMillis = SystemClock.uptimeMillis(),
        )
}
