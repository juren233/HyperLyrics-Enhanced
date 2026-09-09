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

