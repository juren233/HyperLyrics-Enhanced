/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

/** Owns in-app metadata/model identity registration and account-value capture. */
internal class AppleInAppMetadataRegistrationCoordinator(
    runtime: AppleMusicProviderRuntime,
    private val metadataStore: AppleMetadataOverrideStore,
    private val registry: AppleInAppMetadataRegistry,
    private val resolutionCoordinator: AppleInAppMetadataResolutionCoordinator,
    private val catalogResolver: AppleInternalCatalogResolver,
    private val contentItemMetadataHooks: AppleContentItemMetadataHooks,
    private val metadataApplier: AppleInAppMetadataApplier,
    private val surfaceRuntime: AppleMetadataSurfaceRuntime,
    private val dataBindingHooks: AppleDataBindingMetadataHooks,
    private val configuredContentUiLanguage: () -> Int,
) {
    private val metadataTarget = runtime.hookResolver.resolveMethod(
        AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT,
    ).target
    private val artistContainerClassName = runtime.hookResolver.resolveClass(
        AppleMusicHookPoint.IN_APP_CONTAINER_ARTIST_CLASS,
    ).target.className
    private val albumContainerClassName = runtime.hookResolver.resolveClass(
        AppleMusicHookPoint.IN_APP_CONTAINER_ALBUM_CLASS,
    ).target.className
    private val contentItemTarget by lazy {
        runtime.hookResolver.resolveClasses(AppleMusicHookPoint.CONTENT_ITEM_METADATA_CLASSES)
            .first { resolved ->
                resolved.target.runtimeMemberNameOrNull(AppleMusicRuntimeMember.CONTENT_ITEM_ROLE) ==
                    "base"
            }
    }

    fun registerMetadata(
        mediaId: String,
        metadata: Any,
        requestResolution: Boolean = true,
        preBind: Boolean = false,
        priority: AppleInternalCatalogResolver.RequestPriority =
            AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
    ) {
        dataBindingHooks.recordCurrentRecyclerMediaId(mediaId)
        if (priority == AppleInternalCatalogResolver.RequestPriority.VISIBLE) {
            surfaceRuntime.markVisible(listOf(mediaId))
        }
        registerMetadataRef(mediaId, metadata)
        resolutionCoordinator.effectiveAlias(mediaId)?.let { alias ->
            metadataApplier.applyAliasToMetadata(metadata, alias)
        }
        if (requestResolution && resolutionCoordinator.shouldRequestOverride(mediaId)) {
            resolutionCoordinator.ensureOverride(
                mediaId = mediaId,
                preBind = preBind,
                priority = priority,
            )
        }
    }

    fun registerPlaybackItem(
        mediaId: String,
        playbackItem: Any,
        notifyChange: Boolean = true,
        analyzeMetadata: Boolean = true,
    ) {
        dataBindingHooks.recordCurrentRecyclerMediaId(mediaId)
        val contract = playbackItemContract(playbackItem)
        val rawTitle = readPlaybackItemValue(playbackItem, InAppPlaybackItemField.TITLE, contract)
        val rawArtist = readPlaybackItemValue(playbackItem, InAppPlaybackItemField.ARTIST, contract)
        val rawCollectionName = readPlaybackItemValue(
            playbackItem,
            InAppPlaybackItemField.ALBUM,
            contract,
        )
        registry.registerPlaybackItem(
            mediaId = mediaId,
            playbackItem = playbackItem,
            originalTitle = rawTitle,
            originalArtist = rawArtist,
            originalCollectionName = rawCollectionName,
            contract = contract,
        )
        mergePlaybackAccountMetadata(
            mediaId = mediaId,
            title = rawTitle,
            artist = rawArtist,
            reconcileArtistAssociations = analyzeMetadata,
        )
        if (!analyzeMetadata) return
        val lookupIds = contentItemCatalogLookupIds(playbackItem, mediaId)
        val entityType = contentItemLocalizedEntityType(playbackItem)
        val artistKeys = contentItemArtistCacheKeys(
            playbackItem,
            rawArtist ?: rawTitle.takeIf {
                entityType == AppleInternalCatalogResolver.LocalizedEntityType.ARTIST
            },
        )
        if (artistKeys.isNotEmpty()) {
            metadataStore.mergeArtistKeys(mediaId, artistKeys)
            val associatedArtistIds = artistIdsFromAssociationKeys(artistKeys)
            resolutionCoordinator.mergePlaybackAssociatedArtistIds(mediaId, associatedArtistIds)
            if (
                !metadataStore.hasConfiguredMetadata(mediaId) &&
                shouldAcceptAssociatedArtistResolution(
                    requestedArtistIds = associatedArtistIds,
                    currentArtistIds = metadataStore.associatedArtistIds(mediaId).orEmpty(),
                    artistCredit = resolutionCoordinator.associatedArtistCredit(mediaId),
                )
            ) {
                catalogResolver.cachedLocalizedArtist(
                    configuredContentUiLanguage(),
                    localizedArtistCacheKeys(artistKeys),
                )?.let { artistAlias ->
                    metadataStore.rememberConfiguredArtist(mediaId, artistAlias)
                    resolutionCoordinator.effectiveAlias(mediaId)?.let { effectiveAlias ->
                        metadataApplier.applyAliasToPlaybackItem(
                            playbackItem,
                            effectiveAlias,
                            notifyChange,
                        )
                    }
                }
            }
        }
        metadataStore.mergeLookupIds(mediaId, lookupIds)
        if (entityType == null) {
            metadataStore.markNonCatalogContent(mediaId)
        } else {
            metadataStore.markCatalogContent(mediaId)
            metadataStore.rememberEntityType(mediaId, entityType)
        }
    }

    fun registerContainerItem(
        mediaId: String,
        containerItem: Any,
        kind: InAppContainerKind,
    ) {
        registry.registerContainerItem(
            mediaId = mediaId,
            containerItem = containerItem,
            kind = kind,
            originalTitle = rawContentItemValue(
                containerItem,
                AppleMusicRuntimeMember.CONTENT_ITEM_TITLE_FIELD,
            ) as? String,
        )
        ProviderLogger.info(
            "Apple Music 播放页跳转项捕获: id=$mediaId, kind=$kind, " +
                "class=${containerItem.javaClass.name}"
        )
    }

    fun containerKind(containerItem: Any): InAppContainerKind? {
        val classNames = generateSequence(containerItem.javaClass) { it.superclass }
            .map(Class<*>::getName)
            .toSet()
        return when {
            artistContainerClassName in classNames -> InAppContainerKind.ARTIST
            albumContainerClassName in classNames -> InAppContainerKind.ALBUM
            else -> null
        }
    }

    fun markContainerNavigationItem(
        containerItem: Any,
        kind: InAppContainerKind,
        mediaId: String,
    ) = registry.markContainerNavigationItem(containerItem, kind, mediaId)

    fun containerNavigationBinding(containerItem: Any): InAppContainerNavigationRef? =
        registry.containerNavigationBinding(containerItem)

    fun rawContentItemValue(
        contentItem: Any,
        runtimeMember: AppleMusicRuntimeMember,
    ): Any? = runCatching {
        AppleReflection.field(
            contentItem,
            contentItemTarget.target.runtimeMemberName(runtimeMember),
        )
    }.getOrNull()

    fun playbackItemContract(playbackItem: Any): InAppPlaybackItemContract =
        registry.playbackItemContract(playbackItem)

    fun readPlaybackItemValue(
        playbackItem: Any,
        field: InAppPlaybackItemField,
        contract: InAppPlaybackItemContract = playbackItemContract(playbackItem),
    ): String? {
        val access = inAppPlaybackItemAccess(contract, field) ?: return null
        val value = if (access.readViaMethod) {
            runCatching {
                contentItemMetadataHooks.withOriginalGetters {
                    AppleReflection.call(
                        playbackItem,
                        contentItemTarget.target.runtimeMemberName(access.readMember),
                    )
                }
            }.getOrNull()
        } else {
            rawContentItemValue(playbackItem, access.readMember)
        }
        return value?.toString()
    }

    fun contentItemCatalogLookupIds(contentItem: Any, mediaId: String): Set<String> = buildSet {
        fun addString(value: Any?) {
            value?.toString()?.trim()?.takeIf { candidate ->
                candidate.isNotEmpty() && candidate.all(Char::isDigit)
            }?.let(::add)
        }
        addString(mediaId)
        listOf(
            AppleMusicRuntimeMember.CONTENT_ITEM_SUBSCRIPTION_STORE_ID_GETTER,
            AppleMusicRuntimeMember.CONTENT_ITEM_ID_GETTER,
        ).forEach { runtimeMember ->
            addString(
                runCatching {
                    AppleReflection.call(
                        contentItem,
                        contentItemTarget.target.runtimeMemberName(runtimeMember),
                    )
                }.getOrNull()
            )
        }
        listOf(
            AppleMusicRuntimeMember.CONTENT_ITEM_ASSET_ADAM_ID_GETTER,
            AppleMusicRuntimeMember.CONTENT_ITEM_REPORTING_ADAM_ID_GETTER,
        ).forEach { runtimeMember ->
            val value = runCatching {
                AppleReflection.call(
                    contentItem,
                    contentItemTarget.target.runtimeMemberName(runtimeMember),
                ) as? Long
            }
                .getOrNull()
            value?.takeIf { it > 0L }?.let(::addString)
        }
        val formerIds = runCatching {
            AppleReflection.call(
                contentItem,
                contentItemTarget.target.runtimeMemberName(
                    AppleMusicRuntimeMember.CONTENT_ITEM_FORMER_IDS_GETTER,
                ),
            ) as? Array<*>
        }.getOrNull().orEmpty()
        formerIds.forEach(::addString)
    }

    fun contentItemArtistCacheKeys(contentItem: Any, rawArtist: String?): Set<String> = buildSet {
        rawArtist?.takeIf(String::isNotBlank)?.let { artist ->
            add("name:${AppleInternalCatalogResolver.normalizedArtistNameKey(artist)}")
        }
        listOf(
            AppleMusicRuntimeMember.CONTENT_ITEM_ARTIST_ID_GETTER,
            AppleMusicRuntimeMember.CONTENT_ITEM_ARTIST_ADAM_ID_GETTER,
            AppleMusicRuntimeMember.CONTENT_ITEM_ARTIST_STORE_ID_GETTER,
            AppleMusicRuntimeMember.CONTENT_ITEM_ARTIST_SUBSCRIPTION_STORE_ID_GETTER,
        ).forEach { runtimeMember ->
            val value = runCatching {
                AppleReflection.call(
                    contentItem,
                    contentItemTarget.target.runtimeMemberName(runtimeMember),
                )
            }.getOrNull()
            value?.toString()?.trim()?.takeIf { id ->
                id.isNotEmpty() && id.all(Char::isDigit)
            }?.let { add("id:$it") }
        }
    }

    fun contentItemLocalizedEntityType(
        contentItem: Any,
    ): AppleInternalCatalogResolver.LocalizedEntityType? = localizedEntityTypeForQueueItem(
        historyEntry = playbackItemContract(contentItem) == InAppPlaybackItemContract.HISTORY,
        classNames = generateSequence(contentItem.javaClass as Class<*>?) { it.superclass }
            .map { it.simpleName }
            .toList(),
    )

    private fun registerMetadataRef(mediaId: String, metadata: Any) {
        val originalTitle = AppleReflection.field(
            metadata,
            metadataTarget.runtimeMemberName(AppleMusicRuntimeMember.MEDIA3_METADATA_TITLE_FIELD),
        )
        val originalArtist = AppleReflection.field(
            metadata,
            metadataTarget.runtimeMemberName(AppleMusicRuntimeMember.MEDIA3_METADATA_ARTIST_FIELD),
        )
        mergePlaybackAccountMetadata(mediaId, originalTitle?.toString(), originalArtist?.toString())
        registry.registerMetadata(
            mediaId = mediaId,
            metadata = metadata,
            originalTitle = originalTitle,
            originalArtist = originalArtist,
        )
    }

    fun mergePlaybackAccountMetadata(
        mediaId: String,
        title: String?,
        artist: String?,
        reconcileArtistAssociations: Boolean = true,
    ) {
        val incoming = AccountMetadata(
            title = title?.takeIf(String::isNotBlank),
            artist = artist?.takeIf(String::isNotBlank),
        )
        if (incoming.title == null && incoming.artist == null) return
        val previousArtist = metadataStore.accountMetadata(mediaId)?.artist
        val merged = metadataStore.mergeAccountMetadata(mediaId, incoming)
        if (reconcileArtistAssociations) {
            resolutionCoordinator.enforceAssociatedArtistIsolation(
                mediaId = mediaId,
                resetSafeResolution = previousArtist != merged.artist,
            )
            resolutionCoordinator.hydrateSharedArtistOverrides(mediaId)
        }
    }
}
