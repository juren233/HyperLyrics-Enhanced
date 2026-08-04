/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

internal class InAppListenNowModelBuildState(
    val entity: WeakReference<Any>,
    val liveData: WeakReference<Any>,
    val builderKey: InAppListenNowArtworkContinuityKey?,
    initialCatalogId: String?,
    val builtAlias: AppliedMetadataAlias?,
) {
    @Volatile
    var catalogId: String? = initialCatalogId
        private set

    @Volatile
    var boundBinding: InAppListenNowBoundBinding? = null

    @Synchronized
    fun assignCatalogId(candidate: String): Boolean {
        if (catalogId != null) return false
        catalogId = candidate
        return true
    }
}

internal data class InAppListenNowBoundBinding(
    val binding: WeakReference<Any>,
    val bindGeneration: Long,
)

internal data class InAppArtworkContinuityAccessors(
    val getId: Method,
    val getPersistentId: Method,
    val getContentType: Method,
    val getArtworkToken: Method,
    val getAllArtworkTokens: Method,
    val getImageUrl: Method,
    val getImageUrls: Method,
    val setImageUrl: Method,
    val setImageUrls: Method,
    val imageUrlsLiveData: Field,
)

internal data class InAppArtworkContinuityKey(
    val id: String,
    val persistentId: Long,
    val contentType: Int,
    val artworkToken: String,
    val artworkTokens: String,
)

internal data class InAppListenNowArtworkContinuityKey(
    val id: String,
    val persistentId: Long,
    val contentType: Int,
    val artworkIdentity: String,
)

internal data class InAppListenNowArtworkIdentity(
    val id: String,
    val persistentId: Long,
    val contentType: Int,
    val allArtworkTokenCount: Int,
    val allArtworkIdentity: String,
    val fetchableArtworkToken: String,
    val artworkToken: String,
    val selectedArtworkIdentity: String,
    val key: InAppListenNowArtworkContinuityKey?,
)

internal data class InAppListenNowArtworkCacheProbe(
    val exact: InAppArtworkContinuityEntry?,
    val cacheSize: Int,
    val sameBaseArtworkHashes: List<Int>,
)

internal data class InAppListenNowSeededArtwork(
    val key: InAppListenNowArtworkContinuityKey,
    val urls: List<String>,
)

internal data class InAppArtworkContinuityEntry(
    val urls: List<String>,
    val capturedAtUptimeMillis: Long,
)
