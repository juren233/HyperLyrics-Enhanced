/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class CatalogAccess(
    val mediaApi: Any,
    val storefrontField: Field,
    val directQueryMethod: Method,
    val continuationType: Class<*>,
    val emptyCoroutineContext: Any,
)

internal data class CatalogIdentity(
    val isrc: String?,
    val fallbackAliases: List<Alias>,
    val genres: List<String>,
    val artistIds: List<String>,
)

internal data class CatalogSong(
    val id: String?,
    val alias: Alias,
    val isrc: String?,
    val genres: List<String>,
    val artistIds: List<String>,
)

internal data class CatalogArtist(
    val id: String?,
    val name: String?,
)

internal data class LocalizedRequest(
    val cacheKey: String,
    val requestKey: String,
    val mediaId: String,
    val lookupIds: List<String>,
    val entityType: LocalizedEntityType,
    val selection: Int,
    val storefront: String,
    val language: String,
    val priority: RequestPriority,
)

internal data class OriginalEntityRequest(
    val requestKey: String,
    val mediaId: String,
    val lookupIds: List<String>,
    val entityType: LocalizedEntityType,
    val language: String,
    val storefront: String,
    val directCacheKey: String,
    val priority: RequestPriority,
    val callbacks: List<(Alias?) -> Unit>,
)

data class CatalogRequestLocalization(
    val storefront: String,
    val language: String,
)

data class Alias(
    val title: String,
    val artist: String,
    val language: String,
    val album: String = "",
)

data class OriginalResolution(
    val alias: Alias?,
    val language: String?,
    val originKnown: Boolean,
    val artistIds: List<String>,
    val album: String? = null,
)

data class LocalizedLookup(
    val mediaId: String,
    val lookupIds: Collection<String>,
    val entityType: LocalizedEntityType,
)

enum class RequestPriority {
    BACKGROUND,
    ACTIVE_PAGE,
    VISIBLE,
}

enum class LocalizedEntityType(val path: String) {
    SONG("songs"),
    ALBUM("albums"),
    ARTIST("artists"),
}

