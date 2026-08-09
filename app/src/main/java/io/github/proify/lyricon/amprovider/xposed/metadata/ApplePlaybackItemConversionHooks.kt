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

    fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias?

    fun applyAliasToContainerItem(
        containerItem: Any,
        kind: InAppContainerKind,
        alias: AppleInternalCatalogResolver.Alias,
    )

    fun contentItemMediaId(contentItem: Any): String?

    fun registerPlaybackItem(mediaId: String, playbackItem: Any)

    fun applyAliasToPlaybackItem(
        playbackItem: Any,
        alias: AppleInternalCatalogResolver.Alias,
    )

    fun shouldRequestOverride(mediaId: String): Boolean

    fun ensureOverride(
        mediaId: String,
        priority: AppleInternalCatalogResolver.RequestPriority,
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
                        priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
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
                        priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
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
