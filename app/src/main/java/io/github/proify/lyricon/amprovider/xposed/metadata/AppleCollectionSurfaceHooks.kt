/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalStack
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.util.Collections
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

internal interface AppleCollectionSurfaceHost {
    fun mediaApiEntityAttributes(entity: Any): Any?

    fun mediaApiEntityCatalogId(entity: Any, knownAttributes: Any? = null): String?

    fun mediaApiAttribute(
        attributes: Any,
        attribute: AppleMediaApiTextAttribute,
    ): String?

    fun registerLibraryEntity(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        knownAttributes: Any? = null,
        requestResolution: Boolean = false,
        retainEntityRef: Boolean = true,
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

    fun dataBindingAliasValues(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        binding: Any?,
    ): DataBindingAliasValues

    fun sharedAssociatedArtistId(mediaId: String): String?

    fun onMetadataPageAttached(owner: Any, recycler: RecyclerView)

    fun onMetadataPageDetached(owner: Any)

    fun handleArtistFinalBinding(model: Any, finalHolder: Any?, position: Int?)

    fun nextMetadataTraceSequence(): Long

    fun logMetadataIdentity(event: String, details: String)
}

internal class AppleCollectionSurfaceHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val metadataStore: AppleMetadataOverrideStore,
    private val librarySurfaceHooks: AppleLibrarySurfaceHooks,
    private val dataBindingHooks: AppleDataBindingMetadataHooks,
    private val host: AppleCollectionSurfaceHost,
) {
    private companion object {
        const val MAX_PLAYLIST_ROW_MEDIA_IDS = 512
    }

    private val collectionPageBoundResolutionStates =
        Collections.synchronizedMap(
            WeakHashMap<Any, CollectionPageBoundResolutionState>()
        )
    private val playlistRowRootMediaIds = WeakIdentityMap<View, String>()
    private val playlistRowRefs =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, ConcurrentLinkedQueue<InAppPlaylistRowRef>>(
                MAX_PLAYLIST_ROW_MEDIA_IDS,
                0.75f,
                true,
            ) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<
                        String,
                        ConcurrentLinkedQueue<InAppPlaylistRowRef>,
                        >?,
                ): Boolean = size > MAX_PLAYLIST_ROW_MEDIA_IDS
            }
        )
    private val albumPageBuildData =
        Collections.synchronizedMap(WeakHashMap<Any, AlbumPageBuildData>())
    private val activeAlbumHeaderBuildCaptures = ThreadLocalStack<AlbumHeaderBuildCapture>()
    private val albumHeaderModelIds = WeakIdentityMap<Any, String>()
    private val albumHeaderFinalBoundResolutionIds = WeakIdentityMap<Any, String>()
    private val pageControllerClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val pageLifecycleHookedMethods = ConcurrentHashMap.newKeySet<Executable>()

    @Volatile
    private var playlistExplicitTitleFormatterClass: Class<*>? = null
    @Volatile
    private var playlistExplicitTitleFormatterMethod: String? = null

    fun installHooks() {
        val resolvedClasses = runCatching {
            runtime.hookResolver.resolveClasses(AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES)
        }.getOrElse {
            ProviderLogger.error("Apple Music 详情页运行时类解析失败", it)
            return
        }
        val classesByRole = resolvedClasses.associateBy { resolved ->
            resolved.target.runtimeMemberName(AppleMusicRuntimeMember.COLLECTION_RUNTIME_ROLE)
        }
        val recyclerResolved = checkNotNull(classesByRole["recycler"])
        val mediaEntityResolved = checkNotNull(classesByRole["media_entity"])
        val albumResolved = checkNotNull(classesByRole["album_entity"])
        val albumControllerResolved = checkNotNull(classesByRole["album_controller"])
        val directHeaderResolved = checkNotNull(classesByRole["album_header_model"])
        val playlistControllerResolved = checkNotNull(classesByRole["playlist_controller"])
        val albumRowResolved = checkNotNull(classesByRole["album_row_model"])
        val playlistRowResolved = checkNotNull(classesByRole["playlist_row_model"])

        installFinalBindingHook(
            mediaEntityClass = mediaEntityResolved.clazz,
            albumRowClass = albumRowResolved.clazz,
            playlistRowClass = playlistRowResolved.clazz,
            playlistRowTarget = playlistRowResolved.target,
            mediaEntityTarget = mediaEntityResolved.target,
        )
        installAlbumHooks(
            recyclerClass = recyclerResolved.clazz,
            mediaEntityClass = mediaEntityResolved.clazz,
            albumClass = albumResolved.clazz,
            controllerClass = albumControllerResolved.clazz,
            controllerTarget = albumControllerResolved.target,
            directHeaderClass = directHeaderResolved.clazz,
        )
        installPlaylistHooks(
            recyclerClass = recyclerResolved.clazz,
            mediaEntityClass = mediaEntityResolved.clazz,
            controllerClass = playlistControllerResolved.clazz,
            controllerTarget = playlistControllerResolved.target,
        )
    }

    fun hasAlbumBuildData(controller: Any): Boolean = albumPageBuildData[controller] != null

    fun isPlaylistController(controller: Any): Boolean {
        val playlistClass = runtime.hookResolver
            .resolveClasses(AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES)
            .firstOrNull { resolved ->
                resolved.target.runtimeMemberName(
                    AppleMusicRuntimeMember.COLLECTION_RUNTIME_ROLE
                ) == "playlist_controller"
            }
            ?.clazz
            ?: return false
        return playlistClass.isInstance(controller)
    }

    fun albumTrackMediaIds(controller: Any): Collection<String> =
        albumPageBuildData[controller]?.trackMediaIds.orEmpty()

    fun controllerAppliedAlias(
        controller: Any,
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ): AppliedMetadataAlias {
        val appliedAlias = AppliedMetadataAlias(mediaId, alias)
        val albumData = albumPageBuildData[controller] ?: return appliedAlias
        if (mediaId !in albumData.trackMediaIds) return appliedAlias
        val albumMediaId = albumData.mediaId ?: return appliedAlias
        val albumArtist = host.effectiveAlias(albumMediaId)
            ?.artist
            ?.takeIf(String::isNotBlank)
            ?: metadataStore.accountMetadata(albumMediaId)?.artist
        return albumPageControllerAppliedAlias(
            appliedAlias = appliedAlias,
            songArtistId = host.sharedAssociatedArtistId(mediaId),
            albumArtistId = host.sharedAssociatedArtistId(albumMediaId),
            albumArtist = albumArtist,
        )
    }

    fun requestControllerBuild(
        controller: Any,
        strategy: InAppLibraryControllerBuildStrategy,
    ): Boolean {
        return when (strategy) {
            InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA -> {
                val buildData = albumPageBuildData[controller] ?: return false
                val target = collectionTarget("album_controller") ?: return false
                AppleReflection.call(
                    controller,
                    target.runtimeMemberName(
                        AppleMusicRuntimeMember.COLLECTION_CONTROLLER_SET_DATA_METHOD
                    ),
                    buildData.album,
                    buildData.selectedItemIds,
                )
                true
            }

            InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD -> {
                val target = collectionTarget("playlist_controller") ?: return false
                AppleReflection.call(
                    controller,
                    target.runtimeMemberName(
                        AppleMusicRuntimeMember.COLLECTION_CONTROLLER_FORCE_BUILD_METHOD
                    ),
                )
                true
            }

            else -> false
        }
    }

    fun refreshPlaylistRowRefs(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ): Int {
        val refs = playlistRowRefs[mediaId] ?: return 0
        var completeTargets = 0
        refs.forEach { ref ->
            val root = ref.root.get()
            if (root == null || playlistRowRootMediaIds[root] != mediaId) {
                refs.remove(ref)
                return@forEach
            }
            if (applyAliasToPlaylistRow(mediaId, ref, alias)) completeTargets += 1
        }
        return completeTargets
    }

    fun clearController(controller: Any) {
        collectionPageBoundResolutionStates.remove(controller)
        albumPageBuildData.remove(controller)
    }

    private fun installAlbumHooks(
        recyclerClass: Class<*>,
        mediaEntityClass: Class<*>,
        albumClass: Class<*>,
        controllerClass: Class<*>,
        controllerTarget: AppleMusicHookTarget,
        directHeaderClass: Class<*>,
    ) {
        runCatching {
            val buildMethod = controllerClass.declaredMethods.singleOrNull { method ->
                val parameterTypes = method.parameterTypes
                !method.isBridge &&
                    parameterTypes.size == 2 &&
                    albumClass.isAssignableFrom(parameterTypes[0]) &&
                    Set::class.java.isAssignableFrom(parameterTypes[1])
            }?.apply { isAccessible = true }
                ?: error("AlbumPageController data build method not found")
            val trackBuildMethod = controllerClass.declaredMethods.singleOrNull { method ->
                val parameterTypes = method.parameterTypes
                parameterTypes.size == 2 &&
                    albumClass.isAssignableFrom(parameterTypes[0]) &&
                    parameterTypes[1].isArray &&
                    parameterTypes[1].componentType?.let(mediaEntityClass::isAssignableFrom) == true
            }?.apply { isAccessible = true }
                ?: error("AlbumPageController track build method not found")
            val headerBuildMethod = AppleReflection.findMethod(
                controllerClass,
                controllerTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.COLLECTION_ALBUM_HEADER_BUILD_METHOD
                ),
                parameterTypes = listOf(albumClass),
            )
            val directHeaderConstructor = directHeaderClass.declaredConstructors
                .singleOrNull { it.parameterCount == 0 }
                ?.apply { isAccessible = true }
                ?: error("Direct album header constructor not found")

            runtime.hookRegistrar.installHook(
                buildMethod,
                before = { chain ->
                    val controller = chain.thisObject ?: return@installHook
                    val album = chain.args.getOrNull(0) ?: return@installHook
                    val attributes = host.mediaApiEntityAttributes(album)
                    val mediaId = attributes?.let {
                        host.mediaApiEntityCatalogId(album, it)
                    } ?: host.mediaApiEntityCatalogId(album)
                    albumPageBuildData[controller] = AlbumPageBuildData(
                        album = album,
                        selectedItemIds = chain.args.getOrNull(1),
                        mediaId = mediaId,
                    )
                    if (mediaId != null) {
                        host.registerLibraryEntity(
                            mediaId = mediaId,
                            entity = album,
                            kind = InAppLibraryEntityKind.ALBUM,
                            knownAttributes = attributes,
                        )
                        librarySurfaceHooks.registerController(mediaId, controller)
                    }
                },
                after = { chain, _ ->
                    val controller = chain.thisObject ?: return@installHook
                    val buildData = albumPageBuildData[controller] ?: return@installHook
                    librarySurfaceHooks.recordControllerBuildAliases(
                        controller = controller,
                        mediaIds = listOfNotNull(buildData.mediaId) + buildData.trackMediaIds,
                        replace = true,
                    )
                },
            )
            runtime.hookRegistrar.installScopedHook(
                executable = headerBuildMethod,
                enter = { chain ->
                    val album = chain.args.firstOrNull() ?: return@installScopedHook false
                    val mediaId = librarySurfaceHooks.entityMediaId(album)
                        ?: host.mediaApiEntityCatalogId(album)
                        ?: return@installScopedHook false
                    activeAlbumHeaderBuildCaptures.push(AlbumHeaderBuildCapture(mediaId))
                    true
                },
                after = { _, result ->
                    val mediaId = activeAlbumHeaderBuildCaptures.current?.mediaId
                        ?: return@installScopedHook
                    result?.let { albumHeaderModelIds[it] = mediaId }
                },
                exit = { activeAlbumHeaderBuildCaptures.pop() },
            )
            runtime.hookRegistrar.installHook(directHeaderConstructor, after = { chain, _ ->
                val mediaId = activeAlbumHeaderBuildCaptures.current?.mediaId
                    ?: return@installHook
                chain.thisObject?.let { albumHeaderModelIds[it] = mediaId }
            })
            runtime.hookRegistrar.installHook(trackBuildMethod, before = { chain ->
                val controller = chain.thisObject ?: return@installHook
                val tracks = chain.args.getOrNull(1) as? Array<*> ?: return@installHook
                val trackMediaIds = registerSongEntities(controller, tracks.asList())
                synchronized(albumPageBuildData) {
                    albumPageBuildData[controller]?.let { buildData ->
                        albumPageBuildData[controller] = buildData.copy(
                            trackMediaIds = trackMediaIds
                        )
                    }
                }
            })
            installControllerLifecycle(controllerClass, controllerTarget, recyclerClass)
            ProviderLogger.info(
                "Apple Music 专辑页实时元数据 Hook 已安装: " +
                    "build=${buildMethod.name}, header=${headerBuildMethod.name}, " +
                    "directHeader=${directHeaderClass.name}, tracks=${trackBuildMethod.name}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 专辑页实时元数据 Hook 安装失败", it)
        }
    }

    private fun installPlaylistHooks(
        recyclerClass: Class<*>,
        mediaEntityClass: Class<*>,
        controllerClass: Class<*>,
        controllerTarget: AppleMusicHookTarget,
    ) {
        runCatching {
            val buildItemMethod = AppleReflection.findMethod(
                controllerClass,
                controllerTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.COLLECTION_PLAYLIST_BUILD_ITEM_METHOD
                ),
                parameterTypes = listOf(Int::class.javaPrimitiveType!!, mediaEntityClass),
            )
            runtime.hookRegistrar.installHook(
                buildItemMethod,
                before = { chain ->
                    val controller = chain.thisObject ?: return@installHook
                    val entity = chain.args.getOrNull(1) ?: return@installHook
                    registerSongEntities(controller, listOf(entity))
                },
                after = { chain, _ ->
                    val controller = chain.thisObject ?: return@installHook
                    val entity = chain.args.getOrNull(1) ?: return@installHook
                    val mediaId = librarySurfaceHooks.entityMediaId(entity)
                        ?: host.mediaApiEntityCatalogId(entity)
                        ?: return@installHook
                    librarySurfaceHooks.recordControllerBuildAliases(
                        controller = controller,
                        mediaIds = listOf(mediaId),
                        replace = false,
                    )
                },
            )
            installControllerLifecycle(controllerClass, controllerTarget, recyclerClass)
            ProviderLogger.info(
                "Apple Music 歌单页实时元数据 Hook 已安装: " +
                    "buildItem=${buildItemMethod.name}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 歌单页实时元数据 Hook 安装失败", it)
        }
    }

    private fun installFinalBindingHook(
        mediaEntityClass: Class<*>,
        albumRowClass: Class<*>,
        playlistRowClass: Class<*>,
        playlistRowTarget: AppleMusicHookTarget,
        mediaEntityTarget: AppleMusicHookTarget,
    ) {
        runCatching {
            val resolvedBind = runtime.hookResolver.resolveMethod(AppleMusicHookPoint.EPOXY_FINAL_BIND)
            val bindMethod = resolvedBind.method
            val modelHolderMethod = resolvedBind.target.runtimeMemberName(
                AppleMusicRuntimeMember.EPOXY_FINAL_HOLDER_MODEL_HOLDER_METHOD
            )
            runtime.hookRegistrar.installHook(bindMethod, after = { chain, _ ->
                val model = chain.args.firstOrNull() ?: return@installHook
                val position = chain.args.getOrNull(3) as? Int
                val albumHeaderMediaId = albumHeaderModelIds[model]
                when {
                    albumHeaderMediaId != null -> {
                        val binding = bindingFromFinalHolder(chain.thisObject, modelHolderMethod)
                        if (binding != null) {
                            dataBindingHooks.beginModelBind(binding)
                            dataBindingHooks.capture(binding)
                            dataBindingHooks.register(
                                mediaId = albumHeaderMediaId,
                                binding = binding,
                                originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
                            )
                        }
                        onAlbumHeaderFinalBound(
                            model = model,
                            mediaId = albumHeaderMediaId,
                            position = position,
                            binding = binding,
                        )
                    }

                    albumRowClass.isInstance(model) || playlistRowClass.isInstance(model) -> {
                        val entity = collectionPageRowEntity(model, mediaEntityClass)
                            ?: return@installHook
                        val mediaId = librarySurfaceHooks.entityMediaId(entity)
                            ?: host.mediaApiEntityCatalogId(entity)
                            ?: return@installHook
                        val playlist = playlistRowClass.isInstance(model)
                        host.registerLibraryEntity(
                            mediaId = mediaId,
                            entity = entity,
                            kind = InAppLibraryEntityKind.SONG,
                        )
                        dataBindingHooks.recordCurrentRecyclerMediaId(mediaId)
                        if (playlist) {
                            registerPlaylistRowBinding(
                                mediaId = mediaId,
                                entity = entity,
                                model = model,
                                finalHolder = chain.thisObject,
                                playlistRowTarget = playlistRowTarget,
                                mediaEntityTarget = mediaEntityTarget,
                            )
                        }
                        onCollectionPageRowBound(
                            mediaId = mediaId,
                            entity = entity,
                            pageType = if (playlist) "playlist" else "album",
                        )
                    }

                    else -> host.handleArtistFinalBinding(model, chain.thisObject, position)
                }
            })
            ProviderLogger.info(
                "Apple Music 详情页最终绑定元数据 Hook 已安装: " +
                    "holder=${resolvedBind.target.className}, method=${bindMethod.name}, " +
                    "fallback=${resolvedBind.compatibilityFallback}, collectionModels=3"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 详情页最终绑定 Hook 安装失败", it)
        }
    }

    private fun installControllerLifecycle(
        controllerClass: Class<*>,
        controllerTarget: AppleMusicHookTarget,
        recyclerClass: Class<*>,
    ) {
        pageControllerClasses.add(controllerClass)
        val attachedMethod = AppleReflection.findMethod(
            controllerClass,
            controllerTarget.runtimeMemberName(
                AppleMusicRuntimeMember.COLLECTION_CONTROLLER_ATTACH_METHOD
            ),
            parameterTypes = listOf(recyclerClass),
        )
        val detachedMethod = AppleReflection.findMethod(
            controllerClass,
            controllerTarget.runtimeMemberName(
                AppleMusicRuntimeMember.COLLECTION_CONTROLLER_DETACH_METHOD
            ),
            parameterTypes = listOf(recyclerClass),
        )
        if (pageLifecycleHookedMethods.add(attachedMethod)) {
            runtime.hookRegistrar.installHook(attachedMethod, after = { chain, _ ->
                val owner = chain.thisObject ?: return@installHook
                if (pageControllerClasses.none { it.isInstance(owner) }) return@installHook
                val recycler = chain.args.firstOrNull() as? RecyclerView ?: return@installHook
                host.onMetadataPageAttached(owner, recycler)
            })
        }
        if (pageLifecycleHookedMethods.add(detachedMethod)) {
            runtime.hookRegistrar.installHook(detachedMethod, before = { chain ->
                val owner = chain.thisObject ?: return@installHook
                if (pageControllerClasses.any { it.isInstance(owner) }) {
                    clearController(owner)
                    host.onMetadataPageDetached(owner)
                }
            })
        }
    }

    private fun registerSongEntities(
        controller: Any,
        entities: Collection<Any?>,
    ): Set<String> = buildSet {
        entities.forEach { entity ->
            entity ?: return@forEach
            val mediaId = librarySurfaceHooks.entityMediaId(entity)
                ?: host.mediaApiEntityCatalogId(entity)
                ?: return@forEach
            host.registerLibraryEntity(
                mediaId = mediaId,
                entity = entity,
                kind = InAppLibraryEntityKind.SONG,
            )
            librarySurfaceHooks.registerController(mediaId, controller)
            add(mediaId)
        }
    }

    private fun bindingFromFinalHolder(holder: Any?, modelHolderMethod: String): Any? {
        val modelHolder = holder?.let {
            runCatching { AppleReflection.call(it, modelHolderMethod) }.getOrNull()
        }
        return dataBindingHooks.bindingFromHolder(modelHolder)
            ?: dataBindingHooks.bindingFromHolder(holder)
    }

    private fun registerPlaylistRowBinding(
        mediaId: String,
        entity: Any,
        model: Any,
        finalHolder: Any?,
        playlistRowTarget: AppleMusicHookTarget,
        mediaEntityTarget: AppleMusicHookTarget,
    ) {
        val root = dataBindingHooks.itemViewFromHolder(finalHolder) ?: return
        val attributes = host.mediaApiEntityAttributes(entity)
        val modelTitle = reflectiveStringField(
            model,
            playlistRowTarget.runtimeMemberName(
                AppleMusicRuntimeMember.COLLECTION_PLAYLIST_TITLE_FIELD
            ),
        ) ?: attributes?.let {
            host.mediaApiAttribute(it, AppleMediaApiTextAttribute.NAME)
        }
        val modelSubtitle = reflectiveStringField(
            model,
            playlistRowTarget.runtimeMemberName(
                AppleMusicRuntimeMember.COLLECTION_PLAYLIST_SUBTITLE_FIELD
            ),
        )
        val accountArtist = metadataStore.accountMetadata(mediaId)?.artist
            ?: attributes?.let {
                host.mediaApiAttribute(it, AppleMediaApiTextAttribute.ARTIST_NAME)
            }
        val textViews = descendantTextViews(root)
        val titleView = findRenderedTextView(textViews, modelTitle)
        val subtitleView = findRenderedTextView(textViews, modelSubtitle, titleView)
            ?: findContainingTextView(textViews, accountArtist, titleView)

        playlistRowRootMediaIds[root] = mediaId
        val refs = playlistRowRefs.getOrPut(mediaId) { ConcurrentLinkedQueue() }
        refs.forEach { ref ->
            val targetRoot = ref.root.get()
            if (targetRoot == null || targetRoot === root) refs.remove(ref)
        }
        val rowRef = InAppPlaylistRowRef(
            root = WeakReference(root),
            title = titleView?.let(::WeakReference),
            subtitle = subtitleView?.let(::WeakReference),
            entity = WeakReference(entity),
            originalSubtitle = modelSubtitle,
            originalArtist = accountArtist,
        )
        refs.add(rowRef)
        host.effectiveAlias(mediaId)?.let { applyAliasToPlaylistRow(mediaId, rowRef, it) }
        if (BuildConfig.DEBUG) {
            host.logMetadataIdentity(
                event = "playlist_row_direct_bound",
                details = "contentId=$mediaId, titleView=${titleView != null}, " +
                    "subtitleView=${subtitleView != null}, model=$modelTitle/$modelSubtitle, " +
                    "accountArtist=$accountArtist, explicitMethod=" +
                    mediaEntityTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.COLLECTION_ENTITY_EXPLICIT_METHOD
                    ),
            )
        }
    }

    private fun applyAliasToPlaylistRow(
        mediaId: String,
        ref: InAppPlaylistRowRef,
        alias: AppleInternalCatalogResolver.Alias,
    ): Boolean {
        val root = ref.root.get() ?: return false
        if (playlistRowRootMediaIds[root] != mediaId) return false
        val titleApplied = if (alias.title.isBlank()) {
            true
        } else {
            ref.title?.get()?.let { titleView ->
                applyPlaylistRowTitle(titleView, alias.title, ref.entity.get())
                true
            } ?: false
        }
        val subtitleApplied = if (alias.artist.isBlank()) {
            true
        } else {
            ref.subtitle?.get()?.let { subtitleView ->
                subtitleView.text = artistProfileSubtitleWithArtist(
                    originalSubtitle = ref.originalSubtitle,
                    originalArtist = ref.originalArtist,
                    replacementArtist = alias.artist,
                )
                true
            } ?: false
        }
        if (BuildConfig.DEBUG && (titleApplied || subtitleApplied)) {
            host.logMetadataIdentity(
                event = "playlist_row_direct_applied",
                details = "contentId=$mediaId, titleApplied=$titleApplied, " +
                    "subtitleApplied=$subtitleApplied, alias=${alias.title}/${alias.artist}",
            )
        }
        return titleApplied && subtitleApplied
    }

    private fun applyPlaylistRowTitle(titleView: TextView, title: String, entity: Any?) {
        val mediaEntityTarget = collectionTarget("media_entity")
        val explicitMethod = mediaEntityTarget?.runtimeMemberName(
            AppleMusicRuntimeMember.COLLECTION_ENTITY_EXPLICIT_METHOD
        )
        val explicit = entity != null && explicitMethod != null &&
            runCatching { AppleReflection.call(entity, explicitMethod) as? Boolean }
                .getOrNull() == true
        val formatter = playlistExplicitTitleFormatterClass ?: runtime.hookResolver
            .resolveClasses(AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS)
            .firstOrNull()
            ?.also { resolved ->
                playlistExplicitTitleFormatterMethod = resolved.target.runtimeMemberName(
                    AppleMusicRuntimeMember.APPLE_TEXT_STYLE_EXPLICIT_TITLE_METHOD
                )
            }
            ?.clazz
            ?.also { playlistExplicitTitleFormatterClass = it }
        val formatterMethod = playlistExplicitTitleFormatterMethod
        val formatted = formatter != null && formatterMethod != null && runCatching {
            AppleReflection.callStatic(formatter, formatterMethod, titleView, title, explicit)
        }.isSuccess
        if (!formatted) titleView.text = title
    }

    private fun onCollectionPageRowBound(mediaId: String, entity: Any, pageType: String) {
        val controllers = librarySurfaceHooks.liveControllers(mediaId)
        var shouldResolve = controllers.isEmpty()
        controllers.forEach { controller ->
            val newlyBound = synchronized(collectionPageBoundResolutionStates) {
                collectionPageBoundResolutionStates.getOrPut(controller) {
                    CollectionPageBoundResolutionState()
                }.requestedMediaIds.add(mediaId)
            }
            shouldResolve = shouldResolve || newlyBound
        }
        if (!shouldResolve) return

        runtime.mainHandler.post {
            host.markMetadataVisible(listOf(mediaId))
            host.enrichLibraryEntitiesForResolution(listOf(mediaId))
            val alias = host.effectiveAlias(mediaId)
            if (alias != null) {
                host.applyAliasToMetadataRefs(mediaId, alias, notifyModelChange = true)
            }
            host.scheduleMetadataResolution(
                mediaIds = listOf(mediaId),
                priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                originalResolutionMode =
                    AppleMetadataResolutionEngine.collectionPageOriginalResolutionMode(pageType),
            )
            if (BuildConfig.DEBUG) {
                val attributes = host.mediaApiEntityAttributes(entity)
                val entityName = attributes?.let {
                    host.mediaApiAttribute(it, AppleMediaApiTextAttribute.NAME)
                }
                val entityArtist = attributes?.let {
                    host.mediaApiAttribute(it, AppleMediaApiTextAttribute.ARTIST_NAME)
                }
                ProviderLogger.info(
                    "Apple Music 元数据链路: event=collection_page_row_bound, " +
                        "contentId=$mediaId, pageType=$pageType, controllers=${controllers.size}, " +
                        "entity=$entityName/$entityArtist, " +
                        "effective=${alias?.title}/${alias?.artist}/${alias?.album}, " +
                        "request=${host.shouldRequestOverride(mediaId)}"
                )
            }
        }
    }

    private fun onAlbumHeaderFinalBound(
        model: Any,
        mediaId: String,
        position: Int?,
        binding: Any?,
    ) {
        val shouldResolve = albumHeaderFinalBoundResolutionIds[model] != mediaId
        albumHeaderFinalBoundResolutionIds[model] = mediaId
        runtime.mainHandler.post {
            host.markMetadataVisible(listOf(mediaId))
            host.enrichLibraryEntitiesForResolution(listOf(mediaId))
            val alias = host.effectiveAlias(mediaId)
            val shouldRequest = shouldResolve && host.shouldRequestOverride(mediaId)
            var aliasAlreadyRendered = false
            if (alias != null) {
                val appliedAlias = AppliedMetadataAlias(mediaId, alias)
                val root = binding?.let(dataBindingHooks::root)
                val values = host.dataBindingAliasValues(mediaId, alias, binding)
                aliasAlreadyRendered = binding != null &&
                    dataBindingHooks.mediaId(binding) == mediaId &&
                    root != null &&
                    dataBindingAliasAlreadyRendered(
                        expectedTitle = values.title,
                        expectedSubtitle = values.subtitle,
                        renderedTexts = dataBindingHooks.renderedTexts(root),
                    )
                if (aliasAlreadyRendered) {
                    binding?.let { dataBindingHooks.rememberAppliedAlias(it, appliedAlias) }
                } else {
                    dataBindingHooks.refreshDataBindings(mediaId, alias)
                }
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
                        "event=album_header_final_bound, contentId=$mediaId, position=$position, " +
                        "model=${model.javaClass.name}@${System.identityHashCode(model)}, " +
                        "binding=${binding?.javaClass?.name}@" +
                        "${binding?.let(System::identityHashCode)}, " +
                        "rootVisible=${root?.let(dataBindingHooks::isRootVisible) == true}, " +
                        "effective=${alias?.title}/${alias?.artist}/${alias?.album}, " +
                        "alreadyRendered=$aliasAlreadyRendered, request=$shouldRequest, " +
                        "texts=${root?.let(dataBindingHooks::renderedTexts)}"
                )
            }
        }
    }

    private fun collectionTarget(role: String): AppleMusicHookTarget? =
        runtime.hookResolver.resolveClasses(AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES)
            .firstOrNull { resolved ->
                resolved.target.runtimeMemberName(
                    AppleMusicRuntimeMember.COLLECTION_RUNTIME_ROLE
                ) == role
            }
            ?.target

    private fun reflectiveStringField(instance: Any, name: String): String? =
        runCatching { AppleReflection.field(instance, name)?.toString() }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    private fun descendantTextViews(root: View): List<TextView> {
        val textViews = mutableListOf<TextView>()
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited < 64) {
            val view = pending.removeFirst()
            visited += 1
            if (view is TextView) textViews += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    view.getChildAt(index)?.let(pending::addLast)
                }
            }
        }
        return textViews
    }

    private fun findRenderedTextView(
        textViews: Collection<TextView>,
        expected: String?,
        excluded: TextView? = null,
    ): TextView? {
        val normalized = expected?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        return textViews.firstOrNull { view ->
            view !== excluded && view.text?.toString()?.trim() == normalized
        }
    }

    private fun findContainingTextView(
        textViews: Collection<TextView>,
        expectedPart: String?,
        excluded: TextView? = null,
    ): TextView? {
        val normalized = expectedPart?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        return textViews.firstOrNull { view ->
            view !== excluded &&
                view.text?.toString()?.contains(normalized, ignoreCase = true) == true
        }
    }
}

internal fun collectionPageRowEntity(model: Any, mediaEntityClass: Class<*>): Any? =
    generateSequence(model.javaClass) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .firstOrNull { field -> mediaEntityClass.isAssignableFrom(field.type) }
        ?.let { field ->
            runCatching {
                field.isAccessible = true
                field.get(model)
            }.getOrNull()
        }

internal fun shouldUsePlaylistDirectRowRefresh(
    strategy: InAppLibraryControllerBuildStrategy,
    hasDirectPlaylistRow: Boolean,
): Boolean =
    strategy == InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD &&
        hasDirectPlaylistRow

internal fun albumPageControllerAppliedAlias(
    appliedAlias: AppliedMetadataAlias,
    songArtistId: String?,
    albumArtistId: String?,
    albumArtist: String?,
): AppliedMetadataAlias {
    if (songArtistId == null || songArtistId != albumArtistId) return appliedAlias
    val targetArtist = albumArtist?.trim().orEmpty()
    if (targetArtist.isEmpty()) return appliedAlias
    val songArtistKey = AppleInternalCatalogResolver.normalizedArtistNameKey(
        appliedAlias.artist
    )
    val albumArtistKey = AppleInternalCatalogResolver.normalizedArtistNameKey(targetArtist)
    if (songArtistKey == albumArtistKey) return appliedAlias
    return appliedAlias.copy(artist = targetArtist)
}

internal fun metadataPageFinalBindingKind(
    albumHeader: Boolean,
    albumRow: Boolean,
    playlistRow: Boolean,
    artistTopSong: Boolean,
    artistHeader: Boolean,
): MetadataPageFinalBindingKind? = when {
    albumHeader -> MetadataPageFinalBindingKind.ALBUM_HEADER
    albumRow -> MetadataPageFinalBindingKind.ALBUM_ROW
    playlistRow -> MetadataPageFinalBindingKind.PLAYLIST_ROW
    artistTopSong -> MetadataPageFinalBindingKind.ARTIST_TOP_SONG
    artistHeader -> MetadataPageFinalBindingKind.ARTIST_HEADER
    else -> null
}
