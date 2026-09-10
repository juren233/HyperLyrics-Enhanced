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

internal fun AppleInternalCatalogResolver.invalidateOriginalEntity(mediaId: String, entityType: LocalizedEntityType) {
    persistentOriginalCache.remove(originalDirectEntityCacheKey(entityType, mediaId))
}

internal fun AppleInternalCatalogResolver.enqueueOriginalEntityRequest(request: OriginalEntityRequest) {
    val prioritized = request.copy(
        priority = dispatch.currentRequestPriority(request.mediaId, request.priority),
    )
    if (dispatch.submitOriginalEntityRequest(prioritized)) {
        mainHandler.postDelayed(::processOriginalEntityBatch, ORIGINAL_ENTITY_BATCH_DELAY_MS)
    }
}

internal fun AppleInternalCatalogResolver.processOriginalEntityBatch() {
    val batch = dispatch.takeOriginalEntityBatch()
    if (batch.isEmpty()) return
    val first = batch.first()

    queryByConfiguredRegion(
        mediaIds = batch.flatMap(OriginalEntityRequest::lookupIds).distinct(),
        entityType = first.entityType,
        storefront = first.storefront,
        language = first.language,
    ) { resolved ->
        batch.forEach { request ->
            val alias = selectExactOriginalEntityAlias(
                mediaId = request.mediaId,
                lookupIds = request.lookupIds,
                resolved = resolved.mapValues { it.value.alias },
                sourceLanguage = request.language,
            )
            if (alias != null) {
                persistentOriginalCache.put(request.directCacheKey, alias)
            }
            ProviderLogger.info(
                "Apple 原地区实体查询完成: id=${request.mediaId}, " +
                    "entityType=${request.entityType}, language=${request.language}, " +
                    "batch=${batch.size}, priority=${request.priority}, hit=${alias != null}, " +
                    "value=${alias?.title}/${alias?.artist}/${alias?.album}"
            )
            request.callbacks.forEach { callback -> callback(alias) }
        }
        dispatch.endOriginalEntityBatch(first.priority)
        scheduleOriginalEntityBatchIfCapacity()
    }
    scheduleOriginalEntityBatchIfCapacity()
}

internal fun AppleInternalCatalogResolver.scheduleOriginalEntityBatchIfCapacity() {
    if (dispatch.shouldScheduleOriginalEntityBatch()) mainHandler.post(::processOriginalEntityBatch)
}


internal fun AppleInternalCatalogResolver.resolveForContentUiLanguage(
    mediaId: String,
    selection: Int,
    priority: RequestPriority = RequestPriority.ACTIVE_PAGE,
    onResolved: (Alias?) -> Unit,
) = resolveForContentUiLanguage(
    mediaId = mediaId,
    lookupIds = listOf(mediaId),
    entityType = LocalizedEntityType.SONG,
    selection = selection,
    priority = priority,
    onResolved = onResolved,
)

internal fun AppleInternalCatalogResolver.resolveForContentUiLanguage(
    mediaId: String,
    lookupIds: Collection<String>,
    entityType: LocalizedEntityType,
    selection: Int,
    priority: RequestPriority = RequestPriority.ACTIVE_PAGE,
    onResolved: (Alias?) -> Unit,
) {
    resolveManyForContentUiLanguage(
        lookups = listOf(LocalizedLookup(mediaId, lookupIds, entityType)),
        selection = selection,
        priority = priority,
    ) { resolvedId, alias ->
        if (resolvedId == mediaId) onResolved(alias)
    }
}

internal fun AppleInternalCatalogResolver.resolveManyForContentUiLanguage(
    lookups: Collection<LocalizedLookup>,
    selection: Int,
    priority: RequestPriority = RequestPriority.ACTIVE_PAGE,
    onResolved: (mediaId: String, alias: Alias?) -> Unit,
) {
    val storefront = storefrontForContentUiLanguage(selection)
    val languages = languageTagsForContentUiLanguage(selection)
    if (storefront == null || languages.isEmpty()) {
        lookups.forEach { onResolved(it.mediaId, null) }
        return
    }

    val invalidLookups = lookups.filterNot { lookup ->
        lookup.mediaId.trim().let { it.isNotEmpty() && it.all(Char::isDigit) }
    }
    invalidLookups.forEach { onResolved(it.mediaId, null) }
    val validLookups = lookups.filterNot { lookup -> lookup in invalidLookups }
    if (validLookups.isEmpty()) return
    if (languages.size == 1) {
        resolveManyForContentUiLanguageSingleLanguage(
            lookups = validLookups,
            selection = selection,
            priority = priority,
            storefront = storefront,
            language = languages.first(),
            onResolved = onResolved,
        )
        return
    }

    val resolvedKeys = mutableSetOf<String>()
    fun lookupKey(mediaId: String, entityType: LocalizedEntityType): String =
        "${entityType.name}:${mediaId.trim()}"

    fun resolveLanguage(index: Int, pending: List<LocalizedLookup>) {
        if (pending.isEmpty()) return
        if (index >= languages.size) {
            pending.forEach { lookup ->
                if (resolvedKeys.add(lookupKey(lookup.mediaId, lookup.entityType))) {
                    onResolved(lookup.mediaId, null)
                }
            }
            return
        }
        val language = languages[index]
        resolveManyForContentUiLanguageSingleLanguage(
            lookups = pending,
            selection = selection,
            priority = priority,
            storefront = storefront,
            language = language,
            onResolved = { mediaId, alias ->
                if (alias != null) {
                    pending
                        .filter { it.mediaId.trim() == mediaId.trim() }
                        .forEach { lookup ->
                            if (resolvedKeys.add(lookupKey(mediaId, lookup.entityType))) {
                                onResolved(mediaId, alias)
                            }
                        }
                }
            },
            onComplete = {
                val unresolved = pending.filter { lookup ->
                    lookupKey(lookup.mediaId, lookup.entityType) !in resolvedKeys
                }
                resolveLanguage(index + 1, unresolved)
            },
        )
    }
    resolveLanguage(0, validLookups)
}

internal fun AppleInternalCatalogResolver.resolveManyForContentUiLanguageSingleLanguage(
    lookups: Collection<LocalizedLookup>,
    selection: Int,
    priority: RequestPriority,
    storefront: String,
    language: String,
    onResolved: (mediaId: String, alias: Alias?) -> Unit,
    onComplete: () -> Unit = {},
) {
    val requests = lookups.asSequence()
        .mapNotNull { lookup ->
            val mediaId = lookup.mediaId.trim()
            if (mediaId.isEmpty() || !mediaId.all(Char::isDigit)) return@mapNotNull null
            val normalizedLookupIds = sequenceOf(mediaId)
                .plus(lookup.lookupIds.asSequence())
                .map(String::trim)
                .filter { it.isNotEmpty() && it.all(Char::isDigit) }
                .distinct()
                .take(LOCALIZED_BATCH_SIZE)
                .toList()
            val cacheKey = localizedMetadataCacheKey(
                selection,
                lookup.entityType,
                mediaId,
                language,
            )
            dispatch.rememberRequestPriority(mediaId, priority)
            LocalizedRequest(
                cacheKey = cacheKey,
                requestKey = "$cacheKey:${normalizedLookupIds.joinToString(",")}".trim(),
                mediaId = mediaId,
                lookupIds = normalizedLookupIds,
                entityType = lookup.entityType,
                selection = selection,
                storefront = storefront,
                language = language,
                priority = dispatch.currentRequestPriority(mediaId, priority),
            )
        }
        .distinctBy(LocalizedRequest::requestKey)
        .toList()
    if (requests.isEmpty()) {
        onComplete()
        return
    }

    val requestCompletionCount = AtomicLong(requests.size.toLong())
    val complete: (LocalizedRequest, Alias?) -> Unit = { request, alias ->
        onResolved(request.mediaId, alias)
        if (requestCompletionCount.decrementAndGet() == 0L) onComplete()
    }
    val uncached = mutableListOf<LocalizedRequest>()
    requests.forEach { request ->
        val cached = caches.localizedAlias(request.cacheKey)
        if (cached != null) {
            complete(request, cached)
            return@forEach
        }
        val ownsRequest = dispatch.attachLocalizedCallback(
            requestKey = request.requestKey,
            callback = { alias -> complete(request, alias) },
            // 合并分支必须在 in-flight 车道锁内完成优先级提升，保持原锁嵌套。
            onMerged = { promotePendingRequests(listOf(request.mediaId), request.priority) },
        )
        if (ownsRequest) uncached += request
    }
    if (uncached.isEmpty()) return
    persistentLocalizedCache.getMany(uncached.map(LocalizedRequest::cacheKey)) { cached ->
        uncached.forEach { request ->
            val alias = cached[request.cacheKey]
            if (alias != null) finishLocalizedCacheHit(request, alias)
            else enqueueLocalizedRequest(request)
        }
    }
}

internal fun AppleInternalCatalogResolver.finishLocalizedCacheHit(request: LocalizedRequest, alias: Alias) {
    caches.putLocalizedAlias(request.cacheKey, alias)
    val callbacks = dispatch.drainLocalizedCallbacks(request.requestKey)
    ProviderLogger.info(
        "Apple 地区元数据持久缓存命中: id=${request.mediaId}, " +
            "entityType=${request.entityType}, selection=${request.selection}"
    )
    callbacks.forEach { callback -> callback(alias) }
}

internal fun AppleInternalCatalogResolver.enqueueLocalizedRequest(request: LocalizedRequest) {
    val prioritized = request.copy(
        priority = dispatch.currentRequestPriority(request.mediaId, request.priority),
    )
    if (dispatch.submitLocalizedRequest(prioritized)) {
        mainHandler.postDelayed(::processLocalizedBatch, LOCALIZED_BATCH_DELAY_MS)
    }
}

internal fun AppleInternalCatalogResolver.processLocalizedBatch() {
    val batch = dispatch.takeLocalizedBatch()
    if (batch.isEmpty()) return

    queryByConfiguredRegion(
        mediaIds = batch.flatMap(LocalizedRequest::lookupIds).distinct(),
        entityType = batch.first().entityType,
        storefront = batch.first().storefront,
        language = batch.first().language,
    ) { songs ->
        batch.forEach { request ->
            val resolvedEntry = request.lookupIds.firstNotNullOfOrNull { lookupId ->
                songs[lookupId]?.let { lookupId to it }
            }
            val alias = resolvedEntry?.second?.alias?.takeIf {
                it.title.isNotBlank() || it.artist.isNotBlank()
            }
            finishLocalizedRequest(request, resolvedEntry?.first, alias)
        }
        dispatch.endLocalizedBatch(batch.first().priority)
        scheduleLocalizedBatchIfCapacity()
    }
    scheduleLocalizedBatchIfCapacity()
}

internal fun AppleInternalCatalogResolver.scheduleLocalizedBatchIfCapacity() {
    if (dispatch.shouldScheduleLocalizedBatch()) mainHandler.post(::processLocalizedBatch)
}

internal fun AppleInternalCatalogResolver.promotePendingRequests(
    mediaIds: Collection<String>,
    priority: RequestPriority,
) {
    val normalizedIds = mediaIds.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()
    if (normalizedIds.isEmpty()) return
    if (dispatch.isRequestScopeActive()) {
        val scopedPriorities = normalizedIds.associateWith(dispatch::currentScopedPriority)
        dispatch.updatePendingRequestPriorities(
            scopedPriorities = scopedPriorities,
            onlyMediaIds = normalizedIds,
        )
        return
    }
    if (priority == RequestPriority.BACKGROUND) return
    normalizedIds.forEach { mediaId -> dispatch.rememberRequestPriority(mediaId, priority) }
    val promoted = dispatch.promotePendingRequests(normalizedIds, priority)
    if (BuildConfig.DEBUG && (promoted.localized > 0 || promoted.original > 0)) {
        ProviderLogger.info(
            "Apple 元数据请求优先级提升: priority=$priority, ids=$normalizedIds, " +
                "localized=${promoted.localized}, original=${promoted.original}"
        )
    }
    scheduleLocalizedBatchIfCapacity()
    scheduleOriginalEntityBatchIfCapacity()
}

internal fun AppleInternalCatalogResolver.updateRequestScope(
    revision: Long,
    visibleMediaIds: Collection<String>,
    activePageMediaIds: Collection<String>,
) {
    val visible = normalizeRequestScopeIds(visibleMediaIds)
    val activePage = normalizeRequestScopeIds(activePageMediaIds) - visible
    if (!dispatch.applyRequestScope(revision, visible, activePage)) return
    val scopedPriorities = (visible + activePage).associateWith { mediaId ->
        priorityForRequestScope(mediaId, visible, activePage)
    }
    val changed = dispatch.updatePendingRequestPriorities(scopedPriorities)
    if (BuildConfig.DEBUG && changed > 0) {
        ProviderLogger.info(
            "Apple 元数据请求作用域同步: revision=$revision, " +
                "visible=${visible.size}, page=${activePage.size}, changed=$changed"
        )
    }
    scheduleLocalizedBatchIfCapacity()
    scheduleOriginalEntityBatchIfCapacity()
}

internal fun AppleInternalCatalogResolver.finishLocalizedRequest(
    request: LocalizedRequest,
    resolvedLookupId: String?,
    alias: Alias?,
) {
    if (alias != null) {
        caches.putLocalizedAlias(request.cacheKey, alias)
        persistentLocalizedCache.put(request.cacheKey, alias)
    }
    val callbacks = dispatch.drainLocalizedCallbacks(request.requestKey)
    ProviderLogger.info(
            "Apple 播放元数据地区查询完成: id=${request.mediaId}, " +
            "lookupIds=${request.lookupIds}, resolvedBy=$resolvedLookupId, " +
            "entityType=${request.entityType}, " +
            "selection=${request.selection}, storefront=${request.storefront}, " +
            "language=${request.language}, priority=${request.priority}, " +
            "value=${alias?.title}/${alias?.artist}"
    )
    callbacks.forEach { callback -> callback(alias) }
}

internal fun AppleInternalCatalogResolver.finishResolve(
    metadata: MediaMetadataCache.Metadata,
    languages: List<String>,
    results: List<Alias>,
    originKnown: Boolean,
    artistIds: List<String>,
) {
    val canonicalLanguages = languages.map(::canonicalOriginalLanguage).distinct()
    val sourceLanguage = canonicalLanguages.singleOrNull()
    val acceptableResults = regionalOriginalAliases(results, canonicalLanguages)
    val selected = selectOriginalAlias(
        variants = acceptableResults,
        localizedTitle = metadata.title.orEmpty(),
        localizedArtist = metadata.artist.orEmpty()
    )
    val confirmedRegionalAlias = if (originKnown) {
        acceptableResults.lastOrNull { alias ->
            canonicalOriginalLanguage(alias.language) in canonicalLanguages &&
                isConfidentOriginalSongAlias(
                    alias = alias,
                    localizedTitle = metadata.title.orEmpty(),
                    localizedArtist = metadata.artist.orEmpty(),
                )
        }
    } else {
        null
    }
    val originalAlias = selected ?: confirmedRegionalAlias
    if (originalAlias != null) {
        caches.putOriginalSongAlias(metadata.id, originalAlias)
        persistentOriginalCache.put(originalSongCacheKey(metadata.id), originalAlias)
    }
    val resolvedAlbum = originalAlbumFromResolution(
        alias = originalAlias,
        acceptableResults = acceptableResults,
    )
    dispatch.discardOriginalCandidates(metadata.id)
    val callbacks = dispatch.drainOriginalSongCallbacks(metadata.id)
    ProviderLogger.info(
        "Apple 内部原名查询完成: id=${metadata.id}, genre=${metadata.genre}, " +
            "languages=$canonicalLanguages, selected=${originalAlias?.title}/${originalAlias?.artist}"
    )
    val resolution = OriginalResolution(
        alias = originalAlias,
        language = originalAlias?.language?.takeIf(String::isNotBlank)
            ?: sourceLanguage,
        originKnown = originKnown,
        artistIds = artistIds,
        album = resolvedAlbum,
    )
    callbacks.forEach { callback -> callback(resolution) }
}

internal fun AppleInternalCatalogResolver.finishCachedOriginalResolve(mediaId: String, alias: Alias) {
    dispatch.discardOriginalCandidates(mediaId)
    val callbacks = dispatch.drainOriginalSongCallbacks(mediaId)
    ProviderLogger.info(
        "Apple 原地区元数据缓存命中: id=$mediaId, language=${alias.language}"
    )
    val resolution = OriginalResolution(
        alias = alias,
        language = alias.language.takeIf(String::isNotBlank),
        originKnown = true,
        artistIds = emptyList(),
        album = alias.album,
    )
    callbacks.forEach { callback -> callback(resolution) }
}

internal fun AppleInternalCatalogResolver.registerOriginalCandidateCallback(
    mediaId: String,
    callback: (Alias) -> Unit,
) {
    dispatch.addOriginalCandidateCallback(mediaId, callback)
    caches.catalogIdentity(mediaId)
        ?.fallbackAliases
        ?.firstOrNull()
        ?.let { alias -> dispatch.publishOriginalCandidate(mediaId, alias) }
}



internal const val CURRENT_LANGUAGE = "current"
internal const val CACHE_SIZE = 64
internal const val LOCALIZED_CACHE_SIZE = 4_096
internal const val LOCALIZED_ARTIST_ALIAS_CACHE_SIZE = 2_048
internal const val REQUEST_PRIORITY_CACHE_SIZE = 2_048
internal const val ORIGINAL_METADATA_CACHE_SCHEMA = "V2"

internal fun isCoroutineSuspended(value: Any?): Boolean =
    value is Enum<*> && value.name == "COROUTINE_SUSPENDED"

internal fun originalSongCacheKey(mediaId: String): String =
    "$ORIGINAL_METADATA_CACHE_SCHEMA:VERIFIED_SONG:${mediaId.trim()}"

internal fun originalDirectEntityCacheKey(
    entityType: LocalizedEntityType,
    mediaId: String,
): String = when (entityType) {
    LocalizedEntityType.SONG ->
        "$ORIGINAL_METADATA_CACHE_SCHEMA:ENTITY_SONG:${mediaId.trim()}"
    else ->
        "$ORIGINAL_METADATA_CACHE_SCHEMA:$entityType:${mediaId.trim()}"
}

internal fun legacyAmbiguousSongCacheKey(mediaId: String): String =
    "SONG:${mediaId.trim()}"

internal fun originalEntityCacheKey(
    entityType: LocalizedEntityType,
    language: String,
    mediaId: String,
): String =
    "$ORIGINAL_METADATA_CACHE_SCHEMA:$entityType:${language.trim()}:${mediaId.trim()}"

/**
 * Direct keys are always tried first. The remaining keys cover aliases written before
 * direct entity keys were introduced and equivalent catalog IDs collected from the same
 * Media API object.
 */
internal fun originalEntityCacheLookupKeys(
    entityType: LocalizedEntityType,
    mediaId: String,
    lookupIds: Collection<String> = emptyList(),
    languages: Collection<String> = ORIGINAL_LANGUAGE_PROBE_ORDER,
): List<String> {
    val ids = sequenceOf(mediaId)
        .plus(lookupIds.asSequence())
        .map(String::trim)
        .filter { it.isNotEmpty() && it.all(Char::isDigit) }
        .distinct()
        .toList()
    if (ids.isEmpty()) return emptyList()
    val directKeys = ids.map { id ->
        originalDirectEntityCacheKey(entityType, id)
    }
    val legacyKeys = ids.flatMap { id ->
        languages.flatMap { language ->
            originalLanguageCacheKeyVariants(language).map { variant ->
                originalEntityCacheKey(entityType, variant, id)
            }
        }
    }
    return (directKeys + legacyKeys).distinct()
}

internal fun localizedMetadataCacheKey(
    selection: Int,
    entityType: LocalizedEntityType,
    mediaId: String,
    language: String? = null,
): String {
    val normalizedLanguage = language
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.replace('_', '-')
        ?.lowercase()
    return if (normalizedLanguage == null) {
        "$selection:$entityType:${mediaId.trim()}"
    } else {
        "$selection:$entityType:$normalizedLanguage:${mediaId.trim()}"
    }
}

internal fun isLocalizedArtistAliasCacheKey(key: String): Boolean =
    ":ARTIST_ALIAS:" in key

internal const val LOCALIZED_BATCH_SIZE = 50
internal const val MAX_LOCALIZED_BATCHES_RUNNING = 4
internal const val MAX_BACKGROUND_LOCALIZED_BATCHES_RUNNING = 2
internal const val LOCALIZED_BATCH_DELAY_MS = 32L
internal const val ORIGINAL_ENTITY_BATCH_SIZE = 50
internal const val MAX_ORIGINAL_ENTITY_BATCHES_RUNNING = 3
internal const val MAX_BACKGROUND_ORIGINAL_ENTITY_BATCHES_RUNNING = 2
internal const val ORIGINAL_ENTITY_BATCH_DELAY_MS = 32L
internal const val QUERY_SLOW_RESPONSE_MS = 6_000L
internal const val QUERY_TIMEOUT_MS = 30_000L
internal const val ARTIST_ALIAS_CACHE_SCHEMA = "V2"
internal const val CATALOG_REQUEST_TOKEN_PARAM = "hle_catalog_request"
internal val ORIGINAL_LANGUAGE_PROBE_ORDER = listOf(
    "ja-JP",
    "ko-KR",
    "zh-Hans-CN",
    "th-TH",
    "ru-RU",
    "uk-UA",
    "ar-SA",
    "he-IL",
    "hi-IN",
    "el-GR",
    "bg-BG",
)

internal fun originalLanguageCacheKeyVariants(language: String): List<String> = when (
    canonicalOriginalLanguage(language)
) {
    "zh-Hans-CN" -> listOf("zh-Hans-CN", "zh-CN", "zh-cn", "zh-hans-cn")
    else -> listOf(language, language.lowercase())
}.distinct()

internal fun selectNextRequestIndex(
    priorities: List<RequestPriority>,
): Int? {
    var selectedIndex: Int? = null
    var selectedPriority = RequestPriority.BACKGROUND
    priorities.forEachIndexed { index, priority ->
        if (selectedIndex == null || priority.ordinal > selectedPriority.ordinal) {
            selectedIndex = index
            selectedPriority = priority
        }
    }
    return selectedIndex
}

internal fun canStartRequest(
    priority: RequestPriority,
    totalRunning: Int,
    backgroundRunning: Int,
    maxRunning: Int,
    maxBackgroundRunning: Int,
): Boolean = totalRunning < maxRunning &&
    (
        priority != RequestPriority.BACKGROUND ||
            backgroundRunning < maxBackgroundRunning
        )

internal fun higherPriority(
    first: RequestPriority,
    second: RequestPriority,
): RequestPriority = if (first.ordinal >= second.ordinal) first else second

internal fun priorityForRequestScope(
    mediaId: String,
    visibleMediaIds: Set<String>,
    activePageMediaIds: Set<String>,
): RequestPriority = when (mediaId.trim()) {
    in visibleMediaIds -> RequestPriority.VISIBLE
    in activePageMediaIds -> RequestPriority.ACTIVE_PAGE
    else -> RequestPriority.BACKGROUND
}

internal fun normalizeRequestScopeIds(mediaIds: Collection<String>): Set<String> =
    mediaIds.asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && it.all(Char::isDigit) }
        .toSet()

internal fun languageTagsForGenre(genre: String?): List<String> {
    return knownLanguageTagsForGenre(genre).ifEmpty {
        listOf("ja-JP", "ko-KR", "zh-Hans-CN")
    }
}

internal fun knownLanguageTagsForGenre(genre: String?): List<String> {
    val normalized = genre.orEmpty().trim().lowercase()
    return when {
        "j-pop" in normalized || "japanese" in normalized ||
            "日本流行" in normalized || "日语流行" in normalized ||
            "日語流行" in normalized -> listOf("ja-JP")
        "k-pop" in normalized || "korean" in normalized ||
            "韩国流行" in normalized || "韓國流行" in normalized ||
            "韩语流行" in normalized || "韓語流行" in normalized -> listOf("ko-KR")
        "mandopop" in normalized || "chinese" in normalized ||
            "国语流行" in normalized || "國語流行" in normalized ||
            "华语流行" in normalized || "華語流行" in normalized ||
            "中文流行" in normalized ->
            listOf("zh-Hans-CN")
        "cantopop" in normalized || "hong kong" in normalized ||
            "粤语流行" in normalized || "粵語流行" in normalized ->
            listOf("zh-Hans-CN")
        "thai" in normalized -> listOf("th-TH")
        "russian" in normalized -> listOf("ru-RU")
        "ukrain" in normalized -> listOf("uk-UA")
        "arab" in normalized -> listOf("ar-SA")
        "israel" in normalized || "hebrew" in normalized -> listOf("he-IL")
        "indian" in normalized || "bollywood" in normalized -> listOf("hi-IN")
        "greek" in normalized -> listOf("el-GR")
        "bulgar" in normalized -> listOf("bg-BG")
        else -> emptyList()
    }
}

internal fun languageTagsForIsrc(isrc: String?): List<String> {
    val country = isrc.orEmpty().trim().take(2).uppercase()
    return when (country) {
        "JP" -> listOf("ja-JP")
        "KR" -> listOf("ko-KR")
        "CN", "HK", "MO", "TW" -> listOf("zh-Hans-CN")
        else -> emptyList()
    }
}

internal fun languageTagsForOriginalMetadata(
    genre: String?,
    isrc: String?,
): List<String> = languageTagsForOriginalMetadata(
    genre = genre,
    catalogGenres = emptyList(),
    isrc = isrc,
)

internal fun languageTagsForOriginalMetadata(
    genre: String?,
    catalogGenres: Collection<String>,
    isrc: String?,
): List<String> {
    val genreLanguages = sequenceOf(genre)
        .plus(catalogGenres.asSequence())
        .filterNotNull()
        .map(::knownLanguageTagsForGenre)
        .firstOrNull(List<String>::isNotEmpty)
        .orEmpty()
    return genreLanguages.ifEmpty { languageTagsForIsrc(isrc) }
}

