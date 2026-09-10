/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

internal interface ApplePlaybackItemConversionHost {
    fun containerKind(containerItem: Any): InAppContainerKind?

    fun metadataId(metadata: Any, fallback: String?): String?

    fun activePlaybackIdentity(): ActivePlaybackMediaIdentity

    fun metadataDetails(metadata: Any): String

    fun logMetadataIdentity(
        event: String,
        identity: ActivePlaybackMediaIdentity,
        details: String,
    )

    fun markContainerNavigationItem(
        containerItem: Any,
        kind: InAppContainerKind,
        mediaId: String,
    )

    fun markMetadataVisible(mediaIds: Collection<String>)

    fun registerContainerItem(
        mediaId: String,
        containerItem: Any,
        kind: InAppContainerKind,
    )

    fun effectiveAlias(mediaId: String): Alias?

    fun applyAliasToContainerItem(
        containerItem: Any,
        kind: InAppContainerKind,
        alias: Alias,
    )

    fun contentItemMediaId(contentItem: Any): String?

    fun registerPlaybackItem(mediaId: String, playbackItem: Any)

    fun applyAliasToPlaybackItem(
        playbackItem: Any,
        alias: Alias,
    )

    fun shouldRequestOverride(mediaId: String): Boolean

    fun ensureOverride(
        mediaId: String,
        priority: RequestPriority,
    )
}

internal class ApplePlaybackItemConversionHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val host: ApplePlaybackItemConversionHost,
) {
    fun installHooks() {
        runCatching {
            val resolvedPlayerUtil = runtime.hookResolver.resolveClass(
                AppleMusicHookPoint.APPLE_PLAYER_UTIL_CLASS,
            )
            val playerUtilClass = resolvedPlayerUtil.clazz
            val containerMethod = AppleReflection.findMethod(
                playerUtilClass,
                resolvedPlayerUtil.target.runtimeMemberName(
                    AppleMusicRuntimeMember.APPLE_PLAYER_UTIL_CONTAINER_METHOD,
                ),
                parameterCount = 1,
            )
            runtime.hookRegistrar.installResultOverrideHook(containerMethod) { chain, original ->
                val containerItem = original ?: return@installResultOverrideHook original
                val metadata = chain.args.firstOrNull()
                    ?: return@installResultOverrideHook original
                val kind = host.containerKind(containerItem)
                    ?: return@installResultOverrideHook original
                val metadataId = host.metadataId(metadata, null)
                val identity = host.activePlaybackIdentity()
                val mediaId = metadataId
                host.logMetadataIdentity(
                    event = "container_conversion",
                    identity = identity,
                    details = "metadataId=$metadataId, resolvedId=$mediaId, kind=$kind, " +
                        "class=${containerItem.javaClass.name}, ${host.metadataDetails(metadata)}",
                )
                if (mediaId == null) return@installResultOverrideHook original
                host.markContainerNavigationItem(containerItem, kind, mediaId)
                host.markMetadataVisible(listOf(mediaId))
                host.registerContainerItem(mediaId, containerItem, kind)
                host.effectiveAlias(mediaId)?.let { alias ->
                    host.applyAliasToContainerItem(containerItem, kind, alias)
                }
                if (host.shouldRequestOverride(mediaId)) {
                    host.ensureOverride(
                        mediaId = mediaId,
                        priority = RequestPriority.VISIBLE,
                    )
                }
                original
            }

            val playbackItemMethod = AppleReflection.findMethod(
                playerUtilClass,
                resolvedPlayerUtil.target.runtimeMemberName(
                    AppleMusicRuntimeMember.APPLE_PLAYER_UTIL_PLAYBACK_ITEM_METHOD,
                ),
                parameterCount = 1,
            )
            runtime.hookRegistrar.installResultOverrideHook(playbackItemMethod) { chain, original ->
                val playbackItem = original ?: return@installResultOverrideHook original
                val metadata = chain.args.firstOrNull()
                    ?: return@installResultOverrideHook original
                val mediaId = host.metadataId(metadata, null)
                    ?: host.contentItemMediaId(playbackItem)
                    ?: return@installResultOverrideHook original
                host.markMetadataVisible(listOf(mediaId))
                host.registerPlaybackItem(mediaId, playbackItem)
                host.effectiveAlias(mediaId)?.let { alias ->
                    host.applyAliasToPlaybackItem(playbackItem, alias)
                }
                if (host.shouldRequestOverride(mediaId)) {
                    host.ensureOverride(
                        mediaId = mediaId,
                        priority = RequestPriority.VISIBLE,
                    )
                }
                original
            }
            ProviderLogger.info(
                "Apple Music App 容器跳转项/PlaybackItem 转换 Hook 已安装"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music App 内容项/PlaybackItem 转换 Hook 安装失败", it)
        }
    }
}

/**
 * 容器跳转项/PlaybackItem 转换 Hook 的默认宿主实现：orchestrator 依赖以 supplier 显式注入，
 * 保持原匿名实现"调用期解析"的语义。
 */
internal class DefaultApplePlaybackItemConversionHost(
    private val containerKindFn: (Any) -> InAppContainerKind?,
    private val metadataIdFn: (Any, String?) -> String?,
    private val activePlaybackIdentityFn: () -> ActivePlaybackMediaIdentity,
    private val metadataDetailsFn: (Any) -> String,
    private val logMetadataIdentityFn: (String, ActivePlaybackMediaIdentity, String) -> Unit,
    private val markContainerNavigationItemFn: (Any, InAppContainerKind, String) -> Unit,
    private val markMetadataVisibleFn: (Collection<String>) -> Unit,
    private val registerContainerItemFn: (String, Any, InAppContainerKind) -> Unit,
    private val effectiveAliasFn: (String) -> Alias?,
    private val applyAliasToContainerItemFn: (Any, InAppContainerKind, Alias) -> Unit,
    private val contentItemMediaIdFn: (Any) -> String?,
    private val registerPlaybackItemFn: (String, Any) -> Unit,
    private val applyAliasToPlaybackItemFn: (Any, Alias) -> Unit,
    private val shouldRequestOverrideFn: (String) -> Boolean,
    private val ensureOverrideFn: (String, RequestPriority) -> Unit,
) : ApplePlaybackItemConversionHost {
    override fun containerKind(containerItem: Any): InAppContainerKind? =
        containerKindFn(containerItem)

    override fun metadataId(metadata: Any, fallback: String?): String? =
        metadataIdFn(metadata, fallback)

    override fun activePlaybackIdentity(): ActivePlaybackMediaIdentity =
        activePlaybackIdentityFn()

    override fun metadataDetails(metadata: Any): String =
        metadataDetailsFn(metadata)

    override fun logMetadataIdentity(
        event: String,
        identity: ActivePlaybackMediaIdentity,
        details: String,
    ) {
        logMetadataIdentityFn(event, identity, details)
    }

    override fun markContainerNavigationItem(
        containerItem: Any,
        kind: InAppContainerKind,
        mediaId: String,
    ) {
        markContainerNavigationItemFn(containerItem, kind, mediaId)
    }

    override fun markMetadataVisible(mediaIds: Collection<String>) {
        markMetadataVisibleFn(mediaIds)
    }

    override fun registerContainerItem(
        mediaId: String,
        containerItem: Any,
        kind: InAppContainerKind,
    ) {
        registerContainerItemFn(mediaId, containerItem, kind)
    }

    override fun effectiveAlias(mediaId: String): Alias? =
        effectiveAliasFn(mediaId)

    override fun applyAliasToContainerItem(
        containerItem: Any,
        kind: InAppContainerKind,
        alias: Alias,
    ) {
        applyAliasToContainerItemFn(containerItem, kind, alias)
    }

    override fun contentItemMediaId(contentItem: Any): String? =
        contentItemMediaIdFn(contentItem)

    override fun registerPlaybackItem(mediaId: String, playbackItem: Any) {
        registerPlaybackItemFn(mediaId, playbackItem)
    }

    override fun applyAliasToPlaybackItem(
        playbackItem: Any,
        alias: Alias,
    ) {
        applyAliasToPlaybackItemFn(playbackItem, alias)
    }

    override fun shouldRequestOverride(mediaId: String): Boolean =
        shouldRequestOverrideFn(mediaId)

    override fun ensureOverride(
        mediaId: String,
        priority: RequestPriority,
    ) {
        ensureOverrideFn(mediaId, priority)
    }
}
