/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

internal interface AppleListenNowHost {
    fun mediaApiEntityAttributes(entity: Any): Any?

    fun mediaApiEntityCatalogId(entity: Any, knownAttributes: Any? = null): String?

    fun registerLibraryEntity(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        knownAttributes: Any?,
        requestResolution: Boolean,
        retainEntityRef: Boolean,
    )

    fun enrichLibraryEntity(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        attributes: Any,
    )

    fun isRestoreOriginalMetadataEnabled(): Boolean

    fun shouldRetryOriginalMetadataCacheProbe(mediaId: String): Boolean

    fun rememberOriginalMetadataOverride(
        mediaId: String,
        alias: Alias,
        confirmed: Boolean,
    )

    fun rememberOriginalLanguageForArtist(mediaId: String, language: String)

    fun resolveCachedOriginalEntityForInApp(
        mediaId: String,
        entityType: LocalizedEntityType,
        preBind: Boolean,
        priority: RequestPriority,
    )

    fun effectiveAlias(mediaId: String): Alias?

    fun applyAliasToLibraryEntity(
        entity: Any,
        kind: InAppLibraryEntityKind,
        alias: Alias,
    ): Boolean

    fun shouldRequestOverride(mediaId: String): Boolean

    fun markMetadataVisible(mediaIds: Collection<String>)

    fun scheduleMetadataResolution(
        mediaIds: Collection<String>,
        priority: RequestPriority,
        originalResolutionMode: InAppOriginalResolutionMode,
    )

    fun nextMetadataTraceSequence(): Long

    fun logMetadataIdentity(event: String, details: String)

    fun isDataBindingInstance(candidate: Any): Boolean

    fun dataBindingFromHolder(argument: Any?): Any?

    fun beginDataBindingModelBind(binding: Any)

    fun clearDataBindingMediaId(binding: Any)

    fun dataBindingGeneration(binding: Any): Long

    fun captureDataBinding(binding: Any)

    fun registerDataBinding(mediaId: String, binding: Any)

    fun aliasValues(
        mediaId: String,
        alias: Alias,
        binding: Any?,
    ): DataBindingAliasValues

    fun renderedTexts(binding: Any): List<String>

    fun appliedAlias(binding: Any): AppliedMetadataAlias?

    fun rememberAppliedAlias(binding: Any, alias: AppliedMetadataAlias)

    fun applyAliasVariables(
        binding: Any,
        values: DataBindingAliasValues,
    ): DataBindingVariableApplyResult

    fun invalidateDataBinding(binding: Any)

    fun executePendingDataBindings(binding: Any)
}

internal fun shouldRefreshListenNowDataBindingAlias(
    appliedAlias: AppliedMetadataAlias?,
    requestedAlias: AppliedMetadataAlias,
    expectedTitle: String?,
    expectedSubtitle: String?,
    renderedTexts: Collection<String>,
): Boolean {
    if (appliedAlias != requestedAlias) return true
    if (renderedTexts.isEmpty()) return false
    return !dataBindingAliasAlreadyRendered(
        expectedTitle = expectedTitle,
        expectedSubtitle = expectedSubtitle,
        renderedTexts = renderedTexts,
    )
}

internal fun normalizedInAppArtworkValueUrls(value: Any?): List<String> {
    val values: Sequence<Any?> = when (value) {
        null -> emptySequence()
        is CharSequence -> sequenceOf(value)
        is Array<*> -> value.asSequence()
        is Iterable<*> -> value.asSequence()
        else -> emptySequence()
    }
    return values.mapNotNull { item ->
        item?.toString()?.trim()?.takeIf(String::isNotEmpty)
    }.distinct().toList()
}

internal fun preferredInAppListenNowArtworkKey(
    builderKey: InAppListenNowArtworkContinuityKey?,
    delegateKey: InAppListenNowArtworkContinuityKey?,
): InAppListenNowArtworkContinuityKey? = builderKey ?: delegateKey

internal fun listenNowCatalogIdForExactCard(
    builderLiveData: Any?,
    delegateLiveData: Any?,
    builderKey: InAppListenNowArtworkContinuityKey?,
    delegateKey: InAppListenNowArtworkContinuityKey?,
): String? {
    if (builderLiveData == null || builderLiveData !== delegateLiveData) return null
    val builder = builderKey ?: return null
    val delegate = delegateKey ?: return null
    if (
        builder.persistentId != delegate.persistentId ||
        builder.contentType != delegate.contentType ||
        builder.artworkIdentity != delegate.artworkIdentity
    ) return null
    val delegateCatalogId = delegate.id.trim()
        .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?: return null
    val builderId = builder.id.trim()
    val builderCatalogId = builderId.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    if (builderCatalogId != null) {
        return delegateCatalogId.takeIf { it == builderCatalogId }
    }
    return delegateCatalogId.takeIf { builderId.startsWith("l.") }
}

internal fun shouldSkipInAppListenNowArtworkLookup(
    keyMatches: Boolean,
    currentUrls: Collection<String>,
    seededUrls: Collection<String>,
): Boolean {
    if (!keyMatches) return false
    val normalizedCurrent = currentUrls.map(String::trim).filter(String::isNotEmpty).distinct()
    val normalizedSeeded = seededUrls.map(String::trim).filter(String::isNotEmpty).distinct()
    return normalizedCurrent.isNotEmpty() && normalizedCurrent == normalizedSeeded
}

/**
 * Listen Now Hook 的默认宿主实现：orchestrator 依赖以 supplier 显式注入，保持原匿名实现
 * "调用期解析"的语义；traceSequence 为根单例构造期值可直捕。
 */
internal class DefaultAppleListenNowHost(
    private val mediaApiEntityAttributesFn: (Any) -> Any?,
    private val mediaApiEntityCatalogIdFn: (Any, Any?) -> String?,
    private val registerLibraryEntityFn: (
        String, Any, InAppLibraryEntityKind, Any?, Boolean, Boolean,
    ) -> Unit,
    private val enrichLibraryEntityFn: (String, Any, InAppLibraryEntityKind, Any) -> Unit,
    private val isRestoreOriginalMetadataEnabledFn: () -> Boolean,
    private val shouldRetryOriginalMetadataCacheProbeFn: (String) -> Boolean,
    private val rememberOriginalMetadataOverrideFn: (String, Alias, Boolean) -> Unit,
    private val rememberOriginalLanguageForArtistFn: (String, String) -> Unit,
    private val resolveCachedOriginalEntityForInAppFn: (
        String, LocalizedEntityType, Boolean, RequestPriority,
    ) -> Unit,
    private val effectiveAliasFn: (String) -> Alias?,
    private val applyAliasToLibraryEntityFn: (Any, InAppLibraryEntityKind, Alias) -> Boolean,
    private val shouldRequestOverrideFn: (String) -> Boolean,
    private val markMetadataVisibleFn: (Collection<String>) -> Unit,
    private val scheduleMetadataResolutionFn: (
        Collection<String>, RequestPriority, InAppOriginalResolutionMode,
    ) -> Unit,
    private val logMetadataIdentityFn: (String, String) -> Unit,
    private val isDataBindingInstanceFn: (Any) -> Boolean,
    private val dataBindingFromHolderFn: (Any?) -> Any?,
    private val beginDataBindingModelBindFn: (Any) -> Unit,
    private val clearDataBindingMediaIdFn: (Any) -> Unit,
    private val dataBindingGenerationFn: (Any) -> Long,
    private val captureDataBindingFn: (Any) -> Unit,
    private val registerDataBindingFn: (String, Any) -> Unit,
    private val aliasValuesFn: (String, Alias, Any?) -> DataBindingAliasValues,
    private val renderedTextsFn: (Any) -> List<String>,
    private val appliedAliasFn: (Any) -> AppliedMetadataAlias?,
    private val rememberAppliedAliasFn: (Any, AppliedMetadataAlias) -> Unit,
    private val applyAliasVariablesFn: (
        Any, DataBindingAliasValues,
    ) -> DataBindingVariableApplyResult,
    private val invalidateDataBindingFn: (Any) -> Unit,
    private val executePendingDataBindingsFn: (Any) -> Unit,
    private val traceSequence: AtomicLong,
) : AppleListenNowHost {
    override fun mediaApiEntityAttributes(entity: Any): Any? =
        mediaApiEntityAttributesFn(entity)

    override fun mediaApiEntityCatalogId(entity: Any, knownAttributes: Any?): String? =
        mediaApiEntityCatalogIdFn(entity, knownAttributes)

    override fun registerLibraryEntity(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        knownAttributes: Any?,
        requestResolution: Boolean,
        retainEntityRef: Boolean,
    ) {
        registerLibraryEntityFn(
            mediaId, entity, kind, knownAttributes, requestResolution, retainEntityRef,
        )
    }

    override fun enrichLibraryEntity(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        attributes: Any,
    ) {
        enrichLibraryEntityFn(mediaId, entity, kind, attributes)
    }

    override fun isRestoreOriginalMetadataEnabled(): Boolean =
        isRestoreOriginalMetadataEnabledFn()

    override fun shouldRetryOriginalMetadataCacheProbe(mediaId: String): Boolean =
        shouldRetryOriginalMetadataCacheProbeFn(mediaId)

    override fun rememberOriginalMetadataOverride(
        mediaId: String,
        alias: Alias,
        confirmed: Boolean,
    ) {
        rememberOriginalMetadataOverrideFn(mediaId, alias, confirmed)
    }

    override fun rememberOriginalLanguageForArtist(mediaId: String, language: String) {
        rememberOriginalLanguageForArtistFn(mediaId, language)
    }

    override fun resolveCachedOriginalEntityForInApp(
        mediaId: String,
        entityType: LocalizedEntityType,
        preBind: Boolean,
        priority: RequestPriority,
    ) {
        resolveCachedOriginalEntityForInAppFn(mediaId, entityType, preBind, priority)
    }

    override fun effectiveAlias(mediaId: String): Alias? =
        effectiveAliasFn(mediaId)

    override fun applyAliasToLibraryEntity(
        entity: Any,
        kind: InAppLibraryEntityKind,
        alias: Alias,
    ): Boolean = applyAliasToLibraryEntityFn(entity, kind, alias)

    override fun shouldRequestOverride(mediaId: String): Boolean =
        shouldRequestOverrideFn(mediaId)

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

    override fun nextMetadataTraceSequence(): Long =
        traceSequence.incrementAndGet()

    override fun logMetadataIdentity(event: String, details: String) {
        logMetadataIdentityFn(event, details)
    }

    override fun isDataBindingInstance(candidate: Any): Boolean =
        isDataBindingInstanceFn(candidate)

    override fun dataBindingFromHolder(argument: Any?): Any? =
        dataBindingFromHolderFn(argument)

    override fun beginDataBindingModelBind(binding: Any) {
        beginDataBindingModelBindFn(binding)
    }

    override fun clearDataBindingMediaId(binding: Any) {
        clearDataBindingMediaIdFn(binding)
    }

    override fun dataBindingGeneration(binding: Any): Long =
        dataBindingGenerationFn(binding)

    override fun captureDataBinding(binding: Any) {
        captureDataBindingFn(binding)
    }

    override fun registerDataBinding(mediaId: String, binding: Any) {
        registerDataBindingFn(mediaId, binding)
    }

    override fun aliasValues(
        mediaId: String,
        alias: Alias,
        binding: Any?,
    ): DataBindingAliasValues = aliasValuesFn(mediaId, alias, binding)

    override fun renderedTexts(binding: Any): List<String> =
        renderedTextsFn(binding)

    override fun appliedAlias(binding: Any): AppliedMetadataAlias? =
        appliedAliasFn(binding)

    override fun rememberAppliedAlias(binding: Any, alias: AppliedMetadataAlias) {
        rememberAppliedAliasFn(binding, alias)
    }

    override fun applyAliasVariables(
        binding: Any,
        values: DataBindingAliasValues,
    ): DataBindingVariableApplyResult = applyAliasVariablesFn(binding, values)

    override fun invalidateDataBinding(binding: Any) {
        invalidateDataBindingFn(binding)
    }

    override fun executePendingDataBindings(binding: Any) {
        executePendingDataBindingsFn(binding)
    }
}

