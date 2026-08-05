/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.BuildConfig
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/** Owns captured-model mutation, restoration, and exact in-app metadata consumer refresh. */
internal class AppleInAppMetadataApplier(
    private val runtime: AppleMusicProviderRuntime,
    private val metadataStore: AppleMetadataOverrideStore,
    private val registry: AppleInAppMetadataRegistry,
    private val contentItemMetadataHooks: AppleContentItemMetadataHooks,
    private val librarySurfaceHooks: AppleLibrarySurfaceHooks,
    private val collectionSurfaceHooks: AppleCollectionSurfaceHooks,
    private val artistSurfaceHooks: AppleArtistSurfaceHooks,
    private val dataBindingHooks: AppleDataBindingMetadataHooks,
    private val listenNowHooks: AppleListenNowHooks,
    private val queueMetadataHooks: AppleQueueMetadataHooks,
    private val traceSequence: AtomicLong,
    private val logMetadataIdentity: (event: String, details: String) -> Unit,
) {
    private val callbackAppliedAliases =
        Collections.synchronizedMap(WeakHashMap<Any, AppliedMetadataAlias>())
    private val metadataTarget = runtime.hookResolver.resolveMethod(
        AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT,
    ).target
    private val contentItemTarget by lazy {
        runtime.hookResolver.resolveClasses(AppleMusicHookPoint.CONTENT_ITEM_METADATA_CLASSES)
            .first { resolved ->
                resolved.target.runtimeMemberNameOrNull(AppleMusicRuntimeMember.CONTENT_ITEM_ROLE) ==
                    "base"
            }
    }
    private val artistContainerTarget by lazy {
        runtime.hookResolver.resolveClass(AppleMusicHookPoint.IN_APP_CONTAINER_ARTIST_CLASS).target
    }
    private val albumContainerTarget by lazy {
        runtime.hookResolver.resolveClass(AppleMusicHookPoint.IN_APP_CONTAINER_ALBUM_CLASS).target
    }

    fun clearCallbackState() {
        callbackAppliedAliases.clear()
    }

    fun applyAliasToMetadata(
        metadata: Any,
        alias: AppleInternalCatalogResolver.Alias,
    ) {
        setMetadataField(
            metadata = metadata,
            runtimeMember = AppleMusicRuntimeMember.MEDIA3_METADATA_TITLE_FIELD,
            value = alias.title,
        )
        setMetadataField(
            metadata = metadata,
            runtimeMember = AppleMusicRuntimeMember.MEDIA3_METADATA_ARTIST_FIELD,
            value = alias.artist,
        )
    }

    fun applyAliasToMetadataRefs(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        forceRebind: Boolean = true,
        notifyModelChange: Boolean = true,
    ) {
        var metadataApplied = 0
        registry.liveMetadataRefs(mediaId).forEach { ref ->
            ref.metadata.get()?.let { metadata ->
                applyAliasToMetadata(metadata, alias)
                metadataApplied += 1
            }
        }
        var playbackItemApplied = 0
        registry.livePlaybackItemRefs(mediaId).forEach { ref ->
            ref.playbackItem.get()?.let { playbackItem ->
                applyAliasToPlaybackItem(playbackItem, alias, notifyModelChange)
                playbackItemApplied += 1
            }
        }
        var containerItemApplied = 0
        registry.liveContainerItemRefs(mediaId).forEach { ref ->
            ref.containerItem.get()?.let { containerItem ->
                applyAliasToContainerItem(containerItem, ref.kind, alias, notifyModelChange)
                containerItemApplied += 1
            }
        }
        val libraryEntitiesApplied = librarySurfaceHooks.applyAliasToEntityRefs(mediaId, alias)
        val playlistRowsApplied =
            if (forceRebind) collectionSurfaceHooks.refreshPlaylistRowRefs(mediaId, alias) else 0
        val libraryControllers = if (forceRebind) {
            librarySurfaceHooks.refreshControllers(
                mediaId = mediaId,
                alias = alias,
                hasDirectPlaylistRow = playlistRowsApplied > 0,
            )
        } else {
            0
        }
        val libraryComposeStates =
            if (forceRebind) librarySurfaceHooks.refreshComposeStates(mediaId, alias) else 0
        val dataBindingTargets =
            if (forceRebind) dataBindingHooks.refreshDataBindings(mediaId, alias) else 0
        val listenNowDataBindingTargets =
            if (forceRebind) listenNowHooks.refreshDataBindings(mediaId, alias) else 0
        val queueAdapterTargets =
            if (forceRebind) queueMetadataHooks.refreshAdapters(mediaId) else 0
        val genericRecyclerTargets =
            if (forceRebind) dataBindingHooks.refreshGenericRecyclerItems(mediaId) else 0
        if (
            metadataApplied + playbackItemApplied + containerItemApplied +
            libraryEntitiesApplied + playlistRowsApplied + libraryControllers +
            libraryComposeStates + dataBindingTargets + listenNowDataBindingTargets +
            queueAdapterTargets + genericRecyclerTargets > 0
        ) {
            ProviderLogger.info(
                "Apple Music App 内元数据已覆盖: id=$mediaId, " +
                    "title=${alias.title}, artist=${alias.artist}, album=${alias.album}, " +
                    "metadata=$metadataApplied, items=$playbackItemApplied, " +
                    "containers=$containerItemApplied, libraryEntities=$libraryEntitiesApplied, " +
                    "playlistRows=$playlistRowsApplied, libraryControllers=$libraryControllers, " +
                    "libraryComposeStates=$libraryComposeStates, " +
                    "dataBindings=$dataBindingTargets, " +
                    "listenNowDataBindings=$listenNowDataBindingTargets, " +
                    "queueAdapters=$queueAdapterTargets, " +
                    "genericRecyclerItems=$genericRecyclerTargets"
            )
        }
    }

    fun hasLiveModelTarget(mediaId: String): Boolean =
        registry.hasLiveModelTarget(mediaId) ||
            librarySurfaceHooks.hasEntityRefs(mediaId) ||
            librarySurfaceHooks.hasControllerRefs(mediaId) ||
            librarySurfaceHooks.hasComposeStateRefs(mediaId) ||
            dataBindingHooks.hasRefs(mediaId) ||
            dataBindingHooks.hasGenericRecyclerRefs(mediaId) ||
            queueMetadataHooks.hasCapturedMediaId(mediaId) && queueMetadataHooks.hasLiveAdapter()

    fun requestLibraryControllerBuild(
        controller: Any,
        strategy: InAppLibraryControllerBuildStrategy,
    ) {
        if (collectionSurfaceHooks.requestControllerBuild(controller, strategy)) return
        when (strategy) {
            InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA,
            InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD ->
                error("Collection controller build state unavailable: $strategy")

            InAppLibraryControllerBuildStrategy.ARTIST_SET_DATA -> {
                check(artistSurfaceHooks.requestControllerBuild(controller)) {
                    "Artist controller build state unavailable"
                }
            }

            InAppLibraryControllerBuildStrategy.GENERIC_REQUEST_MODEL_BUILD ->
                AppleReflection.call(controller, "requestModelBuild")
        }
    }

    fun dataBindingAliasValues(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        binding: Any?,
    ): DataBindingAliasValues {
        val entityType = metadataStore.entityType(mediaId)
            ?: AppleInternalCatalogResolver.LocalizedEntityType.SONG
        val title = contentItemMetadataOverride(
            entityType,
            AppleContentItemGetter.TITLE,
            alias,
            null,
        )
        val defaultSubtitle = contentItemMetadataOverride(
            entityType,
            AppleContentItemGetter.SUBTITLE,
            alias,
            null,
        )
        val subtitle = artistSurfaceHooks.subtitleForBinding(
            binding = binding,
            defaultSubtitle = defaultSubtitle,
            replacementArtist = alias.artist,
        )
        if (BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 元数据链路: seq=${traceSequence.incrementAndGet()}, " +
                    "event=data_binding_values, contentId=$mediaId, " +
                    "entityType=$entityType, alias=${alias.title}/${alias.artist}/${alias.album}, " +
                    "title=$title, subtitle=$subtitle"
            )
        }
        return DataBindingAliasValues(title = title, subtitle = subtitle)
    }

    fun applyAliasToContainerItem(
        containerItem: Any,
        kind: InAppContainerKind,
        alias: AppleInternalCatalogResolver.Alias,
        notifyChange: Boolean = true,
    ) {
        val title = when (kind) {
            InAppContainerKind.ARTIST -> alias.artist
            InAppContainerKind.ALBUM -> alias.album
        }.takeIf(String::isNotBlank) ?: return
        val changed = runCatching {
            AppleReflection.call(
                containerItem,
                containerTarget(kind).runtimeMemberName(
                    AppleMusicRuntimeMember.IN_APP_CONTAINER_SET_TITLE_METHOD,
                ),
                title,
            )
            true
        }.getOrDefault(false)
        if (changed && notifyChange) {
            runCatching {
                AppleReflection.call(
                    containerItem,
                    containerTarget(kind).runtimeMemberName(
                        AppleMusicRuntimeMember.IN_APP_CONTAINER_NOTIFY_CHANGE_METHOD,
                    ),
                )
            }
                .onFailure {
                    ProviderLogger.error(
                        "Apple Music App 容器跳转项变更通知失败: " +
                            "class=${containerItem.javaClass.name}, kind=$kind",
                        it,
                    )
                }
        }
    }

    fun applyAliasToPlaybackItem(
        playbackItem: Any,
        alias: AppleInternalCatalogResolver.Alias,
        notifyChange: Boolean = true,
    ) {
        val entityType = localizedEntityType(playbackItem) ?: return
        val contract = registry.playbackItemContract(playbackItem)
        var changed = false
        listOf(
            Triple(InAppPlaybackItemField.TITLE, AppleContentItemGetter.TITLE, alias.title),
            Triple(InAppPlaybackItemField.ARTIST, AppleContentItemGetter.ARTIST, alias.artist),
            Triple(InAppPlaybackItemField.ALBUM, AppleContentItemGetter.COLLECTION, alias.album),
        ).forEach { (field, getter, _) ->
            contentItemMetadataOverride(entityType, getter, alias, null)
                ?.takeIf(String::isNotBlank)
                ?.let { value ->
                    if (readPlaybackItemValue(playbackItem, field, contract) != value) {
                        changed = writePlaybackItemValue(
                            playbackItem,
                            field,
                            value,
                            contract,
                        ) || changed
                    }
                }
        }
        if (changed && notifyChange && contract == InAppPlaybackItemContract.STANDARD) {
            notifyPlaybackItemChanged(playbackItem, "变更")
        }
    }

    fun restoreCapturedModels() {
        registry.allLiveMetadataRefs().forEach { ref ->
            ref.metadata.get()?.let { metadata ->
                AppleReflection.setField(
                    metadata,
                    member(AppleMusicRuntimeMember.MEDIA3_METADATA_TITLE_FIELD),
                    ref.originalTitle,
                )
                AppleReflection.setField(
                    metadata,
                    member(AppleMusicRuntimeMember.MEDIA3_METADATA_ARTIST_FIELD),
                    ref.originalArtist,
                )
            }
        }
        registry.allLivePlaybackItemRefs().values.flatten().forEach { ref ->
            ref.playbackItem.get()?.let { playbackItem ->
                writePlaybackItemValue(
                    playbackItem,
                    InAppPlaybackItemField.TITLE,
                    ref.originalTitle?.toString(),
                    ref.contract,
                )
                writePlaybackItemValue(
                    playbackItem,
                    InAppPlaybackItemField.ARTIST,
                    ref.originalArtist?.toString(),
                    ref.contract,
                )
                writePlaybackItemValue(
                    playbackItem,
                    InAppPlaybackItemField.ALBUM,
                    ref.originalCollectionName,
                    ref.contract,
                )
                if (ref.contract == InAppPlaybackItemContract.STANDARD) {
                    notifyPlaybackItemChanged(playbackItem, "恢复")
                }
            }
        }
        librarySurfaceHooks.restoreOriginalEntities().forEach { mediaId ->
            librarySurfaceHooks.refreshControllers(mediaId)
            librarySurfaceHooks.refreshComposeStates(mediaId)
            dataBindingHooks.refreshDataBindings(mediaId)
        }
        registry.allLiveContainerItemRefs().forEach { ref ->
            ref.containerItem.get()?.let { containerItem ->
                runCatching {
                    AppleReflection.call(
                        containerItem,
                        containerTarget(ref.kind).runtimeMemberName(
                            AppleMusicRuntimeMember.IN_APP_CONTAINER_SET_TITLE_METHOD,
                        ),
                        ref.originalTitle,
                    )
                    AppleReflection.call(
                        containerItem,
                        containerTarget(ref.kind).runtimeMemberName(
                            AppleMusicRuntimeMember.IN_APP_CONTAINER_NOTIFY_CHANGE_METHOD,
                        ),
                    )
                }
                    .onFailure {
                        ProviderLogger.error(
                            "Apple Music App 容器跳转项恢复通知失败: " +
                                "class=${containerItem.javaClass.name}, kind=${ref.kind}",
                            it,
                        )
                    }
            }
        }
    }

    fun refreshMetadataCallbacks(
        mediaId: String? = null,
        alias: AppleInternalCatalogResolver.Alias? = null,
    ) {
        val dispatcherRefresh = queueMetadataHooks.currentDispatcherRefresh()
            ?.takeIf { mediaId == null || it.mediaId == mediaId }
        val listenerRefresh = queueMetadataHooks.currentNowPlayingRefresh()
            ?.takeIf { mediaId == null || it.mediaId == mediaId }
        if (dispatcherRefresh == null && listenerRefresh == null) return
        val appliedAlias = mediaId?.let { id -> alias?.let { AppliedMetadataAlias(id, it) } }
        runtime.mainHandler.post {
            var listenerHandled = false
            listenerRefresh?.let { refresh ->
                val listener = refresh.listener.get()
                val metadata = refresh.metadata.get()
                if (listener != null && metadata != null) {
                    if (appliedAlias != null && callbackAppliedAliases[listener] == appliedAlias) {
                        listenerHandled = true
                    } else {
                        runCatching { refresh.method.invoke(listener, metadata) }
                            .onSuccess {
                                listenerHandled = true
                                appliedAlias?.let { callbackAppliedAliases[listener] = it }
                                logMetadataIdentity(
                                    "in_app_now_playing_refresh",
                                    "refreshId=${refresh.mediaId}",
                                )
                            }
                            .onFailure {
                                ProviderLogger.error("Apple Music App 播放页元数据刷新失败", it)
                            }
                    }
                }
            }
            if (!listenerHandled) {
                dispatcherRefresh?.let { refresh ->
                    val dispatcher = refresh.dispatcher.get()
                    val metadata = refresh.metadata.get()
                    if (dispatcher != null && metadata != null &&
                        (appliedAlias == null || callbackAppliedAliases[dispatcher] != appliedAlias)
                    ) {
                        runCatching { refresh.method.invoke(dispatcher, metadata) }
                            .onSuccess {
                                appliedAlias?.let { callbackAppliedAliases[dispatcher] = it }
                                logMetadataIdentity(
                                    "in_app_dispatcher_refresh",
                                    "refreshId=${refresh.mediaId}",
                                )
                            }
                            .onFailure {
                                ProviderLogger.error("Apple Music App 全局元数据刷新失败", it)
                            }
                    }
                }
            }
        }
    }

    private fun localizedEntityType(
        contentItem: Any,
    ): AppleInternalCatalogResolver.LocalizedEntityType? = localizedEntityTypeForQueueItem(
        historyEntry = registry.playbackItemContract(contentItem) ==
            InAppPlaybackItemContract.HISTORY,
        classNames = generateSequence(contentItem.javaClass as Class<*>?) { it.superclass }
            .map { it.simpleName }
            .toList(),
    )

    private fun readPlaybackItemValue(
        playbackItem: Any,
        field: InAppPlaybackItemField,
        contract: InAppPlaybackItemContract,
    ): String? {
        val access = inAppPlaybackItemAccess(contract, field) ?: return null
        val value = if (access.readViaMethod) {
            runCatching {
                contentItemMetadataHooks.withOriginalGetters {
                    AppleReflection.call(
                        playbackItem,
                        contentItemTarget.target.runtimeMemberName(access.readMember),
                    )
                }
            }.getOrNull()
        } else {
            runCatching {
                AppleReflection.field(
                    playbackItem,
                    contentItemTarget.target.runtimeMemberName(access.readMember),
                )
            }.getOrNull()
        }
        return value?.toString()
    }

    private fun writePlaybackItemValue(
        playbackItem: Any,
        field: InAppPlaybackItemField,
        value: String?,
        contract: InAppPlaybackItemContract,
    ): Boolean {
        val setter = inAppPlaybackItemAccess(contract, field)?.setter ?: return false
        return runCatching {
            AppleReflection.call(
                playbackItem,
                contentItemTarget.target.runtimeMemberName(setter),
                value,
            )
        }.isSuccess
    }

    private fun notifyPlaybackItemChanged(playbackItem: Any, operation: String) {
        runCatching {
            AppleReflection.call(
                playbackItem,
                contentItemTarget.target.runtimeMemberName(
                    AppleMusicRuntimeMember.CONTENT_ITEM_NOTIFY_CHANGE_METHOD,
                ),
            )
        }
            .onFailure {
                ProviderLogger.error(
                    "Apple Music App PlaybackItem $operation 通知失败: " +
                        "class=${playbackItem.javaClass.name}",
                    it,
                )
            }
    }

    private fun setMetadataField(
        metadata: Any,
        runtimeMember: AppleMusicRuntimeMember,
        value: String,
    ) {
        value.takeIf(String::isNotBlank)?.let { replacement ->
            val fieldName = member(runtimeMember)
            val current = runCatching { AppleReflection.field(metadata, fieldName) }
                .getOrNull()?.toString()
            if (current != replacement) AppleReflection.setField(metadata, fieldName, replacement)
        }
    }

    private fun member(runtimeMember: AppleMusicRuntimeMember): String =
        metadataTarget.runtimeMemberName(runtimeMember)

    private fun containerTarget(kind: InAppContainerKind): AppleMusicHookTarget = when (kind) {
        InAppContainerKind.ARTIST -> artistContainerTarget
        InAppContainerKind.ALBUM -> albumContainerTarget
    }
}
