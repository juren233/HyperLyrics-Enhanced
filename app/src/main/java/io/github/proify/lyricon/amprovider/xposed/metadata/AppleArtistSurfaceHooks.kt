/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import androidx.recyclerview.widget.RecyclerView
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

internal interface AppleArtistSurfaceHost {
    fun mediaApiEntityAttributes(entity: Any): Any?

    fun mediaApiEntityCatalogId(entity: Any, knownAttributes: Any? = null): String?

    fun mediaApiAttribute(attributes: Any, getter: String): String?

    fun registerLibraryEntity(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        knownAttributes: Any? = null,
    )

    fun enrichLibraryEntity(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        attributes: Any,
    )

    fun markMetadataVisible(mediaIds: Collection<String>)

    fun enrichLibraryEntitiesForResolution(mediaIds: Collection<String>)

    fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias?

    fun applyAliasToMetadataRefs(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        notifyModelChange: Boolean,
    )

    fun shouldRequestOverride(mediaId: String): Boolean

    fun scheduleMetadataResolution(
        mediaIds: Collection<String>,
        priority: AppleInternalCatalogResolver.RequestPriority,
        originalResolutionMode: InAppOriginalResolutionMode,
    )

    fun activeMetadataPageOwner(): Any?

    fun knownArtistProfileCredits(artistId: String): Set<String>

    fun onMetadataPageAttached(owner: Any, recycler: RecyclerView)

    fun onMetadataPageDetached(owner: Any)

    fun nextMetadataTraceSequence(): Long

    fun logMetadataIdentity(event: String, details: String)
}

internal class AppleArtistSurfaceHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val metadataStore: AppleMetadataOverrideStore,
    private val librarySurfaceHooks: AppleLibrarySurfaceHooks,
    private val dataBindingHooks: AppleDataBindingMetadataHooks,
    private val host: AppleArtistSurfaceHost,
) {
    private val pageBuildData = Collections.synchronizedMap(WeakHashMap<Any, ArtistPageBuildData>())
    private val topSongModels = WeakIdentityMap<Any, ArtistTopSongModelSnapshot>()
    private val topSongBindings = WeakIdentityMap<Any, ArtistTopSongModelSnapshot>()
    private val profileMediaIds = WeakIdentityMap<Any, String>()
    private val headerModelIds = WeakIdentityMap<Any, String>()
    private val headerBindingIds = WeakIdentityMap<Any, String>()
    private val finalBoundResolutionIds = WeakIdentityMap<Any, String>()
    private val topSongCandidateArtistIds =
        ConcurrentHashMap<String, MutableSet<String>>()

    @Volatile
    private var latestProfileMediaId: String? = null

    fun installTopSongHooks() {
        val classes = artistClasses() ?: return
        val recycler = checkNotNull(classes["recycler"])
        val mediaEntity = checkNotNull(classes["media_entity"])
        val baseController = checkNotNull(classes["base_controller"])
        val artistController = checkNotNull(classes["artist_controller"])
        val topSongModel = checkNotNull(classes["top_song_model"])
        installPageLifecycle(artistController, recycler.clazz)

        runCatching {
            val buildMethod = AppleReflection.findMethod(
                baseController.clazz,
                baseController.target.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTIST_TOP_SONG_BUILD_METHOD
                ),
                parameterTypes = listOf(
                    String::class.java,
                    mediaEntity.clazz,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                ),
            )
            val modelBindMethod = AppleReflection.findMethod(
                topSongModel.clazz,
                topSongModel.target.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTIST_MODEL_BIND_METHOD
                ),
                parameterTypes = listOf(Int::class.javaPrimitiveType!!, Any::class.java),
            )
            val titleField = topSongModel.target.runtimeMemberName(
                AppleMusicRuntimeMember.ARTIST_TOP_SONG_TITLE_FIELD
            )
            val subtitleField = topSongModel.target.runtimeMemberName(
                AppleMusicRuntimeMember.ARTIST_TOP_SONG_SUBTITLE_FIELD
            )
            val captionField = topSongModel.target.runtimeMemberName(
                AppleMusicRuntimeMember.ARTIST_TOP_SONG_CAPTION_FIELD
            )

            runtime.hookRegistrar.installHook(
                buildMethod,
                before = { chain ->
                    val controller = chain.thisObject ?: return@installHook
                    val entity = chain.args.getOrNull(1) ?: return@installHook
                    val mediaId = artistProfileTopSongMediaId(
                        relationshipKey = chain.args.getOrNull(0),
                        mediaId = host.mediaApiEntityCatalogId(entity),
                    ) ?: return@installHook
                    host.registerLibraryEntity(
                        mediaId = mediaId,
                        entity = entity,
                        kind = InAppLibraryEntityKind.SONG,
                    )
                    associateTopSongWithProfileArtist(controller, mediaId)
                },
                after = { chain, result ->
                    val model = result ?: return@installHook
                    val entity = chain.args.getOrNull(1) ?: return@installHook
                    val mediaId = artistProfileTopSongMediaId(
                        relationshipKey = chain.args.getOrNull(0),
                        mediaId = librarySurfaceHooks.entityMediaId(entity)
                            ?: host.mediaApiEntityCatalogId(entity),
                    ) ?: return@installHook
                    val snapshot = ArtistTopSongModelSnapshot(
                        mediaId = mediaId,
                        originalTitle = reflectiveField(model, titleField)?.toString(),
                        originalSubtitle = reflectiveField(model, subtitleField)?.toString(),
                        originalArtist = metadataStore.accountMetadata(mediaId)?.artist,
                    )
                    topSongModels[model] = snapshot
                    if (BuildConfig.DEBUG) {
                        ProviderLogger.info(
                            "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                                "event=artist_top_songs_capture, contentId=$mediaId, " +
                                "relationshipKey=${chain.args.getOrNull(0)}, " +
                                "entity=${entity.javaClass.name}@${System.identityHashCode(entity)}, " +
                                "model=${model.javaClass.name}@${System.identityHashCode(model)}, " +
                                "modelTitle=${reflectiveField(model, titleField)}, " +
                                "modelSubtitle=${reflectiveField(model, subtitleField)}, " +
                                "modelCaption=${reflectiveField(model, captionField)}, " +
                                "profileArtistId=${profileMediaIds[chain.thisObject]}, " +
                                "artistIds=${metadataStore.associatedArtistIds(mediaId)}"
                        )
                    }
                },
            )
            runtime.hookRegistrar.installHook(
                modelBindMethod,
                before = { chain -> bindTopSongModel(chain.thisObject, chain.args.getOrNull(1), true) },
                after = { chain, _ ->
                    val model = chain.thisObject ?: return@installHook
                    val binding = bindTopSongModel(model, chain.args.getOrNull(1), false)
                    if (BuildConfig.DEBUG) {
                        val snapshot = topSongModels[model] ?: return@installHook
                        val root = binding?.let(dataBindingHooks::root)
                        ProviderLogger.info(
                            "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                                "event=artist_top_songs_visible, contentId=${snapshot.mediaId}, " +
                                "position=${chain.args.getOrNull(0)}, " +
                                "model=${model.javaClass.name}@${System.identityHashCode(model)}, " +
                                "modelTitle=${reflectiveField(model, titleField)}, " +
                                "modelSubtitle=${reflectiveField(model, subtitleField)}, " +
                                "binding=${binding?.javaClass?.name}@" +
                                "${binding?.let(System::identityHashCode)}, " +
                                "bindingMediaId=${binding?.let(dataBindingHooks::mediaId)}, " +
                                "rootVisible=${root?.let(dataBindingHooks::isRootVisible) == true}, " +
                                "texts=${root?.let(dataBindingHooks::renderedTexts)}"
                        )
                    }
                },
            )
            ProviderLogger.info(
                "Apple Music 歌手页歌曲排行元数据 Hook 已安装: " +
                    "builder=${buildMethod.name}, binder=${modelBindMethod.name}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 歌手页歌曲排行元数据 Hook 安装失败", it)
        }
    }

    fun installProfileHooks() {
        val classes = artistClasses() ?: return
        val mediaEntity = checkNotNull(classes["media_entity"])
        val artistController = checkNotNull(classes["artist_controller"])
        val headerModel = checkNotNull(classes["header_model"])
        runCatching {
            val buildMethod = AppleReflection.findMethod(
                artistController.clazz,
                artistController.target.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTIST_PROFILE_BUILD_METHOD
                ),
                parameterTypes = listOf(
                    mediaEntity.clazz,
                    Boolean::class.javaPrimitiveType!!,
                    Set::class.java,
                ),
            )
            val headerBindMethod = AppleReflection.findMethod(
                headerModel.clazz,
                headerModel.target.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTIST_MODEL_BIND_METHOD
                ),
                parameterTypes = listOf(Int::class.javaPrimitiveType!!, Any::class.java),
            )
            val headerTitleField = headerModel.target.runtimeMemberName(
                AppleMusicRuntimeMember.ARTIST_HEADER_TITLE_FIELD
            )

            runtime.hookRegistrar.installHook(
                buildMethod,
                before = { chain ->
                    val controller = chain.thisObject ?: return@installHook
                    val entity = chain.args.firstOrNull() ?: return@installHook
                    val attributes = host.mediaApiEntityAttributes(entity) ?: return@installHook
                    val mediaId = host.mediaApiEntityCatalogId(entity, attributes)
                        ?: return@installHook
                    profileMediaIds[controller] = mediaId
                    latestProfileMediaId = mediaId
                    pageBuildData[controller] = ArtistPageBuildData(
                        artist = entity,
                        isAddMusicMode = chain.args.getOrNull(1) as? Boolean
                            ?: return@installHook,
                        selectedItemIds = chain.args.getOrNull(2),
                    )
                    host.registerLibraryEntity(
                        mediaId = mediaId,
                        entity = entity,
                        kind = InAppLibraryEntityKind.ARTIST,
                        knownAttributes = attributes,
                    )
                    host.enrichLibraryEntity(
                        mediaId = mediaId,
                        entity = entity,
                        kind = InAppLibraryEntityKind.ARTIST,
                        attributes = attributes,
                    )
                    librarySurfaceHooks.registerController(mediaId, controller)
                    host.effectiveAlias(mediaId)?.let { alias ->
                        librarySurfaceHooks.applyAliasToEntity(
                            entity = entity,
                            kind = InAppLibraryEntityKind.ARTIST,
                            alias = alias,
                        )
                    }
                    if (BuildConfig.DEBUG) {
                        ProviderLogger.info(
                            "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                                "event=artist_profile_build, contentId=$mediaId, " +
                                "entity=${entity.javaClass.name}@${System.identityHashCode(entity)}, " +
                                "attributeName=${host.mediaApiAttribute(attributes, "getName")}, " +
                                "artistIds=${metadataStore.associatedArtistIds(mediaId)}, " +
                                "effective=${host.effectiveAlias(mediaId)?.let {
                                    "${it.title}/${it.artist}/${it.album}"
                                }}"
                        )
                    }
                },
                after = { chain, _ ->
                    val controller = chain.thisObject ?: return@installHook
                    val mediaId = profileMediaIds[controller] ?: return@installHook
                    runtime.mainHandler.post {
                        host.markMetadataVisible(listOf(mediaId))
                        host.enrichLibraryEntitiesForResolution(listOf(mediaId))
                        host.effectiveAlias(mediaId)?.let { alias ->
                            host.applyAliasToMetadataRefs(
                                mediaId = mediaId,
                                alias = alias,
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
                    }
                },
            )
            runtime.hookRegistrar.installHook(
                headerBindMethod,
                before = { chain -> bindHeaderModel(
                    model = chain.thisObject,
                    holder = chain.args.getOrNull(1),
                    beginModelBind = true,
                    headerTitleField = headerTitleField,
                ) },
                after = { chain, _ ->
                    val binding = bindHeaderModel(
                        model = chain.thisObject,
                        holder = chain.args.getOrNull(1),
                        beginModelBind = false,
                        headerTitleField = headerTitleField,
                    )
                    if (BuildConfig.DEBUG) {
                        val model = chain.thisObject ?: return@installHook
                        val mediaId = headerMediaId(model, headerTitleField) ?: return@installHook
                        val root = binding?.let(dataBindingHooks::root)
                        ProviderLogger.info(
                            "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                                "event=artist_profile_header_visible, contentId=$mediaId, " +
                                "position=${chain.args.getOrNull(0)}, " +
                                "model=${model.javaClass.name}@${System.identityHashCode(model)}, " +
                                "modelTitle=${reflectiveField(model, headerTitleField)}, " +
                                "binding=${binding?.javaClass?.name}@" +
                                "${binding?.let(System::identityHashCode)}, " +
                                "bindingMediaId=${binding?.let(dataBindingHooks::mediaId)}, " +
                                "rootVisible=${root?.let(dataBindingHooks::isRootVisible) == true}, " +
                                "texts=${root?.let(dataBindingHooks::renderedTexts)}"
                        )
                    }
                },
            )
            ProviderLogger.info(
                "Apple Music 歌手页标题实时元数据 Hook 已安装: " +
                    "builder=${buildMethod.name}, binder=${headerBindMethod.name}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 歌手页标题实时元数据 Hook 安装失败", it)
        }
    }

    fun handleFinalBinding(model: Any, finalHolder: Any?, position: Int?) {
        val classes = artistClasses() ?: return
        val topSongClass = classes["top_song_model"]?.clazz
        val headerResolved = classes["header_model"] ?: return
        val headerTitleField = headerResolved.target.runtimeMemberName(
            AppleMusicRuntimeMember.ARTIST_HEADER_TITLE_FIELD
        )
        when {
            topSongClass?.isInstance(model) == true -> {
                val snapshot = topSongModels[model] ?: return
                val binding = bindingFromFinalHolder(finalHolder)
                if (binding != null) {
                    topSongBindings[binding] = snapshot
                    dataBindingHooks.capture(binding)
                    dataBindingHooks.register(
                        mediaId = snapshot.mediaId,
                        binding = binding,
                        originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
                    )
                }
                onFinalBound(
                    model = model,
                    mediaId = snapshot.mediaId,
                    kind = MetadataPageFinalBindingKind.ARTIST_TOP_SONG,
                    position = position,
                    binding = binding,
                )
            }

            headerResolved.clazz.isInstance(model) -> {
                val mediaId = headerMediaId(model, headerTitleField) ?: return
                headerModelIds[model] = mediaId
                val binding = bindingFromFinalHolder(finalHolder)
                if (binding != null) {
                    headerBindingIds[binding] = mediaId
                    dataBindingHooks.capture(binding)
                    dataBindingHooks.register(
                        mediaId = mediaId,
                        binding = binding,
                        originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
                    )
                }
                onFinalBound(
                    model = model,
                    mediaId = mediaId,
                    kind = MetadataPageFinalBindingKind.ARTIST_HEADER,
                    position = position,
                    binding = binding,
                )
            }
        }
    }

    fun hasBuildData(controller: Any): Boolean = pageBuildData[controller] != null

    fun requestControllerBuild(controller: Any): Boolean {
        val buildData = pageBuildData[controller] ?: return false
        val target = artistClasses()?.get("artist_controller")?.target ?: return false
        AppleReflection.call(
            controller,
            target.runtimeMemberName(AppleMusicRuntimeMember.ARTIST_CONTROLLER_SET_DATA_METHOD),
            buildData.artist,
            buildData.isAddMusicMode,
            buildData.selectedItemIds,
        )
        return true
    }

    fun clearController(controller: Any) {
        pageBuildData.remove(controller)
        val detachedMediaId = profileMediaIds[controller]
        profileMediaIds.remove(controller)
        if (latestProfileMediaId == detachedMediaId) latestProfileMediaId = null
    }

    fun fallbackArtistId(
        mediaId: String,
        existingArtistIds: List<String>,
        songArtistCredit: String?,
    ): String? = topSongCandidateArtistIds[mediaId].orEmpty()
        .mapNotNull { profileArtistId ->
            artistProfileFallbackArtistId(
                profileArtistId = profileArtistId,
                existingArtistIds = existingArtistIds,
                songArtistCredit = songArtistCredit,
                profileArtistCredits = host.knownArtistProfileCredits(profileArtistId),
            )
        }
        .distinct()
        .singleOrNull()

    fun clearTopSongCandidates(mediaId: String) {
        topSongCandidateArtistIds.remove(mediaId)
    }

    fun onBeginBindingModel(binding: Any) {
        headerBindingIds.remove(binding)
    }

    fun onBindingMediaIdChanged(binding: Any, mediaId: String) {
        topSongBindings[binding]
            ?.takeIf { snapshot -> snapshot.mediaId != mediaId }
            ?.let { topSongBindings.remove(binding) }
        headerBindingIds[binding]
            ?.takeIf { artistId -> artistId != mediaId }
            ?.let { headerBindingIds.remove(binding) }
    }

    fun originalResolutionMode(binding: Any): InAppOriginalResolutionMode =
        if (topSongBindings[binding] != null) {
            InAppOriginalResolutionMode.ORIGINAL_FIRST
        } else {
            InAppOriginalResolutionMode.AFTER_LOCALIZED
        }

    fun shouldInvalidateAppliedAlias(
        binding: Any,
        mediaId: String,
        appliedAlias: AppliedMetadataAlias,
        pendingAlias: AppliedMetadataAlias?,
        effectiveAlias: AppleInternalCatalogResolver.Alias,
        expectedTitle: String?,
        renderedTexts: Collection<String>,
    ): Boolean {
        if (headerBindingIds[binding] != mediaId) return false
        return shouldInvalidateArtistHeaderAppliedAlias(
            appliedAlias = appliedAlias,
            effectiveAlias = AppliedMetadataAlias(mediaId, effectiveAlias),
            pendingAlias = pendingAlias,
            expectedTitle = expectedTitle,
            renderedTexts = renderedTexts,
        )
    }

    fun subtitleForBinding(
        binding: Any?,
        defaultSubtitle: String?,
        replacementArtist: String,
    ): String? {
        val topSong = binding?.let { topSongBindings[it] } ?: return defaultSubtitle
        return artistProfileSubtitleWithArtist(
            originalSubtitle = topSong.originalSubtitle,
            originalArtist = topSong.originalArtist,
            replacementArtist = replacementArtist,
        )
    }

    fun isRecyclerAdapter(adapter: Any): Boolean {
        val controllerClass = artistClasses()?.get("artist_controller")?.clazz ?: return false
        if (controllerClass.isInstance(adapter)) return true
        return generateSequence(adapter.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { field ->
                !java.lang.reflect.Modifier.isStatic(field.modifiers) && !field.type.isPrimitive
            }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(adapter)
                }.getOrNull()
            }
            .any(controllerClass::isInstance)
    }

    private fun installPageLifecycle(
        controller: ResolvedAppleMusicHookClass,
        recyclerClass: Class<*>,
    ) {
        runCatching {
            val attached = AppleReflection.findMethod(
                controller.clazz,
                controller.target.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTIST_CONTROLLER_ATTACH_METHOD
                ),
                parameterTypes = listOf(recyclerClass),
            )
            val detached = AppleReflection.findMethod(
                controller.clazz,
                controller.target.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTIST_CONTROLLER_DETACH_METHOD
                ),
                parameterTypes = listOf(recyclerClass),
            )
            runtime.hookRegistrar.installHook(attached, after = { chain, _ ->
                val owner = chain.thisObject ?: return@installHook
                val recycler = chain.args.firstOrNull() as? RecyclerView ?: return@installHook
                host.onMetadataPageAttached(owner, recycler)
            })
            runtime.hookRegistrar.installHook(detached, before = { chain ->
                val owner = chain.thisObject ?: return@installHook
                clearController(owner)
                host.onMetadataPageDetached(owner)
            })
            ProviderLogger.info("Apple Music 歌手页元数据页面边界 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple Music 歌手页元数据页面边界 Hook 安装失败", it)
        }
    }

    private fun bindTopSongModel(model: Any?, holder: Any?, beginModelBind: Boolean): Any? {
        model ?: return null
        val snapshot = topSongModels[model] ?: return null
        val binding = dataBindingHooks.bindingFromHolder(holder) ?: return null
        if (beginModelBind) dataBindingHooks.beginModelBind(binding)
        topSongBindings[binding] = snapshot
        dataBindingHooks.capture(binding)
        dataBindingHooks.register(
            mediaId = snapshot.mediaId,
            binding = binding,
            originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
        )
        return binding
    }

    private fun bindHeaderModel(
        model: Any?,
        holder: Any?,
        beginModelBind: Boolean,
        headerTitleField: String,
    ): Any? {
        model ?: return null
        val mediaId = headerMediaId(model, headerTitleField) ?: return null
        headerModelIds[model] = mediaId
        val binding = dataBindingHooks.bindingFromHolder(holder) ?: return null
        if (beginModelBind) dataBindingHooks.beginModelBind(binding)
        headerBindingIds[binding] = mediaId
        dataBindingHooks.capture(binding)
        dataBindingHooks.register(mediaId, binding)
        return binding
    }

    private fun headerMediaId(model: Any, headerTitleField: String): String? {
        headerModelIds[model]?.let { return it }
        host.activeMetadataPageOwner()
            ?.let { owner -> profileMediaIds[owner] }
            ?.let { return it }
        val latestMediaId = latestProfileMediaId ?: return null
        val modelTitle = reflectiveField(model, headerTitleField)?.toString().orEmpty()
        val accountTitle = metadataStore.accountMetadata(latestMediaId)?.title.orEmpty()
        val modelKey = AppleInternalCatalogResolver.normalizedArtistNameKey(modelTitle)
        val accountKey = AppleInternalCatalogResolver.normalizedArtistNameKey(accountTitle)
        return latestMediaId.takeIf { modelKey.isNotEmpty() && modelKey == accountKey }
    }

    private fun bindingFromFinalHolder(holder: Any?): Any? {
        val methodName = runtime.hookResolver.resolveMethod(AppleMusicHookPoint.EPOXY_FINAL_BIND)
            .target
            .runtimeMemberName(
                AppleMusicRuntimeMember.EPOXY_FINAL_HOLDER_MODEL_HOLDER_METHOD
            )
        val modelHolder = holder?.let {
            runCatching { AppleReflection.call(it, methodName) }.getOrNull()
        }
        return dataBindingHooks.bindingFromHolder(modelHolder)
            ?: dataBindingHooks.bindingFromHolder(holder)
    }

    private fun onFinalBound(
        model: Any,
        mediaId: String,
        kind: MetadataPageFinalBindingKind,
        position: Int?,
        binding: Any?,
    ) {
        val shouldResolve = finalBoundResolutionIds[model] != mediaId
        finalBoundResolutionIds[model] = mediaId
        runtime.mainHandler.post {
            host.markMetadataVisible(listOf(mediaId))
            host.enrichLibraryEntitiesForResolution(listOf(mediaId))
            val alias = host.effectiveAlias(mediaId)
            val shouldRequest = shouldResolve && host.shouldRequestOverride(mediaId)
            if (alias != null) {
                host.applyAliasToMetadataRefs(
                    mediaId = mediaId,
                    alias = alias,
                    notifyModelChange = false,
                )
            }
            if (shouldRequest) {
                host.scheduleMetadataResolution(
                    mediaIds = listOf(mediaId),
                    priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                    originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
                )
            }
            if (BuildConfig.DEBUG) {
                val root = binding?.let(dataBindingHooks::root)
                ProviderLogger.info(
                    "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                        "event=artist_profile_final_bound, contentId=$mediaId, kind=$kind, " +
                        "position=$position, model=${model.javaClass.name}@" +
                        "${System.identityHashCode(model)}, " +
                        "binding=${binding?.javaClass?.name}@" +
                        "${binding?.let(System::identityHashCode)}, " +
                        "rootVisible=${root?.let(dataBindingHooks::isRootVisible) == true}, " +
                        "effective=${alias?.title}/${alias?.artist}/${alias?.album}, " +
                        "request=$shouldRequest"
                )
            }
        }
    }

    private fun associateTopSongWithProfileArtist(controller: Any, mediaId: String) {
        val profileArtistId = profileMediaIds[controller] ?: return
        topSongCandidateArtistIds.computeIfAbsent(mediaId) {
            ConcurrentHashMap.newKeySet()
        }.add(profileArtistId)
    }

    private fun artistClasses(): Map<String, ResolvedAppleMusicHookClass>? = runCatching {
        runtime.hookResolver.resolveClasses(AppleMusicHookPoint.ARTIST_SURFACE_CLASSES)
            .associateBy { resolved ->
                resolved.target.runtimeMemberName(AppleMusicRuntimeMember.ARTIST_RUNTIME_ROLE)
            }
    }.onFailure {
        ProviderLogger.error("Apple Music 歌手页运行时类解析失败", it)
    }.getOrNull()

    private fun reflectiveField(instance: Any, name: String): Any? =
        runCatching { AppleReflection.field(instance, name) }.getOrNull()
}

internal fun shouldInvalidateArtistHeaderAppliedAlias(
    appliedAlias: AppliedMetadataAlias?,
    effectiveAlias: AppliedMetadataAlias?,
    pendingAlias: AppliedMetadataAlias?,
    expectedTitle: String?,
    renderedTexts: Collection<String>,
): Boolean {
    if (appliedAlias == null || appliedAlias != effectiveAlias) return false
    if (pendingAlias == effectiveAlias) return false
    val expected = expectedTitle?.trim()?.takeIf(String::isNotEmpty) ?: return false
    val rendered = renderedTexts.map(String::trim).filter(String::isNotEmpty)
    return rendered.isNotEmpty() && expected !in rendered
}

internal fun isArtistProfileControllerClassNames(classNames: Iterable<String>): Boolean =
    classNames.any { className ->
        className == "com.apple.android.music.profiles.ArtistEpoxyController"
    }

internal fun artistProfileTopSongMediaId(
    relationshipKey: Any?,
    mediaId: String?,
): String? {
    if (relationshipKey != "top-songs") return null
    return mediaId
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
}

internal fun artistProfileFallbackArtistId(
    profileArtistId: String?,
    existingArtistIds: Collection<String>,
    songArtistCredit: String?,
    profileArtistCredits: Collection<String>,
): String? {
    if (existingArtistIds.isNotEmpty()) return null
    val artistId = profileArtistId
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?: return null
    val credit = songArtistCredit?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (AppleInternalCatalogResolver.isCollaborationArtistName(credit)) return null
    val creditKey = AppleInternalCatalogResolver.normalizedArtistNameKey(credit)
        .takeIf(String::isNotEmpty)
        ?: return null
    val knownCreditKeys = profileArtistCredits.asSequence()
        .map(AppleInternalCatalogResolver::normalizedArtistNameKey)
        .filter(String::isNotEmpty)
        .toSet()
    return artistId.takeIf { creditKey in knownCreditKeys }
}

internal fun artistProfileSubtitleWithArtist(
    originalSubtitle: String?,
    originalArtist: String?,
    replacementArtist: String?,
): String? {
    val subtitle = originalSubtitle?.takeIf(String::isNotBlank) ?: return null
    val original = originalArtist?.trim()?.takeIf(String::isNotEmpty) ?: return subtitle
    val replacement = replacementArtist?.trim()?.takeIf(String::isNotEmpty) ?: return subtitle
    if (original == replacement) return subtitle
    if (subtitle == original) return replacement

    if (subtitle.startsWith(original)) {
        val suffix = subtitle.substring(original.length)
        val boundary = suffix.firstOrNull()
        if (
            boundary == null ||
            boundary.isWhitespace() ||
            boundary in setOf('·', '•', '—', '–', '-', '|', '/', '（', '(')
        ) {
            return replacement + suffix
        }
    }

    val separators = listOf(" · ", " • ", " — ", " – ")
    separators.forEach { separator ->
        val separatorIndex = subtitle.indexOf(separator)
        if (separatorIndex <= 0) return@forEach
        val credit = subtitle.substring(0, separatorIndex)
        if (
            AppleInternalCatalogResolver.normalizedArtistNameKey(credit) ==
            AppleInternalCatalogResolver.normalizedArtistNameKey(original)
        ) {
            return replacement + subtitle.substring(separatorIndex)
        }
    }
    return subtitle
}
