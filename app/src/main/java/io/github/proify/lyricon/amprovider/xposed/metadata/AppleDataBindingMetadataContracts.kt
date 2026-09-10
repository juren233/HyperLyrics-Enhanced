/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalStack
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

internal interface AppleDataBindingMetadataHost {
    fun contentItemMediaId(contentItem: Any): String?

    fun bindingCandidateMediaId(value: Any): String?

    fun onBeginBindingModel(binding: Any)

    fun onBindingMediaIdChanged(binding: Any, previousMediaId: String?, mediaId: String)

    fun originalResolutionMode(binding: Any): InAppOriginalResolutionMode

    fun shouldInvalidateAppliedAlias(
        binding: Any,
        mediaId: String,
        appliedAlias: AppliedMetadataAlias,
        pendingAlias: AppliedMetadataAlias?,
        renderedTexts: Collection<String>,
    ): Boolean

    fun effectiveAlias(mediaId: String): Alias?

    fun aliasValues(
        mediaId: String,
        alias: Alias,
        binding: Any?,
    ): DataBindingAliasValues

    fun isCurrentSurfaceMediaId(mediaId: String): Boolean

    fun hasVisibleConsumer(mediaId: String): Boolean

    fun isRefreshableMediaId(mediaId: String): Boolean

    fun boundModelCandidates(mediaId: String): List<Any>

    fun enrichEntitiesForResolution(mediaIds: Collection<String>)

    fun markMetadataVisible(mediaIds: Collection<String>)

    fun scheduleMetadataResolution(
        mediaIds: Collection<String>,
        priority: RequestPriority,
        originalResolutionMode: InAppOriginalResolutionMode =
            InAppOriginalResolutionMode.AFTER_LOCALIZED,
    )

    fun isAppleLyricsRecyclerAdapter(adapter: Any?): Boolean

    fun isQueueAdapter(adapter: Any): Boolean

    fun isArtistProfileRecyclerAdapter(adapter: Any): Boolean

    fun nextMetadataTraceSequence(): Long
}

internal fun normalizedRecyclerBindingMediaIds(mediaIds: Collection<String>): Set<String> =
    mediaIds.asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && it.all(Char::isDigit) }
        .toCollection(linkedSetOf())

internal fun shouldScheduleVisibleRecyclerMetadata(
    previousMediaIds: Set<String>?,
    currentMediaIds: Set<String>,
    visible: Boolean,
): Boolean = visible && currentMediaIds.isNotEmpty() && previousMediaIds != currentMediaIds

internal fun shouldRegisterGenericRecyclerRefresh(
    mediaIds: Set<String>,
    dataBindingMediaId: String?,
    blockMultiItemStructuralRefresh: Boolean,
): Boolean {
    if (mediaIds.isEmpty()) return false
    if (mediaIds.size == 1 && dataBindingMediaId in mediaIds) return false
    return !blockMultiItemStructuralRefresh
}

internal fun shouldScheduleDataBindingAliasRefresh(
    appliedAlias: AppliedMetadataAlias?,
    pendingAlias: AppliedMetadataAlias?,
    requestedAlias: AppliedMetadataAlias?,
): Boolean = requestedAlias == null ||
    (appliedAlias != requestedAlias && pendingAlias != requestedAlias)

internal fun dataBindingRefreshStrategy(
    expectedTitle: String?,
    expectedSubtitle: String?,
    titleApplied: Boolean,
    subtitleApplied: Boolean,
): DataBindingRefreshStrategy {
    val titleRequired = !expectedTitle.isNullOrBlank()
    val subtitleRequired = !expectedSubtitle.isNullOrBlank()
    val allRequiredVariablesApplied =
        (titleRequired || subtitleRequired) &&
            (!titleRequired || titleApplied) &&
            (!subtitleRequired || subtitleApplied)
    return if (allRequiredVariablesApplied) {
        DataBindingRefreshStrategy.VARIABLES_ONLY
    } else {
        DataBindingRefreshStrategy.FULL_INVALIDATE
    }
}

internal fun dataBindingAliasAlreadyRendered(
    expectedTitle: String?,
    expectedSubtitle: String?,
    renderedTexts: Collection<String>,
): Boolean {
    val rendered = renderedTexts
        .map(String::trim)
        .filter(String::isNotEmpty)
    fun containsExpected(value: String?): Boolean {
        val expected = value?.trim()?.takeIf(String::isNotEmpty) ?: return true
        return rendered.any { text -> text == expected || expected in text }
    }
    return rendered.isNotEmpty() &&
        containsExpected(expectedTitle) &&
        containsExpected(expectedSubtitle)
}

internal fun isDataBindingRefreshCurrent(
    currentMediaId: String?,
    requestedMediaId: String,
    currentBindGeneration: Long,
    scheduledBindGeneration: Long,
): Boolean = currentMediaId == requestedMediaId &&
    currentBindGeneration == scheduledBindGeneration

/**
 * DataBinding 元数据 Hook 的默认宿主实现：orchestrator 依赖以 supplier 显式注入，保持原匿名
 * 实现"调用期解析"的语义；registry/traceSequence 为根单例构造期值可直捕。
 */
internal class DefaultAppleDataBindingMetadataHost(
    private val contentItemMediaIdFn: (Any) -> String?,
    private val onBeginBindingModelFn: (Any) -> Unit,
    private val onBindingMediaIdChangedFn: (Any, String?, String) -> Unit,
    private val originalResolutionModeFn: (Any) -> InAppOriginalResolutionMode,
    private val shouldInvalidateAppliedAliasFn: (
        Any, String, AppliedMetadataAlias, AppliedMetadataAlias?, Collection<String>,
    ) -> Boolean,
    private val effectiveAliasFn: (String) -> Alias?,
    private val aliasValuesFn: (String, Alias, Any?) -> DataBindingAliasValues,
    private val isCurrentSurfaceMediaIdFn: (String) -> Boolean,
    private val hasVisibleConsumerFn: (String) -> Boolean,
    private val isRefreshableMediaIdFn: (String) -> Boolean,
    private val enrichEntitiesForResolutionFn: (Collection<String>) -> Unit,
    private val markMetadataVisibleFn: (Collection<String>) -> Unit,
    private val scheduleMetadataResolutionFn: (
        Collection<String>, RequestPriority, InAppOriginalResolutionMode,
    ) -> Unit,
    private val isAppleLyricsRecyclerAdapterFn: (Any?) -> Boolean,
    private val isQueueAdapterFn: (Any) -> Boolean,
    private val isArtistProfileRecyclerAdapterFn: (Any) -> Boolean,
    private val entityMediaIdFn: (Any) -> String?,
    private val attributeBindingMediaIdFn: (Any) -> String?,
    private val liveEntitiesFn: (String) -> List<Any>,
    private val registry: AppleInAppMetadataRegistry,
    private val traceSequence: AtomicLong,
) : AppleDataBindingMetadataHost {
    override fun contentItemMediaId(contentItem: Any): String? =
        contentItemMediaIdFn(contentItem)

    override fun onBeginBindingModel(binding: Any) {
        onBeginBindingModelFn(binding)
    }

    override fun onBindingMediaIdChanged(
        binding: Any,
        previousMediaId: String?,
        mediaId: String,
    ) {
        onBindingMediaIdChangedFn(binding, previousMediaId, mediaId)
    }

    override fun originalResolutionMode(binding: Any): InAppOriginalResolutionMode =
        originalResolutionModeFn(binding)

    override fun shouldInvalidateAppliedAlias(
        binding: Any,
        mediaId: String,
        appliedAlias: AppliedMetadataAlias,
        pendingAlias: AppliedMetadataAlias?,
        renderedTexts: Collection<String>,
    ): Boolean = shouldInvalidateAppliedAliasFn(
        binding, mediaId, appliedAlias, pendingAlias, renderedTexts,
    )

    override fun effectiveAlias(mediaId: String): Alias? =
        effectiveAliasFn(mediaId)

    override fun aliasValues(
        mediaId: String,
        alias: Alias,
        binding: Any?,
    ): DataBindingAliasValues = aliasValuesFn(mediaId, alias, binding)

    override fun isCurrentSurfaceMediaId(mediaId: String): Boolean =
        isCurrentSurfaceMediaIdFn(mediaId)

    override fun hasVisibleConsumer(mediaId: String): Boolean =
        hasVisibleConsumerFn(mediaId)

    override fun isRefreshableMediaId(mediaId: String): Boolean =
        isRefreshableMediaIdFn(mediaId)

    override fun enrichEntitiesForResolution(mediaIds: Collection<String>) {
        enrichEntitiesForResolutionFn(mediaIds)
    }

    override fun markMetadataVisible(mediaIds: Collection<String>) {
        markMetadataVisibleFn(mediaIds)
    }

    override fun scheduleMetadataResolution(
        mediaIds: Collection<String>,
        priority: RequestPriority,
        originalResolutionMode: InAppOriginalResolutionMode,
    ) {
        scheduleMetadataResolutionFn(mediaIds, priority, originalResolutionMode)
    }

    override fun isAppleLyricsRecyclerAdapter(adapter: Any?): Boolean =
        isAppleLyricsRecyclerAdapterFn(adapter)

    override fun isQueueAdapter(adapter: Any): Boolean =
        isQueueAdapterFn(adapter)

    override fun isArtistProfileRecyclerAdapter(adapter: Any): Boolean =
        isArtistProfileRecyclerAdapterFn(adapter)

    override fun nextMetadataTraceSequence(): Long =
        traceSequence.incrementAndGet()

    override fun bindingCandidateMediaId(value: Any): String? =
        registry.metadataId(value)
            ?: registry.playbackItemId(value)
            ?: entityMediaIdFn(value)
            ?: attributeBindingMediaIdFn(value)

    override fun boundModelCandidates(mediaId: String): List<Any> =
        registry.livePlaybackItems(mediaId) + liveEntitiesFn(mediaId)
}

