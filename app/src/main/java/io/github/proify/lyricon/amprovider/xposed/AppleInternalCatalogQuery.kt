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

internal fun AppleInternalCatalogResolver.resolveCatalogIdentity(
    mediaId: String,
    languages: List<String>,
    onResult: (CatalogIdentity) -> Unit
) {
    caches.catalogIdentityCache[mediaId]?.takeIf { isUsefulCatalogIdentity(it) }?.let {
        onResult(it)
        return
    }
    caches.catalogIdentityCache.remove(mediaId)
    val ownsRequest = dispatch.attachCatalogIdentityCallback(mediaId, onResult)
    if (!ownsRequest) return

    queryById(mediaId, null) { currentSong ->
        currentSong?.isrc?.let { isrc ->
            ProviderLogger.info("Apple 内部歌曲 ISRC: id=$mediaId, isrc=$isrc")
            finishCatalogIdentity(
                mediaId,
                CatalogIdentity(
                    isrc = isrc,
                    fallbackAliases = listOfNotNull(currentSong.alias),
                    genres = currentSong.genres,
                    artistIds = currentSong.artistIds,
                ),
            )
            return@queryById
        }

        val fallbackAliases = mutableListOf<Alias>().apply {
            currentSong?.alias?.let(::add)
        }
        val fallbackGenres = mutableListOf<String>().apply {
            currentSong?.genres?.let(::addAll)
        }
        fun queryNext(index: Int) {
            if (index >= languages.size) {
                finishCatalogIdentity(
                    mediaId,
                    CatalogIdentity(
                        isrc = null,
                        fallbackAliases = fallbackAliases,
                        genres = fallbackGenres,
                        artistIds = currentSong?.artistIds.orEmpty(),
                    ),
                )
                return
            }
            val language = languages[index]
            queryById(mediaId, language) { song ->
                song?.alias?.let(fallbackAliases::add)
                song?.genres?.let(fallbackGenres::addAll)
                val isrc = song?.isrc
                if (isrc != null) {
                    ProviderLogger.info(
                        "Apple 内部歌曲 ISRC: id=$mediaId, language=$language, isrc=$isrc"
                    )
                    finishCatalogIdentity(
                        mediaId,
                        CatalogIdentity(
                            isrc = isrc,
                            fallbackAliases = fallbackAliases,
                            genres = fallbackGenres.distinct(),
                            artistIds = (
                                currentSong?.artistIds.orEmpty() + song.artistIds
                            ).distinct(),
                        ),
                    )
                } else {
                    queryNext(index + 1)
                }
            }
        }
        queryNext(0)
    }
}

internal fun AppleInternalCatalogResolver.rememberCatalogIdentity(mediaId: String, song: CatalogSong) {
    val isrc = song.isrc ?: return
    val identity = CatalogIdentity(
        isrc = isrc,
        fallbackAliases = listOfNotNull(song.alias),
        genres = song.genres,
        artistIds = song.artistIds,
    )
    finishCatalogIdentity(mediaId, identity)
}

internal fun AppleInternalCatalogResolver.finishCatalogIdentity(mediaId: String, identity: CatalogIdentity) {
    val previous = caches.catalogIdentityCache[mediaId]
    val merged = if (previous == null) identity else {
        CatalogIdentity(
            isrc = previous.isrc ?: identity.isrc,
            fallbackAliases = (previous.fallbackAliases + identity.fallbackAliases).distinct(),
            genres = (previous.genres + identity.genres).distinct(),
            artistIds = (previous.artistIds + identity.artistIds).distinct(),
        )
    }
    val cacheable = isUsefulCatalogIdentity(merged)
    if (cacheable) {
        caches.catalogIdentityCache[mediaId] = merged
    } else {
        caches.catalogIdentityCache.remove(mediaId)
    }
    MediaMetadataCache.updateCatalogGenres(mediaId, merged.genres)
    val callbacks = dispatch.drainCatalogIdentityCallbacks(mediaId)
    if (callbacks.isNotEmpty()) {
        ProviderLogger.info(
            "Apple 内部歌曲身份已就绪: id=$mediaId, isrc=${merged.isrc}, " +
                "genres=${merged.genres}, " +
                "artistIds=${merged.artistIds}, candidates=${merged.fallbackAliases.size}, " +
                "cached=$cacheable"
        )
        callbacks.forEach { callback -> callback(merged) }
    }
}

internal fun AppleInternalCatalogResolver.isUsefulCatalogIdentity(identity: CatalogIdentity): Boolean =
    shouldCacheCatalogIdentity(identity.isrc, identity.genres)

internal fun AppleInternalCatalogResolver.queryById(
    mediaId: String,
    language: String?,
    onResult: (CatalogSong?) -> Unit
) {
    val queryParams = linkedMapOf(
        "ids" to mediaId,
        "platform" to "android",
        "include[songs]" to "artists"
    )
    language?.let { queryParams["l"] = it }
    query(
        storefront = language?.let { storefrontForLanguage(it) },
        language = language,
        description = "id=$mediaId",
        path = "songs",
        queryParams = queryParams,
        onResult = onResult
    )
}

internal fun AppleInternalCatalogResolver.queryByConfiguredRegion(
    mediaIds: List<String>,
    entityType: LocalizedEntityType,
    storefront: String,
    language: String,
    onResult: (Map<String, CatalogSong>) -> Unit,
) {
    val queryParams = linkedMapOf(
        "ids" to mediaIds.joinToString(","),
        "l" to language,
        "platform" to "android",
    )
    if (entityType != LocalizedEntityType.ARTIST) {
        queryParams["include[${entityType.path}]"] = "artists"
    }
    queryResponse(
        storefront = storefront,
        language = language,
        description = "localized-${entityType.path}-ids=${mediaIds.size}",
        path = entityType.path,
        queryParams = queryParams,
    ) { response ->
        val songs = runCatching {
            response?.let { parseCatalogEntities(it, language, entityType) }.orEmpty()
        }.onFailure { error ->
            ProviderLogger.error(
                "Apple 地区批量元数据响应解析失败: entityType=$entityType, " +
                    "ids=${mediaIds.size}, storefront=$storefront, language=$language",
                error,
            )
        }.getOrDefault(emptyList())
        val byId = songs.mapNotNull { song ->
            song.id?.let { it to song }
        }.toMap()
        byId.forEach { (id, song) -> rememberCatalogIdentity(id, song) }
        ProviderLogger.info(
            "Apple 地区批量元数据候选: entityType=$entityType, " +
                "requested=${mediaIds.size}, resolved=${byId.size}, " +
                "storefront=$storefront, language=$language"
        )
        onResult(byId)
    }
}

internal fun AppleInternalCatalogResolver.queryByIsrc(
    isrc: String,
    language: String,
    onResult: (CatalogSong?) -> Unit
) {
    val queryParams = linkedMapOf(
        "filter[isrc]" to isrc,
        "l" to language,
        "platform" to "android",
        "include[songs]" to "artists",
        "limit" to "1"
    )
    query(
        storefront = storefrontForLanguage(language),
        language = language,
        description = "isrc=$isrc",
        path = "songs",
        queryParams = queryParams,
        onResult = onResult
    )
}

internal fun AppleInternalCatalogResolver.query(
    storefront: String?,
    language: String?,
    description: String,
    path: String,
    queryParams: Map<String, String>,
    onResult: (CatalogSong?) -> Unit
) {
    queryResponse(
        storefront = storefront,
        language = language,
        description = description,
        path = path,
        queryParams = queryParams,
    ) { response ->
        val song = runCatching {
            response?.let {
                parseCatalogSong(it, language ?: CURRENT_LANGUAGE)
            }
        }.onFailure { error ->
            ProviderLogger.error(
                "Apple 内部原名响应解析失败: $description, language=$language",
                error,
            )
        }.getOrNull()
        ProviderLogger.info(
            "Apple 内部原名候选: $description, storefront=$storefront, " +
                "language=$language, value=${song?.alias?.title}/${song?.alias?.artist}, " +
                "isrc=${song?.isrc}"
        )
        onResult(song)
    }
}

internal fun AppleInternalCatalogResolver.queryResponse(
    storefront: String?,
    language: String?,
    description: String,
    path: String,
    queryParams: Map<String, String>,
    onResult: (Any?) -> Unit,
) {
    val diagnosticRequestId = catalogDiagnosticSequence.incrementAndGet().toString(36)
    val queuedAtMs = SystemClock.uptimeMillis()
    logCatalogRequestDiagnostic(
        requestId = diagnosticRequestId,
        event = "queued",
        description = description,
        storefront = storefront,
        language = language,
        elapsedMs = 0L,
    )
    mainHandler.post {
        var requestToken: String? = null
        val completed = AtomicBoolean(false)
        var slowResponse: Runnable? = null
        var timeout: Runnable? = null

        fun finish(response: Any?, event: String = "response") {
            if (!completed.compareAndSet(false, true)) {
                logCatalogRequestDiagnostic(
                    requestId = diagnosticRequestId,
                    event = "late_$event",
                    description = description,
                    storefront = storefront,
                    language = language,
                    requestToken = requestToken,
                    elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                    detail = catalogResponseDiagnostic(response),
                )
                return
            }
            slowResponse?.let(mainHandler::removeCallbacks)
            timeout?.let(mainHandler::removeCallbacks)
            requestToken?.let(pendingCatalogRequests::remove)
            logCatalogRequestDiagnostic(
                requestId = diagnosticRequestId,
                event = event,
                description = description,
                storefront = storefront,
                language = language,
                requestToken = requestToken,
                elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                detail = catalogResponseDiagnostic(response),
            )
            onResult(response)
        }

        fun fail(event: String, error: Throwable) {
            if (!completed.compareAndSet(false, true)) {
                logCatalogRequestDiagnostic(
                    requestId = diagnosticRequestId,
                    event = "late_$event",
                    description = description,
                    storefront = storefront,
                    language = language,
                    requestToken = requestToken,
                    elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                    detail = "error=${error.javaClass.name}:${error.message}",
                )
                return
            }
            slowResponse?.let(mainHandler::removeCallbacks)
            timeout?.let(mainHandler::removeCallbacks)
            requestToken?.let(pendingCatalogRequests::remove)
            ProviderLogger.error(
                "Apple 内部目录直连查询失败: $description, language=$language",
                error,
            )
            logCatalogRequestDiagnostic(
                requestId = diagnosticRequestId,
                event = event,
                description = description,
                storefront = storefront,
                language = language,
                requestToken = requestToken,
                elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                detail = "error=${error.javaClass.name}:${error.message}",
            )
            onResult(null)
        }

        runCatching {
            val access = catalogAccess ?: createCatalogAccess().also { catalogAccess = it }
            val localization = if (storefront != null && language != null) {
                CatalogRequestLocalization(storefront, language)
            } else {
                null
            }
            if (localization != null) {
                captureAccountStorefront(access)
                requestToken = catalogRequestSequence.incrementAndGet().toString(36)
                pendingCatalogRequests[requestToken] = localization
            }
            val directQueryParams = LinkedHashMap(queryParams)
            requestToken?.let { token ->
                directQueryParams[CATALOG_REQUEST_TOKEN_PARAM] = token
            }
            slowResponse = Runnable {
                if (completed.get()) return@Runnable
                logCatalogRequestDiagnostic(
                    requestId = diagnosticRequestId,
                    event = "slow_response",
                    description = description,
                    storefront = storefront,
                    language = language,
                    requestToken = requestToken,
                    elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                    detail = "mode=direct-network, slowMs=$QUERY_SLOW_RESPONSE_MS",
                )
            }.also { mainHandler.postDelayed(it, QUERY_SLOW_RESPONSE_MS) }
            timeout = Runnable {
                if (!completed.compareAndSet(false, true)) return@Runnable
                requestToken?.let(pendingCatalogRequests::remove)
                logCatalogRequestDiagnostic(
                    requestId = diagnosticRequestId,
                    event = "timeout",
                    description = description,
                    storefront = storefront,
                    language = language,
                    requestToken = requestToken,
                    elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                    detail = "mode=direct-network, timeoutMs=$QUERY_TIMEOUT_MS",
                )
                onResult(null)
            }.also { mainHandler.postDelayed(it, QUERY_TIMEOUT_MS) }

            val continuation = createDirectCatalogContinuation(
                access = access,
                onSuccess = { response -> finish(response) },
                onFailure = { error -> fail("request_failed", error) },
            )
            val previousStorefront = access.storefrontField.get(access.mediaApi) as? String
            val directResult = try {
                activeCatalogRequest.set(localization)
                localization?.let {
                    access.storefrontField.set(access.mediaApi, it.storefront)
                }
                access.directQueryMethod.invoke(
                    access.mediaApi,
                    path,
                    directQueryParams,
                    continuation,
                )
            } finally {
                activeCatalogRequest.remove()
                if (localization != null) {
                    access.storefrontField.set(access.mediaApi, previousStorefront)
                }
            }
            logCatalogRequestDiagnostic(
                requestId = diagnosticRequestId,
                event = "observing",
                description = description,
                storefront = storefront,
                language = language,
                requestToken = requestToken,
                elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                detail = "mode=direct-network, path=$path, " +
                    "suspended=${isCoroutineSuspended(directResult)}",
            )
            if (!isCoroutineSuspended(directResult)) {
                finish(directResult)
            }
        }.onFailure { error ->
            activeCatalogRequest.remove()
            fail("start_failed", error)
        }
    }
}

internal fun AppleInternalCatalogResolver.createDirectCatalogContinuation(
    access: CatalogAccess,
    onSuccess: (Any?) -> Unit,
    onFailure: (Throwable) -> Unit,
): Any = Proxy.newProxyInstance(
    access.continuationType.classLoader ?: classLoader,
    arrayOf(access.continuationType),
) { proxy, method, args ->
    when (method.name) {
        "getContext" -> access.emptyCoroutineContext
        "resumeWith" -> {
            val result = args?.firstOrNull()
            mainHandler.post {
                val failure = coroutineResultFailure(result)
                if (failure != null) onFailure(failure) else onSuccess(result)
            }
            null
        }
        "equals" -> proxy === args?.firstOrNull()
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "AppleCatalogContinuation"
        else -> null
    }
}

internal fun AppleInternalCatalogResolver.coroutineResultFailure(result: Any?): Throwable? {
    if (result is Throwable) return result
    val value = result ?: return null
    val fields = value.javaClass.declaredFields.filterNot { field ->
        Modifier.isStatic(field.modifiers)
    }
    if (fields.size != 1) return null
    val field = fields.single()
    if (!Throwable::class.java.isAssignableFrom(field.type)) return null
    field.isAccessible = true
    return field.get(value) as? Throwable
}
