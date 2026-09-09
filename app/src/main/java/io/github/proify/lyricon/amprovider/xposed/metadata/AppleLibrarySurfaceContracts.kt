/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.os.SystemClock
import android.view.Choreographer
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

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

