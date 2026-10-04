/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.BuildConfig

/**
 * Apple defines filter[equivalents] as a storefront identity mapping, not a title search:
 * https://developer.apple.com/documentation/applemusicapi/get-equivalent-ids-for-the-albums-8aky3
 * Request one source ID at a time: Android's MediaApiResponse.Meta does not retain filters.
 * A unique response can then be attributed to that source without guessing batch order.
 */
internal fun AppleInternalCatalogResolver.queryEquivalentOriginalAlbum(
    request: OriginalEntityRequest,
    lookupId: String,
    onResolved: (Alias?) -> Unit,
) {
    queryResponse(
        storefront = request.storefront,
        language = request.language,
        description = "original-album-equivalent-id=$lookupId",
        path = LocalizedEntityType.ALBUM.path,
        queryParams = equivalentAlbumQueryParams(lookupId, request.language),
    ) { response ->
        val albums = runCatching {
            response?.let { parseCatalogEntities(it, request.language, LocalizedEntityType.ALBUM) }
                .orEmpty()
        }.onFailure { ProviderLogger.error("Apple 等价专辑响应解析失败", it) }
            .getOrDefault(emptyList())
        val equivalent = uniqueOriginalAlbumEquivalent(albums, request.language)
        if (BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "Apple 原地区等价专辑: id=${request.mediaId}, lookupId=$lookupId, " +
                    "storefront=${request.storefront}, candidates=${albums.size}, " +
                    "equivalentId=${equivalent?.id}, hit=${equivalent != null}",
            )
        }
        onResolved(equivalent?.alias)
    }
}

internal fun equivalentAlbumQueryParams(lookupId: String, language: String): Map<String, String> {
    require(lookupId.isNotEmpty() && lookupId.all(Char::isDigit))
    return linkedMapOf(
        "filter[equivalents]" to lookupId,
        "l" to language,
        "platform" to "android",
        "include[albums]" to "artists",
    )
}

internal fun uniqueOriginalAlbumEquivalent(
    albums: List<CatalogSong>,
    language: String,
): CatalogSong? = albums.singleOrNull()?.takeIf { album ->
    val id = album.id
    !id.isNullOrBlank() && id.all(Char::isDigit) && album.alias.title.isNotBlank() &&
        isAcceptableOriginalAlias(album.alias, language)
}

/** Keep exact results first and hold the existing batch slot until serial fallbacks finish. */
internal fun resolveOriginalEntityBatchResults(
    requests: List<OriginalEntityRequest>,
    exact: Map<String, Alias>,
    queryEquivalentAlbum: (OriginalEntityRequest, String, (Alias?) -> Unit) -> Unit,
    onResolved: (OriginalEntityRequest, Alias?) -> Unit,
    onComplete: () -> Unit,
) {
    val missingAlbums = requests.filter { request ->
        val alias = selectExactOriginalEntityAlias(
            request.mediaId, request.lookupIds, exact, request.language,
        )
        if (alias != null || request.entityType != LocalizedEntityType.ALBUM) {
            onResolved(request, alias)
            false
        } else {
            true
        }
    }

    fun resolveNext(index: Int) {
        val request = missingAlbums.getOrNull(index) ?: return onComplete()
        val ids = (listOf(request.mediaId) + request.lookupIds)
            .map(String::trim)
            .filter { it.isNotEmpty() && it.all(Char::isDigit) }
            .distinct()
        fun queryNext(idIndex: Int) {
            val id = ids.getOrNull(idIndex)
            if (id == null) {
                onResolved(request, null)
                resolveNext(index + 1)
                return
            }
            queryEquivalentAlbum(request, id) { candidate ->
                val alias = candidate?.takeIf { isAcceptableOriginalAlias(it, request.language) }
                if (alias == null) {
                    queryNext(idIndex + 1)
                } else {
                    // Cache/bind under request.mediaId; the equivalent ID is not a playback ID.
                    onResolved(request, alias)
                    resolveNext(index + 1)
                }
            }
        }
        queryNext(0)
    }
    resolveNext(0)
}
