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

