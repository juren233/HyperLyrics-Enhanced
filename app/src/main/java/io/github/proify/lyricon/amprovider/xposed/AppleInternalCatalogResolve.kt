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

internal fun AppleInternalCatalogResolver.resolve(metadata: MediaMetadataCache.Metadata, onResolved: (Alias?) -> Unit) {
    resolveOriginalMetadata(metadata, RequestPriority.ACTIVE_PAGE) { resolution ->
        onResolved(resolution.alias)
    }
}

internal fun AppleInternalCatalogResolver.resolveOriginalMetadata(
    metadata: MediaMetadataCache.Metadata,
    priority: RequestPriority = RequestPriority.ACTIVE_PAGE,
    onResolved: (OriginalResolution) -> Unit,
) = resolveOriginalMetadata(
    metadata = metadata,
    onCandidate = null,
    priority = priority,
    onResolved = onResolved,
)

internal fun AppleInternalCatalogResolver.resolveOriginalMetadata(
    metadata: MediaMetadataCache.Metadata,
    onCandidate: ((Alias) -> Unit)?,
    priority: RequestPriority = RequestPriority.ACTIVE_PAGE,
    onResolved: (OriginalResolution) -> Unit,
) {
    rememberRequestPriority(metadata.id, priority)
    if (!shouldResolve(metadata)) {
        onResolved(
            OriginalResolution(
                alias = null,
                language = null,
                originKnown = false,
                artistIds = emptyList(),
            )
        )
        return
    }
    synchronized(cache) {
        cache[metadata.id]?.let { alias ->
            canonicalCachedOriginalAlias(alias)?.takeIf { cachedAlias ->
                isReusableOriginalSongAlias(
                    alias = cachedAlias,
                    localizedTitle = metadata.title.orEmpty(),
                    localizedArtist = metadata.artist.orEmpty(),
                )
            }?.let { cachedAlias ->
                if (cachedAlias != alias) cache[metadata.id] = cachedAlias
                onResolved(
                    OriginalResolution(
                        alias = cachedAlias,
                        language = cachedAlias.language.takeIf(String::isNotBlank),
                        originKnown = true,
                        artistIds = emptyList(),
                        album = cachedAlias.album,
                    )
                )
                return
            }
            cache.remove(metadata.id)
        }
    }
    onCandidate?.let { callback ->
        registerOriginalCandidateCallback(metadata.id, callback)
    }
    synchronized(inFlight) {
        val callbacks = inFlight[metadata.id]
        if (callbacks != null) {
            callbacks.add(onResolved)
            return
        }
        inFlight[metadata.id] = mutableListOf(onResolved)
    }

    persistentOriginalCache.get(originalSongCacheKey(metadata.id)) { persistentAlias ->
        val reusableAlias = persistentAlias?.takeIf { alias ->
            isReusableOriginalSongAlias(
                alias = alias,
                localizedTitle = metadata.title.orEmpty(),
                localizedArtist = metadata.artist.orEmpty(),
            )
        }
        if (reusableAlias != null) {
            synchronized(cache) { cache[metadata.id] = reusableAlias }
            finishCachedOriginalResolve(metadata.id, reusableAlias)
        } else {
            if (persistentAlias != null) {
                persistentOriginalCache.remove(originalSongCacheKey(metadata.id))
            }
            persistentOriginalCache.remove(legacyAmbiguousSongCacheKey(metadata.id))
            resolveOriginalMetadataFromCatalog(metadata, priority = priority)
        }
    }
}

internal fun AppleInternalCatalogResolver.resolveOriginalMetadataFromCatalog(
    metadata: MediaMetadataCache.Metadata,
    allowEmptyIdentityRetry: Boolean = true,
    priority: RequestPriority = RequestPriority.ACTIVE_PAGE,
) {
    val fallbackLanguages = if (AppleOriginalMetadataPolicy.isCjkGenre(metadata.genre)) {
        languageTagsForGenre(metadata.genre)
    } else {
        emptyList()
    }
    resolveCatalogIdentity(metadata.id, fallbackLanguages) { identity ->
        identity.fallbackAliases.firstOrNull()?.let { alias ->
            publishOriginalCandidate(metadata.id, alias)
        }
        if (allowEmptyIdentityRetry && shouldRetryEmptyCatalogIdentity(
                mediaId = metadata.id,
                title = metadata.title,
                artist = metadata.artist,
                genre = metadata.genre,
                isrc = identity.isrc,
                catalogGenres = identity.genres,
            )
        ) {
            ProviderLogger.info(
                "Apple 内部歌曲空身份重试: id=${metadata.id}, " +
                    "title=${metadata.title}, artist=${metadata.artist}"
            )
            mainHandler.post {
                resolveOriginalMetadataFromCatalog(
                    metadata = metadata,
                    allowEmptyIdentityRetry = false,
                    priority = currentRequestPriority(metadata.id, priority),
                )
            }
            return@resolveCatalogIdentity
        }
        val results = identity.fallbackAliases.toMutableList()
        val isrc = identity.isrc
        val languages = languageTagsForOriginalMetadata(
            genre = metadata.genre,
            catalogGenres = identity.genres,
            isrc = isrc,
        )
        if (languages.isEmpty()) {
            finishResolve(
                metadata = metadata,
                languages = languages,
                results = results,
                originKnown = isrc != null,
                artistIds = identity.artistIds,
            )
            return@resolveCatalogIdentity
        }
        fun queryNext(index: Int) {
            if (index >= languages.size) {
                finishResolve(
                    metadata = metadata,
                    languages = languages,
                    results = results,
                    originKnown = isrc != null,
                    artistIds = identity.artistIds,
                )
                return
            }
            val language = languages[index]
            selectExactIdentityAlias(identity.fallbackAliases, language)?.let { exactAlias ->
                finishResolve(
                    metadata = metadata,
                    languages = listOf(language),
                    results = listOf(exactAlias),
                    originKnown = true,
                    artistIds = identity.artistIds,
                )
                return
            }
            resolveOriginalEntityForLanguage(
                mediaId = metadata.id,
                lookupIds = listOf(metadata.id),
                entityType = LocalizedEntityType.SONG,
                language = language,
                priority = currentRequestPriority(metadata.id, priority),
            ) { resolvedAlias ->
                val exactAlias = resolvedAlias?.takeIf { alias ->
                    (alias.title.isNotBlank() || alias.artist.isNotBlank()) &&
                        isConfidentOriginalSongAlias(
                            alias = alias,
                            localizedTitle = metadata.title.orEmpty(),
                            localizedArtist = metadata.artist.orEmpty(),
                        )
                }
                if (exactAlias != null) {
                    val regionalArtistIds =
                        catalogIdentityCache[metadata.id]?.artistIds.orEmpty()
                    finishResolve(
                        metadata = metadata,
                        languages = listOf(language),
                        results = listOf(exactAlias),
                        originKnown = true,
                        artistIds = (
                            identity.artistIds + regionalArtistIds
                        ).distinct(),
                    )
                } else {
                    if (resolvedAlias != null) {
                        invalidateOriginalEntity(metadata.id, LocalizedEntityType.SONG)
                    }
                    if (isrc == null) {
                        queryNext(index + 1)
                    } else {
                        queryByIsrc(isrc, language) { song ->
                            song?.alias?.let(results::add)
                            queryNext(index + 1)
                        }
                    }
                }
            }
        }
        queryNext(0)
    }
}

internal fun AppleInternalCatalogResolver.resolveOriginalEntityForLanguage(
    mediaId: String,
    lookupIds: Collection<String>,
    entityType: LocalizedEntityType,
    language: String,
    priority: RequestPriority = RequestPriority.ACTIVE_PAGE,
    onResolved: (Alias?) -> Unit,
) {
    rememberRequestPriority(mediaId, priority)
    val targetLanguage = supportedOriginalLanguageOrNull(language)
    if (targetLanguage == null) {
        ProviderLogger.info(
            "Apple 原地区元数据查询忽略: id=$mediaId, entityType=$entityType, " +
                "reason=unsupported_language, language=$language"
        )
        onResolved(null)
        return
    }
    val storefront = storefrontForLanguage(targetLanguage)
    val ids = (listOf(mediaId) + lookupIds)
        .map(String::trim)
        .filter { it.isNotEmpty() && it.all(Char::isDigit) }
        .distinct()
    if (ids.isEmpty()) {
        onResolved(null)
        return
    }
    val directCacheKey = originalDirectEntityCacheKey(entityType, mediaId)
    persistentOriginalCache.getFirst(
        keys = originalEntityCacheLookupKeys(
            entityType = entityType,
            mediaId = mediaId,
            lookupIds = ids,
            languages = listOf(targetLanguage),
        ),
        accept = { alias -> isAcceptableOriginalAlias(alias, targetLanguage) },
        onResult = { hit ->
            if (hit != null) {
                if (hit.key != directCacheKey) {
                    // Promote old language/alternate-ID entries into the current direct key.
                    persistentOriginalCache.put(directCacheKey, hit.alias)
                }
                onResolved(hit.alias)
                return@getFirst
            }
            if (entityType == LocalizedEntityType.SONG) {
                persistentOriginalCache.remove(legacyAmbiguousSongCacheKey(mediaId))
            }
            enqueueOriginalEntityRequest(
                OriginalEntityRequest(
                    requestKey = "$entityType:$targetLanguage:$mediaId:${ids.joinToString(",")}",
                    mediaId = mediaId,
                    lookupIds = ids,
                    entityType = entityType,
                    language = targetLanguage,
                    storefront = storefront,
                    directCacheKey = directCacheKey,
                    priority = currentRequestPriority(mediaId, priority),
                    callbacks = listOf(onResolved),
                )
            )
        },
    )
}

