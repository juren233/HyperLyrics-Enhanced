/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import java.util.concurrent.atomic.AtomicLong

internal interface AppleLibrarySurfaceHost {
    fun contentItemMediaId(source: Any): String?

    fun primeLibrarySource(source: Any?)

    fun mediaApiEntityAttributes(entity: Any): Any?

    fun mediaApiEntityCatalogId(entity: Any, knownAttributes: Any? = null): String?

    fun mediaApiEntityLookupIds(entity: Any, knownAttributes: Any?): Set<String>

    fun mergePlaybackAccountMetadata(
        mediaId: String,
        title: String?,
        artist: String?,
    )

    fun requestPriorityForMediaId(
        mediaId: String,
    ): RequestPriority

    fun enrichEntityAssociations(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        attributes: Any,
        originalName: String?,
        originalArtist: String?,
        originalAlbum: String?,
    )

    fun recordCurrentRecyclerMediaId(mediaId: String)

    fun effectiveAlias(mediaId: String): Alias?

    fun normalizeMediaIds(mediaIds: Collection<String>): List<String>

    fun markMetadataVisible(mediaIds: Collection<String>)

    fun applyAliasToMetadataRefs(
        mediaId: String,
        alias: Alias,
    )

    fun scheduleMetadataResolution(
        mediaIds: Collection<String>,
        priority: RequestPriority,
    )

    fun isRefreshableMediaId(mediaId: String): Boolean

    fun nextMetadataTraceSequence(): Long

    fun logMetadataIdentity(event: String, details: String)

    fun debugStackSummary(): String

    fun controllerBuildStrategy(controller: Any): InAppLibraryControllerBuildStrategy

    fun controllerAppliedAlias(
        controller: Any,
        mediaId: String,
        alias: Alias,
    ): AppliedMetadataAlias

    fun controllerAlbumTrackMediaIds(controller: Any): Collection<String>

    fun requestControllerBuild(
        controller: Any,
        strategy: InAppLibraryControllerBuildStrategy,
    )

}

/**
 * Library Surface Hook 的默认宿主实现：orchestrator 依赖以 supplier 显式注入，保持原匿名实现
 * "调用期解析"的语义；traceSequence 为根单例构造期值可直捕。
 */
internal class DefaultAppleLibrarySurfaceHost(
    private val contentItemMediaIdFn: (Any) -> String?,
    private val primeLibrarySourceFn: (Any?) -> Unit,
    private val mediaApiEntityAttributesFn: (Any) -> Any?,
    private val mediaApiEntityCatalogIdFn: (Any, Any?) -> String?,
    private val mediaApiEntityLookupIdsFn: (Any, Any?) -> Set<String>,
    private val mergePlaybackAccountMetadataFn: (String, String?, String?) -> Unit,
    private val requestPriorityForMediaIdFn: (String) -> RequestPriority,
    private val enrichEntityAssociationsFn: (
        String, Any, InAppLibraryEntityKind, Any, String?, String?, String?,
    ) -> Unit,
    private val recordCurrentRecyclerMediaIdFn: (String) -> Unit,
    private val effectiveAliasFn: (String) -> Alias?,
    private val normalizeMediaIdsFn: (Collection<String>) -> List<String>,
    private val markMetadataVisibleFn: (Collection<String>) -> Unit,
    private val applyAliasToMetadataRefsFn: (String, Alias) -> Unit,
    private val scheduleMetadataResolutionFn: (Collection<String>, RequestPriority) -> Unit,
    private val isRefreshableMediaIdFn: (String) -> Boolean,
    private val logMetadataIdentityFn: (String, String) -> Unit,
    private val debugStackSummaryFn: () -> String,
    private val controllerBuildStrategyFn: (Any) -> InAppLibraryControllerBuildStrategy,
    private val controllerAppliedAliasFn: (Any, String, Alias) -> AppliedMetadataAlias,
    private val controllerAlbumTrackMediaIdsFn: (Any) -> Collection<String>,
    private val requestControllerBuildFn: (Any, InAppLibraryControllerBuildStrategy) -> Unit,
    private val traceSequence: AtomicLong,
) : AppleLibrarySurfaceHost {
    override fun contentItemMediaId(source: Any): String? =
        contentItemMediaIdFn(source)

    override fun primeLibrarySource(source: Any?) {
        primeLibrarySourceFn(source)
    }

    override fun mediaApiEntityAttributes(entity: Any): Any? =
        mediaApiEntityAttributesFn(entity)

    override fun mediaApiEntityCatalogId(entity: Any, knownAttributes: Any?): String? =
        mediaApiEntityCatalogIdFn(entity, knownAttributes)

    override fun mediaApiEntityLookupIds(entity: Any, knownAttributes: Any?): Set<String> =
        mediaApiEntityLookupIdsFn(entity, knownAttributes)

    override fun mergePlaybackAccountMetadata(
        mediaId: String,
        title: String?,
        artist: String?,
    ) {
        mergePlaybackAccountMetadataFn(mediaId, title, artist)
    }

    override fun requestPriorityForMediaId(mediaId: String): RequestPriority =
        requestPriorityForMediaIdFn(mediaId)

    override fun enrichEntityAssociations(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        attributes: Any,
        originalName: String?,
        originalArtist: String?,
        originalAlbum: String?,
    ) {
        enrichEntityAssociationsFn(
            mediaId, entity, kind, attributes, originalName, originalArtist, originalAlbum,
        )
    }

    override fun recordCurrentRecyclerMediaId(mediaId: String) {
        recordCurrentRecyclerMediaIdFn(mediaId)
    }

    override fun effectiveAlias(mediaId: String): Alias? =
        effectiveAliasFn(mediaId)

    override fun normalizeMediaIds(mediaIds: Collection<String>): List<String> =
        normalizeMediaIdsFn(mediaIds)

    override fun markMetadataVisible(mediaIds: Collection<String>) {
        markMetadataVisibleFn(mediaIds)
    }

    override fun applyAliasToMetadataRefs(
        mediaId: String,
        alias: Alias,
    ) {
        applyAliasToMetadataRefsFn(mediaId, alias)
    }

    override fun scheduleMetadataResolution(
        mediaIds: Collection<String>,
        priority: RequestPriority,
    ) {
        scheduleMetadataResolutionFn(mediaIds, priority)
    }

    override fun isRefreshableMediaId(mediaId: String): Boolean =
        isRefreshableMediaIdFn(mediaId)

    override fun nextMetadataTraceSequence(): Long =
        traceSequence.incrementAndGet()

    override fun logMetadataIdentity(event: String, details: String) {
        logMetadataIdentityFn(event, details)
    }

    override fun debugStackSummary(): String =
        debugStackSummaryFn()

    override fun controllerBuildStrategy(controller: Any): InAppLibraryControllerBuildStrategy =
        controllerBuildStrategyFn(controller)

    override fun controllerAppliedAlias(
        controller: Any,
        mediaId: String,
        alias: Alias,
    ): AppliedMetadataAlias = controllerAppliedAliasFn(controller, mediaId, alias)

    override fun controllerAlbumTrackMediaIds(controller: Any): Collection<String> =
        controllerAlbumTrackMediaIdsFn(controller)

    override fun requestControllerBuild(
        controller: Any,
        strategy: InAppLibraryControllerBuildStrategy,
    ) {
        requestControllerBuildFn(controller, strategy)
    }
}

