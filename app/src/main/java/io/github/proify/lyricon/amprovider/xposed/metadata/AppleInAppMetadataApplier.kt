/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.os.SystemClock
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
        val traceTargetId = mediaId ?: listenerRefresh?.mediaId ?: dispatcherRefresh?.mediaId
        val postedAtNanos = SystemClock.elapsedRealtimeNanos()
        runtime.mainHandler.post {
            val mainStartedAtNanos = SystemClock.elapsedRealtimeNanos()
            AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                songId = traceTargetId,
                stage = "metadata_refresh_main_started",
                details = "queueWaitMs=${(mainStartedAtNanos - postedAtNanos) / 1_000_000.0}," +
                    "thread=${Thread.currentThread().name}"
            )
            var listenerHandled = false
            var callbackTarget = "none"
            var callbackInvocations = 0
            listenerRefresh?.let { refresh ->
                val listener = refresh.listener.get()
                val metadata = refresh.metadata.get()
                if (listener != null && metadata != null) {
                    if (appliedAlias != null && callbackAppliedAliases[listener] == appliedAlias) {
                        listenerHandled = true
                        callbackTarget = "listener_deduplicated"
                    } else {
                        val callbackStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                        callbackInvocations += 1
                        AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                            songId = refresh.mediaId,
                            stage = "metadata_listener_callback_started",
                            details = "thread=${Thread.currentThread().name}"
                        )
                        runCatching { refresh.method.invoke(listener, metadata) }
                            .onSuccess {
                                listenerHandled = true
                                callbackTarget = "listener"
                                appliedAlias?.let { callbackAppliedAliases[listener] = it }
                                logMetadataIdentity(
                                    "in_app_now_playing_refresh",
                                    "refreshId=${refresh.mediaId}",
                                )
                                AppleSourceSwitchPerformanceDiagnostics.record(
                                    songId = refresh.mediaId,
                                    event = "metadata_callback_invoke",
                                    durationNanos =
                                        SystemClock.elapsedRealtimeNanos() - callbackStartedAtNanos,
                                    details = "target=listener,success=true",
                                )
                            }
                            .onFailure {
                                callbackTarget = "listener_failed"
                                ProviderLogger.error("Apple Music App 播放页元数据刷新失败", it)
                                AppleSourceSwitchPerformanceDiagnostics.record(
                                    songId = refresh.mediaId,
                                    event = "metadata_callback_invoke",
                                    durationNanos =
                                        SystemClock.elapsedRealtimeNanos() - callbackStartedAtNanos,
                                    details = "target=listener,success=false,error=" +
                                        it.javaClass.simpleName,
                                )
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
                        val callbackStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                        callbackInvocations += 1
                        AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                            songId = refresh.mediaId,
                            stage = "metadata_dispatcher_callback_started",
                            details = "thread=${Thread.currentThread().name}"
                        )
                        runCatching { refresh.method.invoke(dispatcher, metadata) }
                            .onSuccess {
                                callbackTarget = "dispatcher"
                                appliedAlias?.let { callbackAppliedAliases[dispatcher] = it }
                                logMetadataIdentity(
                                    "in_app_dispatcher_refresh",
                                    "refreshId=${refresh.mediaId}",
                                )
                                AppleSourceSwitchPerformanceDiagnostics.record(
                                    songId = refresh.mediaId,
                                    event = "metadata_callback_invoke",
                                    durationNanos =
                                        SystemClock.elapsedRealtimeNanos() - callbackStartedAtNanos,
                                    details = "target=dispatcher,success=true",
                                )
                            }
                            .onFailure {
                                callbackTarget = "dispatcher_failed"
                                ProviderLogger.error("Apple Music App 全局元数据刷新失败", it)
                                AppleSourceSwitchPerformanceDiagnostics.record(
                                    songId = refresh.mediaId,
                                    event = "metadata_callback_invoke",
                                    durationNanos =
                                        SystemClock.elapsedRealtimeNanos() - callbackStartedAtNanos,
                                    details = "target=dispatcher,success=false,error=" +
                                        it.javaClass.simpleName,
                                )
                            }
                    }
                }
            }
            AppleSourceSwitchPerformanceDiagnostics.record(
                songId = traceTargetId,
                event = "metadata_refresh_main_total",
                durationNanos = SystemClock.elapsedRealtimeNanos() - mainStartedAtNanos,
                details = "queueWaitMs=" +
                    ((mainStartedAtNanos - postedAtNanos) / 1_000_000.0) +
                    ",target=$callbackTarget,invocations=$callbackInvocations," +
                    "listenerHandled=$listenerHandled",
            )
            AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                songId = traceTargetId,
                stage = "metadata_refresh_main_finished",
                details = "target=$callbackTarget,invocations=$callbackInvocations," +
                    "listenerHandled=$listenerHandled,totalMs=" +
                    ((SystemClock.elapsedRealtimeNanos() - mainStartedAtNanos) / 1_000_000.0) +
                    ",thread=${Thread.currentThread().name}"
            )
        }
    }

    /**
     * 重新触发当前 PlaybackItem 的 DataBinding 计算。
     *
     * Apple Music 6.5.1 的歌词按钮把 `e1.i(playbackItem)` 的首次结果缓存在
     * `l7.N2` 绑定中；仅重放媒体元数据回调时，新旧 PlaybackItem 在 Hook 下会同时
     * 返回相同的 `hasLyrics()`，差异比较因此不会重新绑定。这里使用原始 DEX 已确认的
     * `BaseCollectionItemView -> androidx.databinding.a#notifyChange()` 路径，只使现有绑定
     * 重新读取可用性，不修改 Apple 的歌词字段或来源优先级。
     */
    fun refreshPlaybackItemBindings(mediaId: String?) {
        val targetId = mediaId?.takeIf(String::isNotBlank) ?: return
        val postedAtNanos = SystemClock.elapsedRealtimeNanos()
        runtime.mainHandler.post {
            val mainStartedAtNanos = SystemClock.elapsedRealtimeNanos()
            AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                songId = targetId,
                stage = "playback_binding_refresh_main_started",
                details = "queueWaitMs=${(mainStartedAtNanos - postedAtNanos) / 1_000_000.0}," +
                    "thread=${Thread.currentThread().name}"
            )
            var candidates = 0
            var standardCandidates = 0
            var refreshed = 0
            val notifiedItems = ArrayList<String>()
            registry.livePlaybackItemRefs(targetId).forEach { ref ->
                candidates += 1
                if (ref.contract != InAppPlaybackItemContract.STANDARD) return@forEach
                standardCandidates += 1
                val playbackItem = ref.playbackItem.get() ?: return@forEach
                val itemIdentity =
                    "${playbackItem.javaClass.name}@" +
                        System.identityHashCode(playbackItem).toString(16)
                if (notifyPlaybackItemChanged(playbackItem, "歌词可用性刷新")) {
                    refreshed += 1
                    notifiedItems += itemIdentity
                }
            }
            if (BuildConfig.DEBUG) {
                ProviderLogger.diagnostic(
                    "Apple Music 无歌词补充播放页绑定刷新: " +
                        "id=$targetId, items=$refreshed, " +
                        "notified=${notifiedItems.joinToString(prefix = "[", postfix = "]")}"
                )
            }
            AppleSourceSwitchPerformanceDiagnostics.record(
                songId = targetId,
                event = "playback_binding_refresh_main_total",
                durationNanos = SystemClock.elapsedRealtimeNanos() - mainStartedAtNanos,
                units = refreshed.toLong(),
                details = "queueWaitMs=" +
                    ((mainStartedAtNanos - postedAtNanos) / 1_000_000.0) +
                    ",candidates=$candidates,standard=$standardCandidates,notified=$refreshed",
            )
            AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                songId = targetId,
                stage = "playback_binding_refresh_main_finished",
                details = "candidates=$candidates,standard=$standardCandidates,notified=$refreshed," +
                    "totalMs=${(SystemClock.elapsedRealtimeNanos() - mainStartedAtNanos) / 1_000_000.0}," +
                    "thread=${Thread.currentThread().name}"
            )
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

    private fun notifyPlaybackItemChanged(playbackItem: Any, operation: String): Boolean =
        runCatching {
            AppleReflection.call(
                playbackItem,
                contentItemTarget.target.runtimeMemberName(
                    AppleMusicRuntimeMember.CONTENT_ITEM_NOTIFY_CHANGE_METHOD,
                ),
            )
            true
        }
            .getOrElse {
                ProviderLogger.error(
                    "Apple Music App PlaybackItem $operation 通知失败: " +
                        "class=${playbackItem.javaClass.name}",
                    it,
                )
                false
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
