/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.BuildConfig

internal enum class AppleMediaApiTextAttribute(
    val getterRuntimeMember: AppleMusicRuntimeMember,
    val setterRuntimeMember: AppleMusicRuntimeMember,
) {
    NAME(
        AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_NAME_METHOD,
        AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_SET_NAME_METHOD,
    ),
    ARTIST_NAME(
        AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_NAME_METHOD,
        AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_SET_ARTIST_NAME_METHOD,
    ),
    ALBUM_NAME(
        AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ALBUM_NAME_METHOD,
        AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_SET_ALBUM_NAME_METHOD,
    ),
}

internal interface AppleMediaApiMetadataHost {
    fun contentItemMediaId(contentItem: Any): String?

    fun registerPlaybackItem(
        mediaId: String,
        playbackItem: Any,
        notifyChange: Boolean,
        analyzeMetadata: Boolean,
    )

    fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias?

    fun applyAliasToPlaybackItem(
        playbackItem: Any,
        alias: AppleInternalCatalogResolver.Alias,
        notifyChange: Boolean,
    )

    fun shouldShareOriginalSongLanguage(
        localizedTitle: String?,
        localizedArtist: String?,
        alias: AppleInternalCatalogResolver.Alias?,
    ): Boolean

    fun rememberOriginalLanguageForArtist(mediaId: String, language: String)

    fun hydrateSharedArtistOverrides(mediaId: String)

    fun markMetadataVisible(mediaIds: Collection<String>)

    fun applyAliasToMetadataRefs(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        forceRebind: Boolean,
        notifyModelChange: Boolean,
    )

    fun shouldRequestOverride(mediaId: String): Boolean

    fun scheduleMetadataResolution(
        mediaIds: Collection<String>,
        priority: AppleInternalCatalogResolver.RequestPriority,
        originalResolutionMode: InAppOriginalResolutionMode,
    )

    fun configuredContentUiLanguage(): Int

    fun nextTraceSequence(): Long
}

/**
 * Coordinates Apple Media API entity identity, artist associations, and Recently Searched
 * binding without owning page refresh or catalog request state.
 */
internal class AppleMediaApiMetadataCoordinator(
    private val runtime: AppleMusicProviderRuntime,
    private val metadataStore: AppleMetadataOverrideStore,
    private val catalogResolver: AppleInternalCatalogResolver,
    private val librarySurfaceHooks: AppleLibrarySurfaceHooks,
    private val artistSurfaceHooks: AppleArtistSurfaceHooks,
    private val host: AppleMediaApiMetadataHost,
) {
    private val libraryEntityRuntimeClasses by lazy {
        runtime.hookResolver.resolveClasses(AppleMusicHookPoint.LIBRARY_ENTITY_CLASSES)
    }
    private val catalogTarget by lazy {
        runtime.hookResolver.resolveClass(
            AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS
        ).target
    }

    fun entityCatalogId(entity: Any, knownAttributes: Any? = null): String? =
        entityLookupIds(entity, knownAttributes).firstOrNull()

    fun entityLookupIds(entity: Any, knownAttributes: Any? = null): Set<String> = buildSet {
        fun addValue(value: Any?) {
            when (value) {
                is Array<*> -> value.forEach(::addValue)
                is Iterable<*> -> value.forEach(::addValue)
                else -> value?.toString()?.trim()?.takeIf { candidate ->
                    candidate.isNotEmpty() && candidate.all(Char::isDigit)
                }?.let(::add)
            }
        }

        val attributes = knownAttributes ?: entityAttributes(entity)
        val playParams = attributes?.let {
            runCatching {
                AppleReflection.call(
                    it,
                    catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_PLAY_PARAMS_METHOD),
                )
            }.getOrNull()
        }
        addValue(playParams?.let {
            runCatching {
                AppleReflection.call(
                    it,
                    catalogMember(AppleMusicRuntimeMember.CATALOG_PLAY_PARAMS_CATALOG_ID_METHOD),
                )
            }.getOrNull()
        })
        listOf(
            AppleMusicRuntimeMember.CATALOG_ENTITY_ID_METHOD,
            AppleMusicRuntimeMember.CATALOG_ENTITY_SUBSCRIPTION_STORE_ID_METHOD,
            AppleMusicRuntimeMember.CATALOG_ENTITY_ASSET_ADAM_ID_METHOD,
            AppleMusicRuntimeMember.CATALOG_ENTITY_REPORTING_ADAM_ID_METHOD,
        ).forEach { runtimeMember ->
            addValue(
                runCatching { AppleReflection.call(entity, catalogMember(runtimeMember)) }
                    .getOrNull()
            )
        }
        addValue(
            runCatching {
                AppleReflection.call(
                    entity,
                    catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_FORMER_IDS_METHOD),
                )
            }.getOrNull()
        )
    }

    fun entityAttributes(entity: Any): Any? =
        runCatching {
            AppleReflection.call(
                entity,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_ATTRIBUTES_METHOD),
            )
        }.getOrNull()

    fun attribute(attributes: Any, attribute: AppleMediaApiTextAttribute): String? =
        runCatching {
            AppleReflection.call(
                attributes,
                catalogMember(attribute.getterRuntimeMember),
            ) as? String
        }.getOrNull()

    fun primeLibrarySource(source: Any?) {
        source ?: return
        val mediaId = host.contentItemMediaId(source) ?: return
        host.registerPlaybackItem(
            mediaId = mediaId,
            playbackItem = source,
            notifyChange = false,
            analyzeMetadata = false,
        )
        host.effectiveAlias(mediaId)?.let { alias ->
            host.applyAliasToPlaybackItem(source, alias, notifyChange = false)
        }
    }

    fun registerLibraryEntity(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        knownAttributes: Any? = null,
        requestResolution: Boolean = true,
        retainEntityRef: Boolean = true,
    ) {
        librarySurfaceHooks.registerEntity(
            mediaId = mediaId,
            entity = entity,
            kind = kind,
            knownAttributes = knownAttributes,
            requestResolution = requestResolution,
            retainEntityRef = retainEntityRef,
        )
    }

    fun enrichLibraryEntitiesForResolution(mediaIds: Collection<String>) {
        librarySurfaceHooks.enrichEntitiesForResolution(mediaIds)
    }

    fun enrichLibraryEntity(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        attributes: Any,
    ) {
        librarySurfaceHooks.enrichEntity(mediaId, entity, kind, attributes)
    }

    fun enrichLibraryEntityAssociations(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        attributes: Any,
        originalName: String?,
        originalArtist: String?,
        originalAlbum: String?,
    ) {
        val attributeArtistIds = mediaApiAttributeArtistIds(
            attributes = attributes,
            getterNames = listOf(
                AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_ID_METHOD,
                AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_ADAM_ID_METHOD,
                AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_STORE_ID_METHOD,
                AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_SUBSCRIPTION_STORE_ID_METHOD,
            ).map(::catalogMember),
        )
        val mediaApiArtistKeys = artistAssociationKeys(entity) +
            attributeArtistIds.map { artistId -> "id:$artistId" }
        val catalogArtistIds = libraryAssociatedArtistIds(
            kind = kind,
            mediaId = mediaId,
            attributeArtistIds = attributeArtistIds,
            associationKeys = mediaApiArtistKeys,
        )
        val existingArtistIds = metadataStore.associatedArtistIds(mediaId).orEmpty()
        val fallbackArtistId = if (
            catalogArtistIds.isEmpty() &&
            existingArtistIds.isEmpty() &&
            kind == InAppLibraryEntityKind.SONG
        ) {
            artistSurfaceHooks.fallbackArtistId(
                mediaId = mediaId,
                existingArtistIds = existingArtistIds,
                songArtistCredit = originalArtist,
            )
        } else {
            null
        }
        val associatedArtistIds =
            (existingArtistIds + catalogArtistIds + listOfNotNull(fallbackArtistId))
                .distinct()
        if (associatedArtistIds.isNotEmpty()) {
            metadataStore.mergeAssociatedArtistIds(mediaId, associatedArtistIds)
            trackAssociatedMediaIds(mediaId, associatedArtistIds)
        }
        val associationKeys = libraryEntityAssociationKeys(
            kind = kind,
            name = originalName,
            artist = originalArtist,
            album = originalAlbum,
        ) + mediaApiArtistKeys +
            associatedArtistIds.map { artistId -> "id:$artistId" } +
            if (kind == InAppLibraryEntityKind.ARTIST) setOf("id:$mediaId") else emptySet()
        if (associationKeys.isNotEmpty()) {
            metadataStore.mergeArtistKeys(mediaId, associationKeys)
            if (associatedArtistIds.isNotEmpty()) {
                stableArtistCacheKeys(associationKeys).forEach { artistKey ->
                    metadataStore.trackAssociatedMediaId(artistKey, mediaId)
                }
            }
            val originalLanguage = metadataStore.originalMetadata(mediaId)?.language
                ?.takeIf(String::isNotBlank)
            originalLanguage?.takeIf {
                kind != InAppLibraryEntityKind.SONG ||
                    host.shouldShareOriginalSongLanguage(
                        localizedTitle = originalName,
                        localizedArtist = originalArtist,
                        alias = metadataStore.originalMetadata(mediaId),
                    )
            }?.let { language ->
                host.rememberOriginalLanguageForArtist(mediaId, language)
            }
            inferredOriginalArtistLanguage(
                kind = kind,
                artist = originalArtist ?: originalName,
                associatedArtistIds = associatedArtistIds,
                genres = genreNames(attributes),
            )?.let { language ->
                host.rememberOriginalLanguageForArtist(mediaId, language)
            }
        }
        host.hydrateSharedArtistOverrides(mediaId)
        artistSurfaceHooks.clearTopSongCandidates(mediaId)
    }

    fun knownArtistProfileCredits(artistId: String): Set<String> = buildSet {
        metadataStore.accountMetadata(artistId)?.let { account ->
            account.title?.takeIf(String::isNotBlank)?.let(::add)
            account.artist?.takeIf(String::isNotBlank)?.let(::add)
        }
        val selection = host.configuredContentUiLanguage()
        listOfNotNull(
            metadataStore.configuredMetadata(artistId),
            metadataStore.configuredArtist(artistId),
            metadataStore.originalMetadata(artistId),
            metadataStore.originalArtist(artistId),
            metadataStore.sharedConfiguredArtist(selection, artistId),
            metadataStore.sharedOriginalArtist(artistId),
            catalogResolver.cachedLocalizedMetadata(
                selection = selection,
                entityType = AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
                mediaId = artistId,
            ),
            catalogResolver.cachedLocalizedArtist(
                selection = selection,
                artistKeys = setOf("id:$artistId"),
            ),
        ).forEach { alias ->
            alias.title.takeIf(String::isNotBlank)?.let(::add)
            alias.artist.takeIf(String::isNotBlank)?.let(::add)
        }
    }

    fun installRecentlySearchedHooks() {
        runCatching {
            val setData = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.RECENTLY_SEARCHED_CONTROLLER,
            )
            val onModelBound = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.RECENTLY_SEARCHED_MODEL_BOUND,
            )
            val mediaEntityClass = runtime.hookResolver.resolveClass(
                AppleMusicHookPoint.RECENTLY_SEARCHED_MEDIA_ENTITY,
            ).clazz
            val setDataMethod = setData.method
            val onModelBoundMethod = onModelBound.method

            runtime.hookRegistrar.installHook(setDataMethod, before = { chain ->
                val controller = chain.thisObject ?: return@installHook
                val entities = chain.args.firstOrNull() as? Iterable<*>
                    ?: return@installHook
                entities.forEach { entity ->
                    entity ?: return@forEach
                    if (!mediaEntityClass.isInstance(entity)) return@forEach
                    registerRecentlySearchedEntity(controller, entity, visible = false)
                }
            })
            runtime.hookRegistrar.installHook(onModelBoundMethod, after = { chain, _ ->
                val controller = chain.thisObject ?: return@installHook
                val model = chain.args.getOrNull(1) ?: return@installHook
                val entity = collectionPageRowEntity(model, mediaEntityClass)
                    ?: return@installHook
                registerRecentlySearchedEntity(controller, entity, visible = true)
            })
            ProviderLogger.info(
                "Apple Music 最近搜索元数据 Hook 已安装: " +
                    "setData=${setDataMethod.name}, bound=${onModelBoundMethod.name}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 最近搜索元数据 Hook 安装失败", it)
        }
    }

    private fun registerRecentlySearchedEntity(
        controller: Any,
        entity: Any,
        visible: Boolean,
    ) {
        val kind = inAppLibraryEntityKindForProfileClasses(
            entity = entity,
            resolvedClasses = libraryEntityRuntimeClasses,
        ) ?: return
        val attributes = entityAttributes(entity) ?: return
        val mediaId = entityCatalogId(entity, attributes) ?: return
        registerLibraryEntity(
            mediaId = mediaId,
            entity = entity,
            kind = kind,
            knownAttributes = attributes,
            requestResolution = false,
            retainEntityRef = true,
        )
        enrichLibraryEntity(mediaId, entity, kind, attributes)
        librarySurfaceHooks.registerController(mediaId, controller)
        host.effectiveAlias(mediaId)?.let { alias ->
            librarySurfaceHooks.applyAliasToEntity(entity, kind, alias)
        }
        if (!visible) return

        runtime.mainHandler.post {
            host.markMetadataVisible(listOf(mediaId))
            host.effectiveAlias(mediaId)?.let { alias ->
                host.applyAliasToMetadataRefs(
                    mediaId = mediaId,
                    alias = alias,
                    forceRebind = true,
                    notifyModelChange = true,
                )
            }
            if (host.shouldRequestOverride(mediaId)) {
                host.scheduleMetadataResolution(
                    mediaIds = listOf(mediaId),
                    priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                    originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
                )
            }
            if (BuildConfig.DEBUG) {
                val alias = host.effectiveAlias(mediaId)
                ProviderLogger.info(
                    "Apple Music 元数据链路: seq=${host.nextTraceSequence()}, " +
                        "event=recent_search_bound, contentId=$mediaId, kind=$kind, " +
                        "controller=${controller.javaClass.name}, " +
                        "entity=${entity.javaClass.name}, " +
                        "effective=${alias?.title}/${alias?.artist}/${alias?.album}, " +
                        "request=${host.shouldRequestOverride(mediaId)}"
                )
            }
        }
    }

    private fun libraryEntityAssociationKeys(
        kind: InAppLibraryEntityKind,
        name: String?,
        artist: String?,
        album: String?,
    ): Set<String> = buildSet {
        artist?.takeIf(String::isNotBlank)?.let { value ->
            add("name:${AppleInternalCatalogResolver.normalizedArtistNameKey(value)}")
        }
        val albumName = when (kind) {
            InAppLibraryEntityKind.ALBUM -> name
            InAppLibraryEntityKind.SONG -> album
            InAppLibraryEntityKind.ARTIST -> null
        }
        albumName?.takeIf(String::isNotBlank)?.let { value ->
            add("album:${AppleInternalCatalogResolver.normalizedArtistNameKey(value)}")
        }
        if (kind == InAppLibraryEntityKind.ARTIST) {
            name?.takeIf(String::isNotBlank)?.let { value ->
                add("name:${AppleInternalCatalogResolver.normalizedArtistNameKey(value)}")
            }
        }
    }

    private fun artistAssociationKeys(entity: Any): Set<String> = buildSet {
        val relationships = runCatching {
            AppleReflection.call(
                entity,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_RELATIONSHIPS_METHOD),
            ) as? Map<*, *>
        }.getOrNull() ?: return@buildSet
        val relationship = relationships["artists"] ?: relationships["artist"]
            ?: return@buildSet
        val rawArtists = runCatching {
            AppleReflection.call(
                relationship,
                catalogMember(AppleMusicRuntimeMember.CATALOG_RELATIONSHIP_ENTITIES_METHOD),
            ) ?: AppleReflection.call(
                relationship,
                catalogMember(AppleMusicRuntimeMember.CATALOG_RELATIONSHIP_DATA_METHOD),
            )
        }.getOrNull() ?: return@buildSet
        val artists: Iterable<*> = when (rawArtists) {
            is Iterable<*> -> rawArtists
            is Array<*> -> rawArtists.asIterable()
            is Map<*, *> -> rawArtists.values
            else -> return@buildSet
        }
        artists.forEach { artistEntity ->
            artistEntity ?: return@forEach
            entityCatalogId(artistEntity)?.let { artistId -> add("id:$artistId") }
            entityAttributes(artistEntity)
                ?.let { artistAttributes ->
                    attribute(artistAttributes, AppleMediaApiTextAttribute.NAME)
                }
                ?.takeIf(String::isNotBlank)
                ?.let { artistName ->
                    add("name:${AppleInternalCatalogResolver.normalizedArtistNameKey(artistName)}")
                }
        }
    }

    private fun genreNames(attributes: Any): List<String> {
        val values = runCatching {
            AppleReflection.call(
                attributes,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_GENRE_NAMES_METHOD),
            )
        }
            .getOrNull()
        val genres = when (values) {
            is Iterable<*> -> values
            is Array<*> -> values.asIterable()
            else -> emptyList<Any?>()
        }.mapNotNull { value ->
            value?.toString()?.trim()?.takeIf(String::isNotEmpty)
        }
        if (genres.isNotEmpty()) return genres
        return listOfNotNull(
            runCatching {
                AppleReflection.call(
                    attributes,
                    catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_GENRE_NAME_METHOD),
                ) as? String
            }
                .getOrNull()
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        )
    }

    private fun trackAssociatedMediaIds(mediaId: String, artistIds: Collection<String>) {
        AppleMetadataResolutionEngine.normalizedAssociatedArtistIds(artistIds).forEach { artistId ->
            metadataStore.trackAssociatedMediaId("id:$artistId", mediaId)
        }
    }

    private fun catalogMember(member: AppleMusicRuntimeMember): String =
        catalogTarget.runtimeMemberName(member)
}
