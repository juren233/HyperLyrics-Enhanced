/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalReentryGuard
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.reflect.Executable
import java.util.concurrent.ConcurrentHashMap

internal interface AppleContentItemMetadataHost {
    fun containerNavigationBinding(contentItem: Any): InAppContainerNavigationRef?

    fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias?

    fun registerContainerItem(mediaId: String, contentItem: Any, kind: InAppContainerKind)

    fun localizedEntityType(
        contentItem: Any,
    ): AppleInternalCatalogResolver.LocalizedEntityType?

    fun recordComposeMediaId(mediaId: String)

    fun recordCurrentRecyclerMediaId(mediaId: String)

    fun requestPriority(mediaId: String): AppleInternalCatalogResolver.RequestPriority

    fun shouldResolveFromGetter(
        priority: AppleInternalCatalogResolver.RequestPriority,
    ): Boolean

    fun registerPlaybackItem(
        mediaId: String,
        playbackItem: Any,
        notifyChange: Boolean,
        analyzeMetadata: Boolean,
    )

    fun shouldRequestOverride(mediaId: String): Boolean

    fun applyAliasToPlaybackItem(
        playbackItem: Any,
        alias: AppleInternalCatalogResolver.Alias,
        notifyChange: Boolean,
    )

    fun metadataOverride(
        entityType: AppleInternalCatalogResolver.LocalizedEntityType,
        getter: AppleContentItemGetter,
        alias: AppleInternalCatalogResolver.Alias,
        original: String?,
    ): String?
}

/** Owns ContentItem identity caching and the lazily installed metadata getter Hooks. */
internal class AppleContentItemMetadataHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val host: AppleContentItemMetadataHost,
) {
    private val hookedMethods = ConcurrentHashMap.newKeySet<Executable>()
    private val mediaIds = WeakIdentityMap<Any, String>()
    private val getterGuard = ThreadLocalReentryGuard()
    private val baseContentItemRuntimeTarget by lazy {
        runtime.hookResolver.resolveClasses(AppleMusicHookPoint.CONTENT_ITEM_METADATA_CLASSES)
            .first { resolved ->
                resolved.target.runtimeMemberNameOrNull(AppleMusicRuntimeMember.CONTENT_ITEM_ROLE) ==
                    "base"
            }
    }

    fun installHooks() {
        runCatching {
            val resolvedClasses = runtime.hookResolver.resolveClasses(
                AppleMusicHookPoint.CONTENT_ITEM_METADATA_CLASSES,
            )
            val baseContentItemClass = baseContentItemRuntimeTarget.clazz
            baseContentItemClass.declaredConstructors.forEach { constructor ->
                constructor.isAccessible = true
                runtime.hookRegistrar.installHook(constructor, after = { chain, _ ->
                    chain.thisObject?.javaClass?.let(::ensureHooks)
                })
            }
            resolvedClasses.forEach { resolved -> ensureHooks(resolved.clazz) }
            ProviderLogger.info(
                "Apple Music 内容项 title/artist/album 模型 Hook 已安装"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 内容项元数据 Hook 安装失败", it)
        }
    }

    fun ensureHooks(contentItemClass: Class<*>) {
        listOf(
            AppleContentItemGetter.TITLE to AppleMusicRuntimeMember.CONTENT_ITEM_TITLE_GETTER,
            AppleContentItemGetter.NOW_PLAYING_TITLE to
                AppleMusicRuntimeMember.CONTENT_ITEM_NOW_PLAYING_TITLE_GETTER,
            AppleContentItemGetter.ARTIST to AppleMusicRuntimeMember.CONTENT_ITEM_ARTIST_GETTER,
            AppleContentItemGetter.NOW_PLAYING_SUBTITLE to
                AppleMusicRuntimeMember.CONTENT_ITEM_NOW_PLAYING_SUBTITLE_GETTER,
            AppleContentItemGetter.SUBTITLE to AppleMusicRuntimeMember.CONTENT_ITEM_SUBTITLE_GETTER,
            AppleContentItemGetter.COLLECTION to
                AppleMusicRuntimeMember.CONTENT_ITEM_COLLECTION_GETTER,
        ).forEach { (getter, runtimeMember) ->
            val methodName = baseContentItemRuntimeTarget.target.runtimeMemberName(runtimeMember)
            val method = runCatching {
                AppleReflection.findMethod(contentItemClass, methodName, parameterCount = 0)
            }.getOrNull() ?: return@forEach
            if (method.returnType != String::class.java || !hookedMethods.add(method)) {
                return@forEach
            }
            runtime.hookRegistrar.installResultOverrideHook(method) { chain, original ->
                if (getterGuard.isActive) {
                    return@installResultOverrideHook original
                }
                val contentItem = chain.thisObject ?: return@installResultOverrideHook original
                val containerBinding = host.containerNavigationBinding(contentItem)
                if (containerBinding != null) {
                    val mediaId = containerBinding.mediaId
                    val containerKind = containerBinding.kind
                    val alias = host.effectiveAlias(mediaId)
                    host.registerContainerItem(mediaId, contentItem, containerKind)
                    if (getter != AppleContentItemGetter.TITLE || alias == null) {
                        return@installResultOverrideHook original
                    }
                    return@installResultOverrideHook when (containerKind) {
                        InAppContainerKind.ARTIST ->
                            alias.artist.takeIf(String::isNotBlank) ?: original
                        InAppContainerKind.ALBUM ->
                            alias.album.takeIf(String::isNotBlank) ?: original
                    }
                }
                val entityType = host.localizedEntityType(contentItem)
                    ?: return@installResultOverrideHook original
                val mediaId = runCatching { mediaId(contentItem) }.getOrNull()
                    ?: return@installResultOverrideHook original
                host.recordComposeMediaId(mediaId)
                host.recordCurrentRecyclerMediaId(mediaId)
                val requestPriority = host.requestPriority(mediaId)
                val surfaceRelevant = host.shouldResolveFromGetter(requestPriority)
                val resolvedAlias = host.effectiveAlias(mediaId)
                host.registerPlaybackItem(
                    mediaId = mediaId,
                    playbackItem = contentItem,
                    notifyChange = false,
                    analyzeMetadata = surfaceRelevant && (
                        resolvedAlias == null || host.shouldRequestOverride(mediaId)
                        ),
                )
                if (resolvedAlias != null) {
                    host.applyAliasToPlaybackItem(
                        playbackItem = contentItem,
                        alias = resolvedAlias,
                        notifyChange = false,
                    )
                }
                val alias = resolvedAlias ?: host.effectiveAlias(mediaId)
                if (alias == null) {
                    original
                } else {
                    host.metadataOverride(
                        entityType = entityType,
                        getter = getter,
                        alias = alias,
                        original = original as? String,
                    ) ?: original
                }
            }
            ProviderLogger.info(
                "Apple 内容项元数据 getter Hook 已安装: " +
                    "class=${method.declaringClass.name}, method=$methodName"
            )
        }
    }

    fun mediaId(contentItem: Any, refresh: Boolean = false): String? {
        if (!refresh) {
            synchronized(mediaIds) {
                mediaIds[contentItem]?.let { return it }
            }
        }
        val subscriptionStoreId = runCatching {
            AppleReflection.call(
                contentItem,
                baseContentItemRuntimeTarget.target.runtimeMemberName(
                    AppleMusicRuntimeMember.CONTENT_ITEM_SUBSCRIPTION_STORE_ID_GETTER
                ),
            ) as? String
        }.getOrNull()
        val id = runCatching {
            AppleReflection.call(
                contentItem,
                baseContentItemRuntimeTarget.target.runtimeMemberName(
                    AppleMusicRuntimeMember.CONTENT_ITEM_ID_GETTER
                ),
            ) as? String
        }.getOrNull()
        val persistentId = runCatching {
            AppleReflection.call(
                contentItem,
                baseContentItemRuntimeTarget.target.runtimeMemberName(
                    AppleMusicRuntimeMember.CONTENT_ITEM_PERSISTENT_ID_GETTER
                ),
            ) as? Long
        }.getOrNull()?.takeIf { it > 0L }?.toString()
        val resolvedMediaId = sequenceOf(subscriptionStoreId, id, persistentId)
            .filterNotNull()
            .firstOrNull { candidate -> candidate.isNotBlank() && candidate.all(Char::isDigit) }
        synchronized(mediaIds) {
            if (resolvedMediaId == null && refresh) {
                mediaIds.remove(contentItem)
            } else if (resolvedMediaId != null) {
                mediaIds[contentItem] = resolvedMediaId
            }
        }
        return resolvedMediaId
    }

    fun <T> withOriginalGetters(block: () -> T): T = getterGuard.run(block)
}
