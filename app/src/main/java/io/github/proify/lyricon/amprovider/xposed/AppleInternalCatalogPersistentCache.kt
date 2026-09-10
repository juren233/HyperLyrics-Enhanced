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

/**
 * Applies the content storefront without changing the account's original value.
 * The MediaApi storefront is also used by Apple Music for localized content, so
 * keeping this value in one place covers both startup and post-query recovery.
 */
internal fun AppleInternalCatalogResolver.applyContentUiLanguage(selection: Int) {
    contentUiLanguageSelection = selection
    warmPersistentLocalizedCache(selection)
    if (activeCatalogRequest.get() != null) return
    runCatching {
        val access = catalogAccess ?: createCatalogAccess().also { catalogAccess = it }
        restoreConfiguredStorefront(access)
    }.onFailure {
        ProviderLogger.error("Apple 内容 UI storefront 应用失败: selection=$selection", it)
    }
}

internal fun AppleInternalCatalogResolver.setPersistentLocalizedCacheEnabled(enabled: Boolean) {
    val wasEnabled = caches.setPersistentCacheEnabled(enabled)
    persistentLocalizedCache.setEnabled(enabled)
    persistentOriginalCache.setEnabled(enabled)
    if (enabled) {
        warmPersistentOriginalCache()
        if (!wasEnabled) {
            caches.resetLocalizedCacheWarm(contentUiLanguageSelection)
        }
        warmPersistentLocalizedCache(contentUiLanguageSelection)
    }
}

internal fun AppleInternalCatalogResolver.warmPersistentOriginalCache() {
    if (!caches.isPersistentCacheEnabled()) return
    persistentOriginalCache.warmRecentAsync { count ->
        if (count != null) {
            ProviderLogger.info("Apple 原地区元数据缓存预热完成: entries=$count")
        } else {
            ProviderLogger.info("Apple 原地区元数据缓存预热延后")
        }
    }
}

internal fun AppleInternalCatalogResolver.cachedLocalizedArtist(selection: Int, artistKeys: Collection<String>): Alias? {
    val languageTags = languageTagsForContentUiLanguage(selection)
    val keys = artistKeys.flatMap { key ->
        languageTags.map { language -> artistCacheKey(selection, key, language) } +
            if (languageTags.size == 1) listOf(artistCacheKey(selection, key)) else emptyList()
    }
    return caches.firstLocalizedArtistAlias(keys)
}

internal fun AppleInternalCatalogResolver.cachedLocalizedMetadata(
    selection: Int,
    entityType: LocalizedEntityType,
    mediaId: String,
): Alias? {
    val normalizedId = mediaId.trim()
    if (normalizedId.isEmpty() || !normalizedId.all(Char::isDigit)) return null
    val languageTags = languageTagsForContentUiLanguage(selection)
    val keys = languageTags.map { language ->
        localizedMetadataCacheKey(selection, entityType, normalizedId, language)
    } + if (languageTags.size == 1) {
        listOf(localizedMetadataCacheKey(selection, entityType, normalizedId))
    } else {
        emptyList()
    }
    return caches.firstLocalizedAlias(keys)
}

internal fun AppleInternalCatalogResolver.rememberLocalizedArtist(
    selection: Int,
    artistKeys: Collection<String>,
    localizedArtist: String,
    language: String? = null,
) {
    if (localizedArtist.isBlank()) return
    val alias = Alias(title = "", artist = localizedArtist, language = "", album = "")
    val entries = artistKeys
        .map { artistCacheKey(selection, it, language) }
        .distinct()
        .associateWith { alias }
    if (entries.isEmpty()) return
    val changedEntries = caches.mergeLocalizedArtistAliases(entries)
    persistentLocalizedCache.putMany(changedEntries)
}

internal fun AppleInternalCatalogResolver.warmPersistentLocalizedCache(selection: Int) {
    if (!caches.isPersistentCacheEnabled()) return
    if (storefrontForContentUiLanguage(selection) == null) return
    if (!caches.beginLocalizedCacheWarm(selection)) return
    val prefix = "$selection:"
    persistentLocalizedCache.warmRecentAsync(prefix) { delayedAliases ->
        if (delayedAliases != null) {
            finishPersistentCacheWarm(selection, delayedAliases)
        } else {
            caches.abandonLocalizedCacheWarm(selection)
            ProviderLogger.info("Apple 地区元数据缓存预热延后: selection=$selection")
        }
    }
}

internal fun AppleInternalCatalogResolver.finishPersistentCacheWarm(selection: Int, aliases: Map<String, Alias>) {
    val artistAliases = aliases.filterKeys(::isLocalizedArtistAliasCacheKey)
    val metadataAliases = aliases.filterKeys { key ->
        !isLocalizedArtistAliasCacheKey(key)
    }
    caches.putAllLocalizedAliases(metadataAliases)
    caches.putAllLocalizedArtistAliases(artistAliases)
    caches.completeLocalizedCacheWarm(selection)
    ProviderLogger.info(
        "Apple 地区元数据缓存预热完成: selection=$selection, " +
            "metadata=${metadataAliases.size}, artistAlias=${artistAliases.size}, " +
            "entries=${aliases.size}"
    )
}

internal fun AppleInternalCatalogResolver.languageTagForCurrentRequest(selection: Int): String? =
    activeCatalogRequest.get()?.language ?: languageTagForContentUiLanguage(selection)

internal fun AppleInternalCatalogResolver.catalogRequestLocalization(token: String?): CatalogRequestLocalization? =
    token?.let(pendingCatalogRequests::get)

internal fun AppleInternalCatalogResolver.pendingCatalogRequestCount(): Int = pendingCatalogRequests.size

internal fun AppleInternalCatalogResolver.cachedCatalogGenres(mediaId: String): List<String> =
    caches.catalogGenres(mediaId)

internal fun AppleInternalCatalogResolver.accountStorefrontForPlaybackRequest(): String? {
    accountStorefront?.let { return it }
    return runCatching {
        val access = catalogAccess ?: createCatalogAccess().also { catalogAccess = it }
        captureAccountStorefront(access)
        accountStorefront
    }.getOrNull()
}

internal fun AppleInternalCatalogResolver.restoreConfiguredStorefront(access: CatalogAccess) {
    captureAccountStorefront(access)
    val selection = contentUiLanguageSelection
    val configuredStorefront = storefrontForContentUiLanguage(selection)
    if (configuredStorefront == null && !accountStorefrontCaptured) return
    val target = configuredStorefront ?: accountStorefront
    val previous = access.storefrontField.get(access.mediaApi) as? String
    access.storefrontField.set(access.mediaApi, target)
    lastAppliedConfiguredStorefront = configuredStorefront
    if (previous != target) {
        ProviderLogger.info(
            "Apple 内容 UI storefront 已应用: selection=$selection, " +
                "previous=${previous ?: "unset"}, " +
                "storefront=${target ?: "account-default"}, " +
                "accountStorefront=${accountStorefront ?: "account-default"}"
        )
    }
}

internal fun AppleInternalCatalogResolver.captureAccountStorefront(access: CatalogAccess) {
    val current = access.storefrontField.get(access.mediaApi) as? String ?: return
    if (!accountStorefrontCaptured || current != lastAppliedConfiguredStorefront) {
        accountStorefront = current
        accountStorefrontCaptured = true
    }
}

internal fun AppleInternalCatalogResolver.resolveCachedOriginalEntity(
    mediaId: String,
    entityType: LocalizedEntityType,
    onResolved: (Alias?) -> Unit,
    lookupIds: Collection<String> = emptyList(),
) {
    val normalizedId = mediaId.trim()
    if (normalizedId.isEmpty() || !normalizedId.all(Char::isDigit)) {
        onResolved(null)
        return
    }
    val directKey = originalDirectEntityCacheKey(entityType, normalizedId)
    persistentOriginalCache.getFirst(
        keys = originalEntityCacheLookupKeys(
            entityType = entityType,
            mediaId = normalizedId,
            lookupIds = lookupIds,
        ),
        onResult = { hit ->
            val validAlias = hit?.alias?.takeIf {
                isAcceptableOriginalAlias(it, canonicalOriginalLanguage(it.language))
            }
            if (validAlias != null && hit.key != directKey) {
                // Promote compatibility/alternate-ID hits so subsequent home builders use the
                // same fast direct path as the library page.
                persistentOriginalCache.put(directKey, validAlias)
            }
            if (entityType == LocalizedEntityType.SONG) {
                persistentOriginalCache.remove(legacyAmbiguousSongCacheKey(normalizedId))
            }
            onResolved(validAlias)
        },
    )
}

/**
 * Reads only the warmed/visited memory entries for the current ID and compatibility IDs.
 * SQLite remains asynchronous in [resolveCachedOriginalEntity].
 */
internal fun AppleInternalCatalogResolver.cachedOriginalEntity(
    mediaId: String,
    entityType: LocalizedEntityType,
    lookupIds: Collection<String> = emptyList(),
): Alias? {
    val normalizedId = mediaId.trim()
    if (normalizedId.isEmpty() || !normalizedId.all(Char::isDigit)) return null
    val keys = originalEntityCacheLookupKeys(
        entityType = entityType,
        mediaId = normalizedId,
        lookupIds = lookupIds,
    )
    val directKey = originalDirectEntityCacheKey(entityType, normalizedId)
    val hit = keys.firstNotNullOfOrNull { key ->
        persistentOriginalCache.cached(key)?.let { alias ->
            alias.takeIf {
                isAcceptableOriginalAlias(it, canonicalOriginalLanguage(it.language))
            }?.let { valid -> AppleOriginalMetadataCache.CacheHit(key, valid) }
        }
    }
    if (hit != null && hit.key != directKey) {
        persistentOriginalCache.put(directKey, hit.alias)
    }
    if (entityType == LocalizedEntityType.SONG) {
        persistentOriginalCache.remove(legacyAmbiguousSongCacheKey(normalizedId))
    }
    return hit?.alias
}

internal fun AppleInternalCatalogResolver.cachedOriginalArtistRegion(artistKeys: Collection<String>): String? =
    persistentOriginalCache.cachedArtistRegion(artistKeys)

internal fun AppleInternalCatalogResolver.rememberOriginalArtistRegion(artistKeys: Collection<String>, language: String) {
    persistentOriginalCache.rememberArtistRegion(artistKeys, language)
}
