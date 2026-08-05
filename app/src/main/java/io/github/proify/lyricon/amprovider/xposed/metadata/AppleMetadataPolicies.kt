/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Notification

internal fun inAppPlaybackItemAccess(
    contract: InAppPlaybackItemContract,
    field: InAppPlaybackItemField,
): InAppPlaybackItemAccess? = when (contract) {
    InAppPlaybackItemContract.STANDARD -> when (field) {
        InAppPlaybackItemField.TITLE ->
            InAppPlaybackItemAccess("name", readViaMethod = false, setter = "setTitle")
        InAppPlaybackItemField.ARTIST ->
            InAppPlaybackItemAccess(
                "artistName",
                readViaMethod = false,
                setter = "setArtistName",
            )
        InAppPlaybackItemField.ALBUM ->
            InAppPlaybackItemAccess(
                "collectionName",
                readViaMethod = false,
                setter = "setCollectionName",
            )
    }
    InAppPlaybackItemContract.HISTORY -> when (field) {
        InAppPlaybackItemField.TITLE ->
            InAppPlaybackItemAccess("getTitle", readViaMethod = true, setter = "setTitle")
        InAppPlaybackItemField.ARTIST ->
            InAppPlaybackItemAccess(
                "getSubTitle",
                readViaMethod = true,
                setter = "setSubTitle",
            )
        InAppPlaybackItemField.ALBUM -> null
    }
}

internal fun isInAppHistoryQueueEntryClassName(className: String): Boolean =
    className == "Z8.d"

internal fun shouldApplyInAppPlaybackItemAlias(
    expectedMediaId: String,
    currentMediaId: String?,
): Boolean = expectedMediaId == currentMediaId

internal fun selectTrustworthyMediaId(
    explicitMediaId: String?,
    inferredMediaIds: Collection<String>,
): String? {
    explicitMediaId
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?.let { return it }
    return inferredMediaIds.asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && it.all(Char::isDigit) }
        .distinct()
        .singleOrNull()
}

internal fun composeVisibleMetadataResolutionIds(
    capturedMediaIds: Collection<String>,
    fallbackMediaIds: Collection<String>,
    limit: Int,
): List<String> {
    if (limit <= 0) return emptyList()
    val captured = normalizedRecyclerBindingMediaIds(capturedMediaIds)
    val candidates = if (captured.isNotEmpty()) {
        captured
    } else {
        normalizedRecyclerBindingMediaIds(fallbackMediaIds)
    }
    return candidates.take(limit)
}

internal fun shouldRefreshInAppLibraryComposeAlias(
    appliedAliases: Map<String, AppliedMetadataAlias>?,
    mediaId: String,
    requestedAlias: AppliedMetadataAlias?,
): Boolean = requestedAlias == null || appliedAliases?.get(mediaId) != requestedAlias

internal fun shouldRefreshInAppSurface(
    surfaceRelevant: Boolean,
    hasVisibleExactConsumer: Boolean,
    hasActiveVisibleLease: Boolean = false,
): Boolean = surfaceRelevant || hasVisibleExactConsumer || hasActiveVisibleLease

internal fun shouldRefreshExactBoundTarget(
    surfaceRelevant: Boolean,
    mediaIdMatches: Boolean,
    rootVisible: Boolean,
): Boolean = mediaIdMatches && (surfaceRelevant || rootVisible)

internal fun inAppLibraryControllerBuildStrategy(
    hasAlbumBuildData: Boolean,
    hasArtistBuildData: Boolean,
    isPlaylistPageController: Boolean,
): InAppLibraryControllerBuildStrategy = when {
    hasAlbumBuildData -> InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA
    hasArtistBuildData -> InAppLibraryControllerBuildStrategy.ARTIST_SET_DATA
    isPlaylistPageController ->
        InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD
    else -> InAppLibraryControllerBuildStrategy.GENERIC_REQUEST_MODEL_BUILD
}

internal fun selectInAppArtworkContinuityUrls(
    currentUrls: Collection<String>,
    cachedUrls: Collection<String>?,
    cachedAtUptimeMillis: Long?,
    nowUptimeMillis: Long,
    ttlMillis: Long,
): List<String>? {
    if (currentUrls.any(String::isNotBlank)) return null
    val normalizedCachedUrls = cachedUrls
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.distinct()
        .orEmpty()
    if (normalizedCachedUrls.isEmpty()) return null
    val capturedAt = cachedAtUptimeMillis ?: return null
    val ageMillis = nowUptimeMillis - capturedAt
    if (ageMillis < 0L || ageMillis > ttlMillis.coerceAtLeast(0L)) return null
    return normalizedCachedUrls
}

internal fun changedAssociatedArtistAlias(
    previousAlias: AppleInternalCatalogResolver.Alias?,
    updatedAlias: AppleInternalCatalogResolver.Alias?,
): AppleInternalCatalogResolver.Alias? = updatedAlias?.takeIf { it != previousAlias }

internal fun inAppLibraryControllerRefreshDelayMillis(
    strategy: InAppLibraryControllerBuildStrategy,
    lastBuildUptimeMillis: Long?,
    nowUptimeMillis: Long,
    albumDebounceMillis: Long,
    playlistIntervalMillis: Long,
): Long {
    if (strategy == InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA) {
        return albumDebounceMillis.coerceAtLeast(0L)
    }
    if (
        strategy != InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD ||
        lastBuildUptimeMillis == null
    ) {
        return 0L
    }
    val elapsed = (nowUptimeMillis - lastBuildUptimeMillis).coerceAtLeast(0L)
    return (playlistIntervalMillis - elapsed).coerceAtLeast(0L)
}

internal fun localizedEntityTypeForInAppLibraryKind(
    kind: InAppLibraryEntityKind,
): AppleInternalCatalogResolver.LocalizedEntityType = when (kind) {
    InAppLibraryEntityKind.ALBUM -> AppleInternalCatalogResolver.LocalizedEntityType.ALBUM
    InAppLibraryEntityKind.SONG -> AppleInternalCatalogResolver.LocalizedEntityType.SONG
    InAppLibraryEntityKind.ARTIST -> AppleInternalCatalogResolver.LocalizedEntityType.ARTIST
}

/**
 * 只按 Apple Media API 的明确实体类型分类最近搜索项。
 *
 * 不根据标题、歌手字段或当前播放内容猜测类型，避免把歌曲（尤其是合唱、
 * 多 artistID、feat. 或多人署名）误当成单一歌手实体。
 */
internal fun inAppLibraryEntityKindForClassNames(
    classNames: Iterable<String>,
): InAppLibraryEntityKind? {
    val names = classNames.toSet()
    return when {
        names.any {
            it == "com.apple.android.music.mediaapi.models.Artist" ||
                it == "com.apple.android.music.mediaapi.models.LibraryArtist"
        } -> InAppLibraryEntityKind.ARTIST

        names.any {
            it == "com.apple.android.music.mediaapi.models.Album" ||
                it == "com.apple.android.music.mediaapi.models.LibraryAlbum"
        } -> InAppLibraryEntityKind.ALBUM

        names.any {
            it == "com.apple.android.music.mediaapi.models.Song" ||
                it == "com.apple.android.music.mediaapi.models.LibrarySong"
        } -> InAppLibraryEntityKind.SONG

        else -> null
    }
}

internal fun mediaApiAttributeArtistIds(attributes: Any?): List<String> {
    attributes ?: return emptyList()
    return listOf("getArtistId", "getArtistAdamId", "getArtistStoreId")
        .mapNotNull { getter ->
            runCatching { AppleReflection.call(attributes, getter) }
                .getOrNull()
                ?.toString()
                ?.trim()
                ?.takeIf { value ->
                    value.isNotEmpty() && value != "0" && value.all(Char::isDigit)
                }
        }
        .distinct()
}

internal fun libraryAssociatedArtistIds(
    kind: InAppLibraryEntityKind,
    mediaId: String,
    attributeArtistIds: List<String>,
    associationKeys: Set<String>,
): List<String> = if (kind == InAppLibraryEntityKind.ARTIST) {
    listOf(mediaId)
} else {
    (
        attributeArtistIds +
            AppleMetadataResolutionEngine.artistIdsFromAssociationKeys(associationKeys)
        ).distinct()
}

internal fun inferredOriginalArtistLanguage(
    kind: InAppLibraryEntityKind,
    artist: String?,
    associatedArtistIds: List<String>,
    genres: Collection<String>,
): String? {
    if (associatedArtistIds.size != 1) return null
    if (
        kind != InAppLibraryEntityKind.ARTIST &&
        AppleInternalCatalogResolver.isCollaborationArtistName(artist.orEmpty())
    ) return null
    return AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
        genre = null,
        catalogGenres = genres,
        isrc = null,
    ).singleOrNull()
}

internal fun artistIdsFromAssociationKeys(keys: Collection<String>): List<String> =
    AppleMetadataResolutionEngine.artistIdsFromAssociationKeys(keys)

internal fun sharedAssociatedArtistId(
    artistIds: Collection<String>,
    artistCredit: String? = null,
): String? = AppleMetadataResolutionEngine.sharedAssociatedArtistId(
    artistIds = artistIds,
    artistCredit = artistCredit,
)

internal fun shouldUseAssociatedArtistEntities(
    artistIds: Collection<String>,
    artistCredit: String? = null,
): Boolean = AppleMetadataResolutionEngine.shouldUseAssociatedArtistEntities(
    artistIds = artistIds,
    artistCredit = artistCredit,
)

internal fun shouldShareAssociatedArtistAlias(
    artistId: String,
    targetArtistIds: Collection<String>,
    targetArtistCredit: String?,
): Boolean = AppleMetadataResolutionEngine.shouldShareAssociatedArtistAlias(
    artistId = artistId,
    targetArtistIds = targetArtistIds,
    targetArtistCredit = targetArtistCredit,
)

internal fun associatedArtistCredit(
    entityType: AppleInternalCatalogResolver.LocalizedEntityType?,
    accountTitle: String?,
    accountArtist: String?,
): String? = AppleMetadataResolutionEngine.associatedArtistCredit(
    entityType = entityType,
    accountTitle = accountTitle,
    accountArtist = accountArtist,
)

internal fun shouldAcceptAssociatedArtistResolution(
    requestedArtistIds: Collection<String>,
    currentArtistIds: Collection<String>,
    artistCredit: String?,
): Boolean = AppleMetadataResolutionEngine.shouldAcceptAssociatedArtistResolution(
    requestedArtistIds = requestedArtistIds,
    currentArtistIds = currentArtistIds,
    artistCredit = artistCredit,
)

internal fun localizedEntityTypeForQueueItem(
    historyEntry: Boolean,
    classNames: Collection<String>,
): AppleInternalCatalogResolver.LocalizedEntityType? =
    AppleMetadataResolutionEngine.localizedEntityTypeForQueueItem(
        historyEntry = historyEntry,
        classNames = classNames,
    )

internal fun localizedEntityTypeForContentItemClassNames(
    classNames: Collection<String>,
): AppleInternalCatalogResolver.LocalizedEntityType? =
    AppleMetadataResolutionEngine.localizedEntityTypeForContentItemClassNames(classNames)

internal fun confirmedOriginalSongAlias(
    resolution: AppleInternalCatalogResolver.OriginalResolution,
): AppleInternalCatalogResolver.Alias? = resolution.alias

internal fun validatedOriginalSongAlias(
    alias: AppleInternalCatalogResolver.Alias?,
    localizedTitle: String?,
    localizedArtist: String?,
): AppleInternalCatalogResolver.Alias? = alias?.takeIf {
    AppleInternalCatalogResolver.isConfidentOriginalSongAlias(
        alias = it,
        localizedTitle = localizedTitle.orEmpty(),
        localizedArtist = localizedArtist.orEmpty(),
    )
}

internal fun originalSongRetryLanguage(
    resolution: AppleInternalCatalogResolver.OriginalResolution,
): String? = resolution.language?.takeIf {
    resolution.alias == null && resolution.originKnown
}

internal fun originalArtistLanguageFromSongResolution(
    resolution: AppleInternalCatalogResolver.OriginalResolution,
    localizedArtist: String?,
): String? {
    if (!resolution.originKnown ||
        !shouldUseAssociatedArtistEntities(
            artistIds = resolution.artistIds,
            artistCredit = localizedArtist,
        )
    ) {
        return null
    }
    return resolution.language?.let(
        AppleInternalCatalogResolver::supportedOriginalLanguageOrNull
    )
}

internal fun stableArtistCacheKeys(keys: Collection<String>): Set<String> =
    AppleMetadataResolutionEngine.stableArtistCacheKeys(keys)

internal fun localizedArtistCacheKeys(keys: Collection<String>): Set<String> =
    AppleMetadataResolutionEngine.localizedArtistCacheKeys(keys)

internal fun associatedArtistAlias(
    artistIds: List<String>,
    aliases: Map<String, AppleInternalCatalogResolver.Alias>,
    language: String,
): AppleInternalCatalogResolver.Alias? =
    AppleMetadataResolutionEngine.associatedArtistAlias(
        artistIds = artistIds,
        aliases = aliases,
        language = language,
    )

internal fun selectEffectiveMetadataAlias(
    restoreOriginalEnabled: Boolean,
    originalMetadataResolved: Boolean,
    originalMetadata: AppleInternalCatalogResolver.Alias?,
    originalArtistResolved: Boolean,
    originalArtist: AppleInternalCatalogResolver.Alias?,
    localizedMetadata: AppleInternalCatalogResolver.Alias?,
    localizedArtist: AppleInternalCatalogResolver.Alias?,
): AppleInternalCatalogResolver.Alias? =
    AppleMetadataResolutionEngine.selectEffectiveMetadataAlias(
        restoreOriginalEnabled = restoreOriginalEnabled,
        originalMetadataResolved = originalMetadataResolved,
        originalMetadata = originalMetadata,
        originalArtistResolved = originalArtistResolved,
        originalArtist = originalArtist,
        localizedMetadata = localizedMetadata,
        localizedArtist = localizedArtist,
    )

internal fun selectIndependentArtistAlias(
    restoreOriginalEnabled: Boolean,
    canUseAssociatedArtist: Boolean,
    originalArtist: AppleInternalCatalogResolver.Alias?,
    localizedArtist: AppleInternalCatalogResolver.Alias?,
): AppleInternalCatalogResolver.Alias? =
    AppleMetadataResolutionEngine.selectIndependentArtistAlias(
        restoreOriginalEnabled = restoreOriginalEnabled,
        canUseAssociatedArtist = canUseAssociatedArtist,
        originalArtist = originalArtist,
        localizedArtist = localizedArtist,
    )

internal fun shouldRequestEffectiveMetadataResolution(
    restoreOriginalEnabled: Boolean,
    originalMetadataResolved: Boolean,
    hasOriginalMetadata: Boolean,
    hasAssociatedArtists: Boolean,
    originalArtistResolved: Boolean,
    hasLocalizedMetadata: Boolean,
): Boolean = AppleMetadataResolutionEngine.shouldRequestEffectiveMetadataResolution(
    restoreOriginalEnabled = restoreOriginalEnabled,
    originalMetadataResolved = originalMetadataResolved,
    hasOriginalMetadata = hasOriginalMetadata,
    hasAssociatedArtists = hasAssociatedArtists,
    originalArtistResolved = originalArtistResolved,
    hasLocalizedMetadata = hasLocalizedMetadata,
)

internal fun inAppOriginalResolutionPlan(
    mediaIds: Collection<String>,
    awaitingLocalizedIds: Set<String>,
    mode: InAppOriginalResolutionMode,
): InAppOriginalResolutionPlan = AppleMetadataResolutionEngine.inAppOriginalResolutionPlan(
    mediaIds = mediaIds,
    awaitingLocalizedIds = awaitingLocalizedIds,
    mode = mode,
)

internal fun collectionPageOriginalResolutionMode(
    pageType: String,
): InAppOriginalResolutionMode =
    AppleMetadataResolutionEngine.collectionPageOriginalResolutionMode(pageType)

internal fun localizedVisibleText(
    field: VisibleTextField,
    alias: AppleInternalCatalogResolver.Alias,
): String = when (field) {
    VisibleTextField.TITLE -> alias.title
    VisibleTextField.ARTIST -> alias.artist
    VisibleTextField.ALBUM -> alias.album.ifBlank { alias.title }
}

internal fun visibleTextFieldForMediaApiAttribute(
    kind: InAppLibraryEntityKind,
    getter: String,
): VisibleTextField? = when (getter) {
    "getName" -> when (kind) {
        InAppLibraryEntityKind.SONG -> VisibleTextField.TITLE
        InAppLibraryEntityKind.ALBUM -> VisibleTextField.ALBUM
        InAppLibraryEntityKind.ARTIST -> VisibleTextField.ARTIST
    }
    "getArtistName" -> VisibleTextField.ARTIST
    "getAlbumName" -> VisibleTextField.ALBUM
    else -> null
}

internal fun contentItemMetadataOverride(
    entityType: AppleInternalCatalogResolver.LocalizedEntityType,
    getter: String,
    alias: AppleInternalCatalogResolver.Alias,
    original: String?,
): String? = when (getter) {
    "getTitle" -> when (entityType) {
        AppleInternalCatalogResolver.LocalizedEntityType.SONG -> alias.title
        AppleInternalCatalogResolver.LocalizedEntityType.ALBUM ->
            alias.album.ifBlank { alias.title }
        AppleInternalCatalogResolver.LocalizedEntityType.ARTIST ->
            alias.artist.ifBlank { alias.title }
    }
    "getNowPlayingTitle" ->
        alias.title.takeIf {
            entityType == AppleInternalCatalogResolver.LocalizedEntityType.SONG
        }
    "getArtistName" -> alias.artist
    "getNowPlayingSubtitle" ->
        alias.artist.takeIf {
            entityType == AppleInternalCatalogResolver.LocalizedEntityType.SONG
        }
    "getSubTitle" ->
        alias.artist.takeIf {
            entityType == AppleInternalCatalogResolver.LocalizedEntityType.SONG ||
                entityType == AppleInternalCatalogResolver.LocalizedEntityType.ALBUM
        }
    "getCollectionName" ->
        alias.album.takeIf {
            entityType == AppleInternalCatalogResolver.LocalizedEntityType.SONG
        }
    else -> null
}?.takeIf { it.isNotBlank() } ?: original

internal fun visibleTextFieldForContentItemGetter(
    entityType: AppleInternalCatalogResolver.LocalizedEntityType,
    getter: String,
): VisibleTextField? = when (getter) {
    "getTitle" -> when (entityType) {
        AppleInternalCatalogResolver.LocalizedEntityType.SONG -> VisibleTextField.TITLE
        AppleInternalCatalogResolver.LocalizedEntityType.ALBUM -> VisibleTextField.ALBUM
        AppleInternalCatalogResolver.LocalizedEntityType.ARTIST -> VisibleTextField.ARTIST
    }
    "getNowPlayingTitle" ->
        VisibleTextField.TITLE.takeIf {
            entityType == AppleInternalCatalogResolver.LocalizedEntityType.SONG
        }
    "getArtistName" -> VisibleTextField.ARTIST
    "getNowPlayingSubtitle" ->
        VisibleTextField.ARTIST.takeIf {
            entityType == AppleInternalCatalogResolver.LocalizedEntityType.SONG
        }
    "getSubTitle" ->
        VisibleTextField.ARTIST.takeIf {
            entityType == AppleInternalCatalogResolver.LocalizedEntityType.SONG ||
                entityType == AppleInternalCatalogResolver.LocalizedEntityType.ALBUM
        }
    "getCollectionName" ->
        VisibleTextField.ALBUM.takeIf {
            entityType == AppleInternalCatalogResolver.LocalizedEntityType.SONG
        }
    else -> null
}

internal fun preferredVisibleEntityType(
    field: VisibleTextField?,
): AppleInternalCatalogResolver.LocalizedEntityType? = when (field) {
    VisibleTextField.TITLE -> AppleInternalCatalogResolver.LocalizedEntityType.SONG
    VisibleTextField.ARTIST -> AppleInternalCatalogResolver.LocalizedEntityType.ARTIST
    VisibleTextField.ALBUM -> AppleInternalCatalogResolver.LocalizedEntityType.ALBUM
    null -> null
}

internal fun catalogMetadataResolutionPlan(
    overrideAccountLanguage: Boolean,
    restoreCjkOriginalMetadata: Boolean,
): CatalogMetadataResolutionPlan = AppleMetadataResolutionEngine.catalogMetadataResolutionPlan(
    overrideAccountLanguage = overrideAccountLanguage,
    restoreCjkOriginalMetadata = restoreCjkOriginalMetadata,
)

internal fun shouldRetryOriginalMetadataCacheProbe(
    originalResolved: Boolean,
    lastMissUptimeMillis: Long?,
    nowUptimeMillis: Long,
    retryAfterMillis: Long = AppleMetadataResolutionEngine.ORIGINAL_METADATA_CACHE_MISS_RETRY_MS,
): Boolean = AppleMetadataResolutionEngine.shouldRetryOriginalMetadataCacheProbe(
    originalResolved = originalResolved,
    lastMissUptimeMillis = lastMissUptimeMillis,
    nowUptimeMillis = nowUptimeMillis,
    retryAfterMillis = retryAfterMillis,
)

internal fun shouldResolveMetadataFromGetter(
    priority: AppleInternalCatalogResolver.RequestPriority,
): Boolean = priority == AppleInternalCatalogResolver.RequestPriority.VISIBLE

internal fun appleNativeSupplementTracks(
    pronunciationSelected: Boolean,
    translationSelected: Boolean,
): List<AppleNativeSupplementTrack> = buildList {
    if (translationSelected) add(AppleNativeSupplementTrack.TRANSLATION)
    if (pronunciationSelected) add(AppleNativeSupplementTrack.PRONUNCIATION)
}

internal fun shouldCompleteAppleLyricsProgrammaticRecenter(
    suspendedForScroll: Boolean,
    scrollState: Int,
    pendingTargetPosition: Int?,
    focusPositions: Set<Int>,
): Boolean =
    suspendedForScroll &&
        scrollState == 0 &&
        pendingTargetPosition != null &&
        pendingTargetPosition in focusPositions

internal fun appleLyricsBlurFocusPositions(
    activePositions: Set<Int>,
    instrumentalPositions: Set<Int>,
    writersCreditsPositions: Set<Int> = emptySet(),
): Set<Int> {
    if (instrumentalPositions.isNotEmpty()) return instrumentalPositions
    if (activePositions.isEmpty()) return emptySet()
    val trailingWritersCredits = writersCreditsPositions.filterTo(linkedSetOf()) { position ->
        position - 1 in activePositions
    }
    return activePositions + trailingWritersCredits
}

internal fun shouldDeferAppleLyricsOutgoingBlur(
    isPendingOutgoing: Boolean,
    rowBottomY: Float?,
    currentZoneTopY: Float?,
): Boolean =
    isPendingOutgoing &&
        (rowBottomY == null || currentZoneTopY == null || rowBottomY > currentZoneTopY)

internal fun resolvePlaybackPositionSource(
    mediaPlayer: Any?,
    currentPositionMethodName: String = "getCurrentPosition",
): PlaybackPositionSource? {
    mediaPlayer ?: return null
    val method = runCatching {
        AppleReflection.findMethod(
            mediaPlayer.javaClass,
            currentPositionMethodName,
            parameterCount = 0
        )
    }.getOrNull() ?: return null
    return PlaybackPositionSource(mediaPlayer, method)
}


internal fun isActivePlaybackCallback(callbackPlayer: Any?, activePlayer: Any?): Boolean =
    callbackPlayer != null && callbackPlayer === activePlayer

internal fun shouldNotifyInAppModelChange(
    mediaId: String,
    activeMediaId: String?,
    hasBoundConsumer: Boolean = false,
): Boolean = mediaId == activeMediaId || hasBoundConsumer

internal fun mergeDeferredMetadataResolution(
    previous: DeferredMetadataResolution?,
    incoming: DeferredMetadataResolution,
): DeferredMetadataResolution {
    if (previous == null) return incoming
    return DeferredMetadataResolution(
        priority = if (incoming.priority.ordinal > previous.priority.ordinal) {
            incoming.priority
        } else {
            previous.priority
        },
        originalResolutionMode = if (
            previous.originalResolutionMode == InAppOriginalResolutionMode.ORIGINAL_FIRST ||
            incoming.originalResolutionMode == InAppOriginalResolutionMode.ORIGINAL_FIRST
        ) {
            InAppOriginalResolutionMode.ORIGINAL_FIRST
        } else {
            InAppOriginalResolutionMode.AFTER_LOCALIZED
        },
    )
}

internal fun shouldOpenFullPlayerFromNotification(
    category: String?,
    hasMediaSession: Boolean,
): Boolean = category == Notification.CATEGORY_TRANSPORT || hasMediaSession

internal fun appleLyricsStringArrayParameterIndexes(
    parameterTypes: Array<Class<*>>,
): List<Int> = parameterTypes.indices.filter { parameterTypes[it] == Array<String>::class.java }

internal fun isAppleLyricsRequestPath(pathSegments: List<String>): Boolean =
    pathSegments.getOrNull(3) == "songs" &&
        pathSegments.lastOrNull()?.contains("lyrics") == true

internal fun expandAppleLyricsPronunciationLanguages(original: List<String>): List<String> =
    (
        original + listOf(
            "ja-Latn",
            "ko-Latn",
            "zh-Latn",
        )
    )
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

internal fun expandAppleLyricsTranslationLanguages(original: List<String>): List<String> =
    original
        .flatMap { language ->
            when (normalizedAppleLyricsLanguageTag(language)) {
                "zh-hans", "zh-hans-cn", "zh-cn" ->
                    listOf(language, "zh-Hans", "zh-Hans-CN", "zh-CN")
                "zh-hans-sg", "zh-sg" ->
                    listOf(language, "zh-Hans", "zh-Hans-SG", "zh-SG", "zh-Hans-CN")
                "zh-hant", "zh-hant-tw", "zh-tw" ->
                    listOf(language, "zh-Hant", "zh-Hant-TW", "zh-TW", "zh-Hant-HK")
                "zh-hant-hk", "zh-hk", "zh-hant-mo", "zh-mo" ->
                    listOf(language, "zh-Hant", "zh-Hant-HK", "zh-HK", "zh-Hant-TW")
                else -> listOf(language)
            }
        }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy(::normalizedAppleLyricsLanguageTag)

internal fun selectAppleLyricsTranslationLanguage(
    systemLanguage: String,
    availableLanguages: List<String>,
): String? {
    val system = appleLyricsLanguageParts(systemLanguage) ?: return null
    val available = availableLanguages.mapNotNull { language ->
        appleLyricsLanguageParts(language)?.let { parts -> language to parts }
    }
    return available.firstOrNull { (_, parts) -> parts.normalized == system.normalized }?.first
        ?: available.firstOrNull { (_, parts) ->
            parts.language == system.language &&
                parts.script != null &&
                parts.script == system.script
        }?.first
        ?: available.firstOrNull { (_, parts) ->
            parts.language == system.language &&
                parts.region != null &&
                parts.region == system.region
        }?.first
        ?: available.firstOrNull { (_, parts) -> parts.language == system.language }?.first
}

private fun normalizedAppleLyricsLanguageTag(language: String): String =
    language.trim().replace('_', '-').lowercase()

private fun appleLyricsLanguageParts(language: String): AppleLyricsLanguageParts? {
    val normalized = normalizedAppleLyricsLanguageTag(language)
    val segments = normalized.split('-').filter(String::isNotEmpty)
    val primary = segments.firstOrNull() ?: return null
    val explicitScript = segments.drop(1).firstOrNull { it.length == 4 }
    val region = segments.drop(1).firstOrNull { segment ->
        segment.length == 2 || segment.length == 3 && segment.all(Char::isDigit)
    }
    val inferredScript = explicitScript ?: when {
        primary != "zh" -> null
        region in setOf("tw", "hk", "mo") -> "hant"
        region in setOf("cn", "sg") -> "hans"
        else -> null
    }
    return AppleLyricsLanguageParts(
        normalized = normalized,
        language = primary,
        script = inferredScript,
        region = region,
    )
}
