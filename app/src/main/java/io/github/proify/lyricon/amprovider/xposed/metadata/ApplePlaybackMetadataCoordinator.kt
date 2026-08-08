/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

internal interface ApplePlaybackMetadataCoordinatorHost {
    fun activePlayer(): Any?

    fun configuredContentUiLanguage(): Int

    fun shouldOverrideAccountLanguage(selection: Int): Boolean

    fun shouldRestoreCjkOriginalMetadata(metadata: MediaMetadataCache.Metadata): Boolean

    fun ensureContentItemMetadataHooks(contentItemClass: Class<*>)

    fun setMetadataPlaybackMediaId(mediaId: String)

    fun effectiveMetadataAlias(mediaId: String): AppleInternalCatalogResolver.Alias?

    fun applyPlaybackMetadataOverride(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        rememberLocalizedArtist: Boolean = true,
        originalMetadata: Boolean = false,
        originalMetadataConfirmed: Boolean = false,
    )

    fun logMetadataIdentity(event: String, details: String)

    fun validatedOriginalSongAlias(
        alias: AppleInternalCatalogResolver.Alias?,
        localizedTitle: String?,
        localizedArtist: String?,
    ): AppleInternalCatalogResolver.Alias?

    fun shouldShareOriginalSongLanguage(
        localizedTitle: String?,
        localizedArtist: String?,
        alias: AppleInternalCatalogResolver.Alias?,
    ): Boolean

    fun rememberOriginalLanguageForArtist(mediaId: String, language: String)

    fun isRestoreOriginalMetadataEnabled(): Boolean
}

/**
 * Owns the current Apple Music queue identity and the configured/original catalog resolution
 * lifecycle. UI model mutation remains in the host so this component cannot bypass surface,
 * generation, or visibility guards.
 */
internal class ApplePlaybackMetadataCoordinator(
    private val hookResolver: AppleMusicHookResolver,
    private val catalogResolver: AppleInternalCatalogResolver,
    private val metadataStore: AppleMetadataOverrideStore,
    private val host: ApplePlaybackMetadataCoordinatorHost,
) {
    private val playbackTarget by lazy {
        hookResolver.resolveClass(AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_CONTROLLER_STATE).target
    }
    private var currentRefresh: PlaybackMetadataRefresh? = null
    private var currentMediaId: String? = null

    fun currentMetadataId(): String? = currentMediaId

    fun currentRefreshMediaId(): String? = currentRefresh?.mediaId

    fun invokeCurrentRefresh(mediaId: String) {
        currentRefresh
            ?.takeIf { it.mediaId == mediaId }
            ?.refresh
            ?.invoke()
    }

    fun refreshCurrentQueueItemIfActive(mediaPlayer: Any?, source: String) {
        if (!isActivePlaybackCallback(mediaPlayer, host.activePlayer())) {
            ProviderLogger.debug(
                "忽略非活动播放器的歌曲元数据：source=$source, " +
                    "callback=${mediaPlayer?.let(System::identityHashCode)}, " +
                    "active=${host.activePlayer()?.let(System::identityHashCode)}"
            )
            return
        }
        refreshCurrentQueueItem(mediaPlayer, source)
    }

    fun refreshCurrentQueueItem(mediaPlayer: Any?, source: String) {
        if (mediaPlayer == null) {
            ProviderLogger.debug("歌曲元数据刷新失败：$source 的 MediaPlayer 为空")
            return
        }
        runCatching {
            handleQueueItem(currentQueueItem(mediaPlayer), source)
        }.onFailure {
            ProviderLogger.error("歌曲元数据刷新异常：source=$source", it)
        }
    }

    fun handleQueueItem(
        queueItem: Any?,
        source: String,
        publishAsCurrent: Boolean = true,
        refreshPlaybackMetadata: (() -> Unit)? = null,
    ) {
        if (queueItem == null) {
            ProviderLogger.debug("歌曲元数据更新失败：$source 的 PlayerQueueItem 为空")
            return
        }
        runCatching {
            val mediaItem = AppleReflection.call(
                queueItem,
                playbackMember(AppleMusicRuntimeMember.PLAYBACK_QUEUE_ITEM_ITEM_METHOD),
            ) ?: return@runCatching
            val mediaId = mediaItemId(mediaItem) ?: return@runCatching
            val queueAlbum = readAlbumName(mediaItem)
            val aliasAlbum = host.effectiveMetadataAlias(mediaId)
                ?.album
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            val languageSelection = host.configuredContentUiLanguage()
            val overrideAccountLanguage = host.shouldOverrideAccountLanguage(languageSelection)

            val metadata = MediaMetadataCache.Metadata(
                id = mediaId,
                title = AppleReflection.call(
                    mediaItem,
                    playbackMember(AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_TITLE_METHOD),
                ) as? String,
                artist = AppleReflection.call(
                    mediaItem,
                    playbackMember(
                        AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_ARTIST_NAME_METHOD
                    ),
                ) as? String,
                genre = AppleReflection.call(
                    mediaItem,
                    playbackMember(AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_GENRE_NAME_METHOD),
                ) as? String,
                album = queueAlbum ?: aliasAlbum,
                duration = AppleReflection.call(
                    mediaItem,
                    playbackMember(AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_DURATION_METHOD),
                ) as? Long ?: 0L,
                queueId = AppleReflection.call(
                    queueItem,
                    playbackMember(AppleMusicRuntimeMember.PLAYBACK_QUEUE_ITEM_ID_METHOD),
                ) as? Long ?: 0L
            )
            val restoreCjkOriginalMetadata = host.shouldRestoreCjkOriginalMetadata(metadata)
            if (overrideAccountLanguage || restoreCjkOriginalMetadata) {
                host.ensureContentItemMetadataHooks(mediaItem.javaClass)
            }
            val previousMetadata = MediaMetadataCache.getMetadataById(mediaId)
            MediaMetadataCache.put(metadata)
            ProviderLogger.debug(
                "歌曲元数据已更新：source=$source, id=${metadata.id}, " +
                    "queueId=${metadata.queueId}, 标题=${metadata.title}, " +
                    "专辑=${metadata.album}, 专辑来源=" +
                    if (queueAlbum != null) "queue" else if (aliasAlbum != null) "alias" else "none"
            )
            if (publishAsCurrent) {
                val previousCurrentId = currentMediaId
                currentMediaId = mediaId
                host.setMetadataPlaybackMediaId(mediaId)
                metadataStore.updateCurrentPlaybackOverride(host.effectiveMetadataAlias(mediaId))
                refreshPlaybackMetadata?.let { callback ->
                    currentRefresh = PlaybackMetadataRefresh(mediaId, callback)
                }
                PlaybackManager.onSongChanged(metadata.id)
                host.logMetadataIdentity(
                    event = "queue_current_published",
                    details = "trigger=$source, previousId=$previousCurrentId, " +
                        "publishedId=$mediaId, title=${metadata.title}, artist=${metadata.artist}, " +
                        "queueId=${metadata.queueId}, overrideEnabled=$overrideAccountLanguage",
                )
                if (
                    previousMetadata != null &&
                    (previousMetadata.title != metadata.title ||
                        previousMetadata.artist != metadata.artist)
                ) {
                    PlaybackManager.onCatalogMetadataResolved(metadata.id)
                }
                resolveCatalogMetadata(
                    metadata = metadata,
                    languageSelection = languageSelection,
                    overrideAccountLanguage = overrideAccountLanguage,
                    restoreCjkOriginalMetadata = restoreCjkOriginalMetadata,
                )
            }
        }.onFailure {
            ProviderLogger.error("歌曲元数据解析异常：source=$source", it)
        }
    }

    /** Queue media items differ across Apple Music builds; probe the stable album accessors. */
    private fun readAlbumName(mediaItem: Any): String? = listOf(
        "getAlbumName",
        "getAlbum",
        "getCollectionName",
    ).firstNotNullOfOrNull { methodName ->
        runCatching {
            AppleReflection.call(mediaItem, methodName) as? String
        }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)
    }

    fun resolveOriginalMetadataOnDemand(mediaId: String) {
        val metadata = MediaMetadataCache.getMetadataById(mediaId)
        if (metadata == null) {
            ProviderLogger.info("Apple 原名按需查询忽略: id=$mediaId, reason=metadata_missing")
            return
        }
        if (
            metadata.originalMetadataResolved ||
            !metadata.originalTitle.isNullOrBlank() ||
            !metadata.originalArtist.isNullOrBlank()
        ) {
            PlaybackManager.onCatalogMetadataResolved(mediaId)
            return
        }
        resolveOriginalMetadata(
            metadata = metadata,
            applyToPlayback = host.shouldRestoreCjkOriginalMetadata(metadata),
            reason = "online_source_requested",
        )
    }

    fun isCurrentQueueItem(candidate: Any?, current: Any?): Boolean {
        if (candidate == null || current == null) return false
        if (candidate === current) return true
        val queueIdMethod = playbackMember(AppleMusicRuntimeMember.PLAYBACK_QUEUE_ITEM_ID_METHOD)
        val candidateQueueId = AppleReflection.call(candidate, queueIdMethod) as? Long ?: 0L
        val currentQueueId = AppleReflection.call(current, queueIdMethod) as? Long ?: 0L
        if (candidateQueueId > 0L && currentQueueId > 0L) {
            return candidateQueueId == currentQueueId
        }
        val candidateMediaId = queueItemMediaId(candidate)
        val currentMediaId = queueItemMediaId(current)
        return candidateMediaId != null && candidateMediaId == currentMediaId
    }

    fun queueItemMediaId(queueItem: Any): String? {
        val mediaItem = AppleReflection.call(
            queueItem,
            playbackMember(AppleMusicRuntimeMember.PLAYBACK_QUEUE_ITEM_ITEM_METHOD),
        ) ?: return null
        return mediaItemId(mediaItem)
    }

    fun currentPlaybackQueueMediaId(): String? {
        val currentQueueItem = host.activePlayer()?.let { player ->
            runCatching { currentQueueItem(player) }.getOrNull()
        }
        return currentQueueItem
            ?.let { queueItem -> runCatching { queueItemMediaId(queueItem) }.getOrNull() }
            ?: currentMediaId
    }

    private fun resolveCatalogMetadata(
        metadata: MediaMetadataCache.Metadata,
        languageSelection: Int,
        overrideAccountLanguage: Boolean,
        restoreCjkOriginalMetadata: Boolean,
    ) {
        val resolutionPlan = AppleMetadataResolutionEngine.catalogMetadataResolutionPlan(
            overrideAccountLanguage = overrideAccountLanguage,
            restoreCjkOriginalMetadata = restoreCjkOriginalMetadata,
        )
        if (resolutionPlan.resolveConfiguredRegion) {
            resolveConfiguredCatalogMetadata(metadata, languageSelection)
        }

        if (resolutionPlan.resolveOriginalRegion) {
            if (metadata.originalMetadataResolved) {
                val cachedAlias = cachedOriginalMetadataAlias(metadata)
                if (cachedAlias != null) {
                    host.applyPlaybackMetadataOverride(
                        mediaId = metadata.id,
                        alias = cachedAlias,
                        rememberLocalizedArtist = false,
                        originalMetadata = true,
                        originalMetadataConfirmed = true,
                    )
                } else {
                    PlaybackManager.onCatalogMetadataResolved(metadata.id)
                }
                return
            }
            resolveOriginalMetadata(
                metadata = metadata,
                applyToPlayback = true,
                reason = "setting_enabled",
            )
            return
        }
        if (!resolutionPlan.resolveConfiguredRegion) {
            host.logMetadataIdentity(
                event = "current_catalog_resolve_skipped",
                details = "requestedId=${metadata.id}, selection=$languageSelection, reason=disabled",
            )
        }
    }

    private fun resolveConfiguredCatalogMetadata(
        metadata: MediaMetadataCache.Metadata,
        languageSelection: Int,
    ) {
        host.logMetadataIdentity(
            event = "current_catalog_resolve_started",
            details = "requestedId=${metadata.id}, selection=$languageSelection, " +
                "title=${metadata.title}, artist=${metadata.artist}",
        )

        catalogResolver.resolveForContentUiLanguage(
            mediaId = metadata.id,
            selection = languageSelection,
        ) { alias ->
            host.logMetadataIdentity(
                event = "current_catalog_resolve_finished",
                details = "requestedId=${metadata.id}, selection=$languageSelection, " +
                    "hit=${alias != null}, resolved=${alias?.title}/${alias?.artist}/${alias?.album}",
            )
            if (
                alias != null &&
                host.shouldOverrideAccountLanguage(languageSelection) &&
                host.configuredContentUiLanguage() == languageSelection
            ) {
                host.applyPlaybackMetadataOverride(metadata.id, alias)
            }
        }
    }

    private fun resolveOriginalMetadata(
        metadata: MediaMetadataCache.Metadata,
        applyToPlayback: Boolean,
        reason: String,
    ) {
        ProviderLogger.info(
            "Apple 原名查询开始: id=${metadata.id}, title=${metadata.title}, " +
                "artist=${metadata.artist}, reason=$reason"
        )
        catalogResolver.resolveOriginalMetadata(
            metadata = metadata,
            onCandidate = candidate@{ candidate ->
                if (!applyToPlayback ||
                    !host.isRestoreOriginalMetadataEnabled() ||
                    currentMediaId != metadata.id
                ) return@candidate
                val safeCandidate = host.validatedOriginalSongAlias(
                    alias = candidate,
                    localizedTitle = metadata.title,
                    localizedArtist = metadata.artist,
                ) ?: return@candidate
                host.applyPlaybackMetadataOverride(
                    mediaId = metadata.id,
                    alias = safeCandidate,
                    rememberLocalizedArtist = false,
                )
            },
            onResolved = { resolution ->
                val alias = host.validatedOriginalSongAlias(
                    alias = resolution.alias,
                    localizedTitle = metadata.title,
                    localizedArtist = metadata.artist,
                )
                metadataStore.markOriginalResolved(metadata.id)
                resolution.language?.takeIf {
                    host.shouldShareOriginalSongLanguage(
                        localizedTitle = metadata.title,
                        localizedArtist = metadata.artist,
                        alias = alias,
                    )
                }?.let { language ->
                    host.rememberOriginalLanguageForArtist(metadata.id, language)
                }
                MediaMetadataCache.updateOriginalMetadata(
                    mediaId = metadata.id,
                    title = alias?.title,
                    artist = alias?.artist,
                    album = alias?.album,
                    resolved = true,
                )
                if (alias == null) {
                    ProviderLogger.info(
                        "Apple 原名查询未命中: id=${metadata.id}, reason=$reason"
                    )
                    metadataStore.configuredMetadata(metadata.id)?.let { localizedAlias ->
                        host.applyPlaybackMetadataOverride(metadata.id, localizedAlias)
                    } ?: PlaybackManager.onCatalogMetadataResolved(metadata.id)
                    return@resolveOriginalMetadata
                }
                if (
                    applyToPlayback &&
                    host.isRestoreOriginalMetadataEnabled() &&
                    currentMediaId == metadata.id
                ) {
                    host.applyPlaybackMetadataOverride(
                        mediaId = metadata.id,
                        alias = alias,
                        rememberLocalizedArtist = false,
                        originalMetadata = true,
                        originalMetadataConfirmed = true,
                    )
                } else {
                    PlaybackManager.onCatalogMetadataResolved(metadata.id)
                }
            },
        )
    }

    private fun cachedOriginalMetadataAlias(
        metadata: MediaMetadataCache.Metadata,
    ): AppleInternalCatalogResolver.Alias? {
        val title = metadata.originalTitle?.takeIf(String::isNotBlank)
        val artist = metadata.originalArtist?.takeIf(String::isNotBlank)
        if (title == null && artist == null) return null
        return AppleInternalCatalogResolver.Alias(
            title = title ?: metadata.title.orEmpty(),
            artist = artist ?: metadata.artist.orEmpty(),
            language = "original",
        )
    }

    private fun mediaItemId(mediaItem: Any): String? {
        val subscriptionStoreId =
            AppleReflection.call(
                mediaItem,
                playbackMember(
                    AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_SUBSCRIPTION_STORE_ID_METHOD
                ),
            ) as? String
        if (!subscriptionStoreId.isNullOrBlank()) return subscriptionStoreId
        val persistentId = AppleReflection.call(
            mediaItem,
            playbackMember(AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_PERSISTENT_ID_METHOD),
        ) as? Long ?: 0L
        return persistentId.takeIf { it > 0L }?.toString()
    }

    fun currentQueueItem(mediaPlayer: Any): Any? = AppleReflection.call(
        mediaPlayer,
        playbackMember(AppleMusicRuntimeMember.PLAYBACK_PLAYER_CURRENT_ITEM_METHOD),
    )

    private fun playbackMember(member: AppleMusicRuntimeMember): String =
        playbackTarget.runtimeMemberName(member)
}
