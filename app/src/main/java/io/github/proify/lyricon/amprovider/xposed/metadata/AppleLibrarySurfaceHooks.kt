/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.os.SystemClock
import android.view.Choreographer
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

internal interface AppleLibrarySurfaceHost {
    fun contentItemMediaId(source: Any): String?

    fun primeLibrarySource(source: Any?)

    fun mediaApiEntityAttributes(entity: Any): Any?

    fun mediaApiEntityCatalogId(entity: Any, knownAttributes: Any? = null): String?

    fun mediaApiEntityLookupIds(entity: Any, knownAttributes: Any?): Set<String>

    fun mergePlaybackAccountMetadata(
        mediaId: String,
        title: String?,
        artist: String?,
    )

    fun requestPriorityForMediaId(
        mediaId: String,
    ): AppleInternalCatalogResolver.RequestPriority

    fun enrichEntityAssociations(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        attributes: Any,
        originalName: String?,
        originalArtist: String?,
        originalAlbum: String?,
    )

    fun recordCurrentRecyclerMediaId(mediaId: String)

    fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias?

    fun normalizeMediaIds(mediaIds: Collection<String>): List<String>

    fun markMetadataVisible(mediaIds: Collection<String>)

    fun applyAliasToMetadataRefs(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    )

    fun scheduleMetadataResolution(
        mediaIds: Collection<String>,
        priority: AppleInternalCatalogResolver.RequestPriority,
    )

    fun isRefreshableMediaId(mediaId: String): Boolean

    fun nextMetadataTraceSequence(): Long

    fun logMetadataIdentity(event: String, details: String)

    fun debugStackSummary(): String

    fun controllerBuildStrategy(controller: Any): InAppLibraryControllerBuildStrategy

    fun controllerAppliedAlias(
        controller: Any,
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ): AppliedMetadataAlias

    fun controllerAlbumTrackMediaIds(controller: Any): Collection<String>

    fun requestControllerBuild(
        controller: Any,
        strategy: InAppLibraryControllerBuildStrategy,
    )

}

internal class AppleLibrarySurfaceHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val metadataStore: AppleMetadataOverrideStore,
    private val host: AppleLibrarySurfaceHost,
) {
    private companion object {
        const val MAX_VISIBLE_RESOLUTION_IDS = 12
        const val ALBUM_CONTROLLER_REFRESH_DEBOUNCE_MS = 180L
        const val PLAYLIST_CONTROLLER_REFRESH_INTERVAL_MS = 500L
    }

    private val entityRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<InAppLibraryEntityRef>>()
    private val entityIds = WeakIdentityMap<Any, String>()
    private val entityAttributes = WeakIdentityMap<Any, Any>()
    private val entityEnrichedIds = WeakIdentityMap<Any, String>()
    private val mediaApiAttributeBindings =
        WeakIdentityMap<Any, InAppMediaApiAttributeBinding>()
    private val mediaApiAttributeHookedMethods =
        ConcurrentHashMap.newKeySet<java.lang.reflect.Executable>()
    private val mediaApiSongIds =
        Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val catalogTarget by lazy {
        runtime.hookResolver.resolveClass(
            AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS
        ).target
    }

    private val activeComposeCapture = ThreadLocal<InAppLibraryComposeCapture?>()
    private val debugLibraryModelRefreshMediaId = ThreadLocal<String?>()
    private val composeStates =
        Collections.synchronizedMap(WeakHashMap<Any, WeakReference<Any>>())
    private val composeStateRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<WeakReference<Any>>>()
    private val composeRefreshPending =
        Collections.synchronizedMap(
            WeakHashMap<Any, MutableMap<String, AppliedMetadataAlias?>>()
        )
    private val composeVisibleResolutionPending =
        Collections.synchronizedMap(WeakHashMap<Any, MutableSet<String>>())
    private val composeAppliedAliases =
        Collections.synchronizedMap(
            WeakHashMap<Any, MutableMap<String, AppliedMetadataAlias>>()
        )
    @Volatile
    private var composeNeverEqualPolicy: Any? = null
    @Volatile
    private var composeObserveTarget: AppleMusicHookTarget? = null

    fun installEntityHooks() {
        runCatching {
            val resolvedClasses = runtime.hookResolver.resolveClasses(
                AppleMusicHookPoint.LIBRARY_ENTITY_CLASSES
            )
            val classesByRole = resolvedClasses.associateBy { resolved ->
                resolved.target.runtimeMemberName(AppleMusicRuntimeMember.LIBRARY_ENTITY_ROLE)
            }
            fun classForRole(role: String): Class<*> =
                checkNotNull(classesByRole[role]?.clazz) {
                    "Library entity class unavailable: role=$role"
                }

            val modelAlbumClass = classForRole("model_album")
            val modelSongClass = classForRole("model_song")
            val mediaApiSongClass = classForRole("media_api_song")
            val libraryAlbumClass = classForRole("library_album")
            val librarySongClass = classForRole("library_song")

            val albumConstructor = libraryAlbumClass
                .getDeclaredConstructor(modelAlbumClass)
                .apply { isAccessible = true }
            runtime.hookRegistrar.installHook(
                albumConstructor,
                before = { chain -> host.primeLibrarySource(chain.args.firstOrNull()) },
                after = { chain, _ ->
                    val entity = chain.thisObject ?: return@installHook
                    val source = chain.args.firstOrNull() ?: return@installHook
                    val mediaId = host.contentItemMediaId(source) ?: return@installHook
                    registerEntity(
                        mediaId = mediaId,
                        entity = entity,
                        kind = InAppLibraryEntityKind.ALBUM,
                        requestResolution = false,
                    )
                },
            )

            val songConstructor = mediaApiSongClass
                .getDeclaredConstructor(modelSongClass)
                .apply { isAccessible = true }
            runtime.hookRegistrar.installHook(
                songConstructor,
                before = { chain -> host.primeLibrarySource(chain.args.firstOrNull()) },
                after = { chain, _ ->
                    val entity = chain.thisObject ?: return@installHook
                    val source = chain.args.firstOrNull() ?: return@installHook
                    val mediaId = host.contentItemMediaId(source) ?: return@installHook
                    mediaApiSongIds[entity] = mediaId
                    registerEntity(
                        mediaId = mediaId,
                        entity = entity,
                        kind = InAppLibraryEntityKind.SONG,
                        requestResolution = false,
                    )
                },
            )

            val librarySongConstructor = librarySongClass
                .getDeclaredConstructor(mediaApiSongClass)
                .apply { isAccessible = true }
            runtime.hookRegistrar.installHook(librarySongConstructor, after = { chain, _ ->
                val entity = chain.thisObject ?: return@installHook
                val source = chain.args.firstOrNull() ?: return@installHook
                val mediaId = mediaApiSongIds[source]
                    ?: host.mediaApiEntityCatalogId(source)
                    ?: return@installHook
                registerEntity(
                    mediaId = mediaId,
                    entity = entity,
                    kind = InAppLibraryEntityKind.SONG,
                    requestResolution = false,
                )
            })

            val explicitlyHookedConstructors = setOf(
                albumConstructor,
                songConstructor,
                librarySongConstructor,
            )
            var deserializationConstructors = 0
            resolvedClasses.forEach { resolved ->
                val kind = resolved.target
                    .runtimeMemberNameOrNull(AppleMusicRuntimeMember.LIBRARY_ENTITY_KIND)
                    ?.let(::libraryEntityKind)
                    ?: return@forEach
                resolved.clazz.declaredConstructors
                    .filterNot(explicitlyHookedConstructors::contains)
                    .forEach { constructor ->
                        constructor.isAccessible = true
                        runtime.hookRegistrar.installHook(constructor, after = { chain, _ ->
                            val entity = chain.thisObject ?: return@installHook
                            val attributes = host.mediaApiEntityAttributes(entity)
                                ?: return@installHook
                            val mediaId = host.mediaApiEntityCatalogId(entity, attributes)
                                ?: return@installHook
                            registerEntity(
                                mediaId = mediaId,
                                entity = entity,
                                kind = kind,
                                knownAttributes = attributes,
                                requestResolution = false,
                                retainEntityRef = true,
                            )
                        })
                        deserializationConstructors += 1
                    }
            }
            ProviderLogger.info(
                "Apple Music 资料库媒体快照 Hook 已安装: album=true, song=true, " +
                    "deserializationConstructors=$deserializationConstructors"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 资料库媒体快照 Hook 安装失败", it)
        }
    }

    fun registerEntity(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        knownAttributes: Any? = null,
        requestResolution: Boolean = true,
        retainEntityRef: Boolean = true,
    ) {
        val attributes = knownAttributes ?: host.mediaApiEntityAttributes(entity) ?: return
        val binding = InAppMediaApiAttributeBinding(mediaId, kind)
        if (
            entityIds[entity] == mediaId &&
            mediaApiAttributeBindings[attributes] == binding &&
            !requestResolution &&
            !retainEntityRef
        ) return
        entityIds[entity] = mediaId
        entityAttributes[entity] = attributes
        val snapshot = AppleMediaApiAttributeSnapshots.remember(
            attributes = attributes,
            name = mediaApiAttribute(attributes, AppleMediaApiTextAttribute.NAME),
            artistName = mediaApiAttribute(attributes, AppleMediaApiTextAttribute.ARTIST_NAME),
            albumName = mediaApiAttribute(attributes, AppleMediaApiTextAttribute.ALBUM_NAME),
        )
        registerMediaApiAttributes(mediaId, attributes, kind)
        metadataStore.rememberEntityType(mediaId, localizedEntityTypeForInAppLibraryKind(kind))
        metadataStore.mergeLookupIds(
            mediaId,
            host.mediaApiEntityLookupIds(entity, attributes) + mediaId,
        )
        host.mergePlaybackAccountMetadata(
            mediaId = mediaId,
            title = snapshot.name,
            artist = snapshot.artistName,
        )
        if (retainEntityRef) {
            val refs = entityRefs.computeIfAbsent(mediaId) { ConcurrentLinkedQueue() }
            var registered = false
            refs.forEach { ref ->
                val target = ref.entity.get()
                if (target == null) refs.remove(ref) else if (target === entity) registered = true
            }
            if (!registered) {
                refs.add(
                    InAppLibraryEntityRef(
                        entity = WeakReference(entity),
                        kind = kind,
                        originalName = snapshot.name,
                        originalArtist = snapshot.artistName,
                        originalAlbum = snapshot.albumName,
                    )
                )
            }
            host.effectiveAlias(mediaId)?.let { alias -> applyAliasToEntity(entity, kind, alias) }
        }
        if (
            requestResolution &&
            host.requestPriorityForMediaId(mediaId) ==
            AppleInternalCatalogResolver.RequestPriority.VISIBLE
        ) {
            enrichEntity(mediaId, entity, kind, attributes)
            host.scheduleMetadataResolution(
                mediaIds = listOf(mediaId),
                priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
            )
        }
    }

    fun enrichEntitiesForResolution(mediaIds: Collection<String>) {
        host.normalizeMediaIds(mediaIds).forEach { mediaId ->
            entityRefs[mediaId]?.forEach { ref ->
                val entity = ref.entity.get()
                if (entity == null) {
                    entityRefs[mediaId]?.remove(ref)
                    return@forEach
                }
                val attributes = entityAttributes[entity]
                    ?: host.mediaApiEntityAttributes(entity)
                    ?: return@forEach
                enrichEntity(mediaId, entity, ref.kind, attributes)
            }
        }
    }

    fun enrichEntity(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        attributes: Any,
    ) {
        if (entityEnrichedIds[entity] == mediaId) return
        val snapshot = AppleMediaApiAttributeSnapshots.get(attributes)
        host.enrichEntityAssociations(
            mediaId = mediaId,
            entity = entity,
            kind = kind,
            attributes = attributes,
            originalName = snapshot?.name
                ?: mediaApiAttribute(attributes, AppleMediaApiTextAttribute.NAME),
            originalArtist = snapshot?.artistName
                ?: mediaApiAttribute(attributes, AppleMediaApiTextAttribute.ARTIST_NAME),
            originalAlbum = snapshot?.albumName
                ?: mediaApiAttribute(attributes, AppleMediaApiTextAttribute.ALBUM_NAME),
        )
        entityEnrichedIds[entity] = mediaId
    }

    fun entityMediaId(entity: Any): String? = entityIds[entity]

    fun attributeBindingMediaId(attributes: Any): String? =
        mediaApiAttributeBindings[attributes]?.mediaId

    fun liveEntities(mediaId: String): List<Any> =
        entityRefs[mediaId]?.mapNotNull { it.entity.get() }.orEmpty()

    fun hasEntityRefs(mediaId: String): Boolean =
        entityRefs[mediaId]?.any { it.entity.get() != null } == true

    fun applyAliasToEntityRefs(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ): Int {
        val refs = entityRefs[mediaId] ?: return 0
        var applied = 0
        refs.forEach { ref ->
            val entity = ref.entity.get()
            if (entity == null || entityIds[entity] != mediaId) {
                refs.remove(ref)
            } else if (applyAliasToEntity(entity, ref.kind, alias)) {
                applied += 1
            }
        }
        return applied
    }

    fun applyAliasToEntity(
        entity: Any,
        kind: InAppLibraryEntityKind,
        alias: AppleInternalCatalogResolver.Alias,
    ): Boolean {
        val attributes = host.mediaApiEntityAttributes(entity) ?: return false
        val name = when (kind) {
            InAppLibraryEntityKind.ALBUM -> alias.album.ifBlank { alias.title }
            InAppLibraryEntityKind.SONG -> alias.title
            InAppLibraryEntityKind.ARTIST -> alias.artist.ifBlank { alias.title }
        }
        var changed = false
        name.takeIf(String::isNotBlank)?.let { value ->
            runCatching {
                setMediaApiAttribute(attributes, AppleMediaApiTextAttribute.NAME, value)
            }
                .onSuccess { changed = true }
        }
        alias.artist.takeIf(String::isNotBlank)?.let { value ->
            runCatching {
                setMediaApiAttribute(attributes, AppleMediaApiTextAttribute.ARTIST_NAME, value)
            }
                .onSuccess { changed = true }
        }
        if (kind == InAppLibraryEntityKind.SONG) {
            alias.album.takeIf(String::isNotBlank)?.let { value ->
                runCatching {
                    setMediaApiAttribute(attributes, AppleMediaApiTextAttribute.ALBUM_NAME, value)
                }
                    .onSuccess { changed = true }
            }
        }
        return changed
    }

    fun restoreOriginalEntities(): Set<String> = buildSet {
        entityRefs.forEach { (mediaId, refs) ->
            refs.forEach { ref ->
                val entity = ref.entity.get()
                if (entity == null) {
                    refs.remove(ref)
                } else {
                    val attributes = host.mediaApiEntityAttributes(entity) ?: return@forEach
                    ref.originalName?.let { value ->
                        runCatching {
                            setMediaApiAttribute(attributes, AppleMediaApiTextAttribute.NAME, value)
                        }
                    }
                    ref.originalArtist?.let { value ->
                        runCatching {
                            setMediaApiAttribute(
                                attributes,
                                AppleMediaApiTextAttribute.ARTIST_NAME,
                                value,
                            )
                        }
                    }
                    ref.originalAlbum?.let { value ->
                        runCatching {
                            setMediaApiAttribute(
                                attributes,
                                AppleMediaApiTextAttribute.ALBUM_NAME,
                                value,
                            )
                        }
                    }
                }
            }
            add(mediaId)
        }
    }

    private fun registerMediaApiAttributes(
        mediaId: String,
        attributes: Any,
        kind: InAppLibraryEntityKind,
    ) {
        mediaApiAttributeBindings[attributes] = InAppMediaApiAttributeBinding(mediaId, kind)
        AppleMediaApiTextAttribute.entries.forEach { attribute ->
            val getter = catalogMember(attribute.getterRuntimeMember)
            val method = runCatching {
                AppleReflection.findMethod(attributes.javaClass, getter, parameterCount = 0)
            }.getOrNull() ?: return@forEach
            if (
                method.returnType != String::class.java ||
                !mediaApiAttributeHookedMethods.add(method)
            ) return@forEach
            runtime.hookRegistrar.installResultOverrideHook(method) { chain, original ->
                val target = chain.thisObject ?: return@installResultOverrideHook original
                val binding = mediaApiAttributeBindings[target]
                    ?: return@installResultOverrideHook original
                recordComposeMediaId(binding.mediaId)
                host.recordCurrentRecyclerMediaId(binding.mediaId)
                host.effectiveAlias(binding.mediaId)?.let { alias ->
                    mediaApiAttributeOverride(binding.kind, attribute, alias)
                } ?: original
            }
            ProviderLogger.info(
                "Apple Music Media API 属性 getter Hook 已安装: " +
                    "class=${method.declaringClass.name}, method=$getter"
            )
        }
    }

    private fun mediaApiAttributeOverride(
        kind: InAppLibraryEntityKind,
        attribute: AppleMediaApiTextAttribute,
        alias: AppleInternalCatalogResolver.Alias,
    ): String? = when (attribute) {
        AppleMediaApiTextAttribute.NAME -> when (kind) {
            InAppLibraryEntityKind.ALBUM -> alias.album.ifBlank { alias.title }
            InAppLibraryEntityKind.SONG -> alias.title
            InAppLibraryEntityKind.ARTIST -> alias.artist.ifBlank { alias.title }
        }
        AppleMediaApiTextAttribute.ARTIST_NAME -> alias.artist
        AppleMediaApiTextAttribute.ALBUM_NAME ->
            if (kind == InAppLibraryEntityKind.SONG) alias.album else null
    }.takeIf { !it.isNullOrBlank() }

    private fun mediaApiAttribute(
        attributes: Any,
        attribute: AppleMediaApiTextAttribute,
    ): String? = runCatching {
        AppleReflection.call(
            attributes,
            catalogMember(attribute.getterRuntimeMember),
        )?.toString()
    }.getOrNull()?.takeIf(String::isNotBlank)

    private fun setMediaApiAttribute(
        attributes: Any,
        attribute: AppleMediaApiTextAttribute,
        value: String,
    ) {
        AppleReflection.call(
            attributes,
            catalogMember(attribute.setterRuntimeMember),
            value,
        )
    }

    private fun catalogMember(member: AppleMusicRuntimeMember): String =
        catalogTarget.runtimeMemberName(member)

    private fun libraryEntityKind(value: String): InAppLibraryEntityKind = when (value) {
        "album" -> InAppLibraryEntityKind.ALBUM
        "song" -> InAppLibraryEntityKind.SONG
        "artist" -> InAppLibraryEntityKind.ARTIST
        else -> error("Unknown Library entity kind: $value")
    }

    fun installComposeHooks() {
        runCatching {
            val resolvedContent = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.LIBRARY_COMPOSE_CONTENT
            )
            val resolvedObserve = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE
            )
            composeObserveTarget = resolvedObserve.target
            val viewModelGetter = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.LIBRARY_COMPOSE_VIEW_MODEL_GETTER
            ).method
            val neverEqualPolicyClass = runtime.hookResolver.resolveClasses(
                AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY
            ).firstOrNull()?.clazz
                ?: error("Compose NeverEqualPolicy class unavailable")
            composeNeverEqualPolicy = neverEqualPolicyClass
                .declaredFields
                .singleOrNull { field ->
                    Modifier.isStatic(field.modifiers) &&
                        neverEqualPolicyClass.isAssignableFrom(field.type)
                }
                ?.apply { isAccessible = true }
                ?.get(null)
                ?: error("Compose NeverEqualPolicy singleton unavailable")
            val recentItemsMethod = resolvedContent.target.runtimeMemberName(
                AppleMusicRuntimeMember.LIBRARY_RECENT_ITEMS_LIVE_RESULT_METHOD
            )

            runtime.hookRegistrar.installHook(
                resolvedContent.method,
                before = { chain ->
                    val fragment = chain.thisObject ?: return@installHook
                    val viewModel = runCatching { viewModelGetter.invoke(fragment) }
                        .getOrNull()
                        ?: return@installHook
                    val liveData = runCatching {
                        AppleReflection.call(viewModel, recentItemsMethod)
                    }.getOrNull() ?: return@installHook
                    activeComposeCapture.set(InAppLibraryComposeCapture(fragment, liveData))
                },
                after = { chain, _ ->
                    val fragment = chain.thisObject ?: return@installHook
                    val capture = activeComposeCapture.get()
                    activeComposeCapture.remove()
                    val fallbackMediaIds = registerComposeContent(
                        fragment = fragment,
                        viewModelGetter = viewModelGetter,
                        recentItemsMethod = recentItemsMethod,
                    )
                    scheduleVisibleResolution(
                        fragment = fragment,
                        capturedMediaIds = capture
                            ?.takeIf { it.fragment === fragment }
                            ?.mediaIds
                            .orEmpty(),
                        fallbackMediaIds = fallbackMediaIds,
                    )
                },
            )
            runtime.hookRegistrar.installHook(
                resolvedObserve.method,
                after = { chain, result ->
                    val capture = activeComposeCapture.get() ?: return@installHook
                    if (chain.args.firstOrNull() !== capture.liveData) return@installHook
                    val state = result ?: return@installHook
                    composeStates[capture.fragment] = WeakReference(state)
                },
            )
            ProviderLogger.info(
                "Apple Music 资料库 Compose 局部刷新 Hook 已安装: " +
                    "content=${resolvedContent.target.className}#" +
                    "${resolvedContent.target.methodName}, observe=" +
                    "${resolvedObserve.target.className}#${resolvedObserve.target.methodName}"
            )
        }.onFailure {
            activeComposeCapture.remove()
            ProviderLogger.error("Apple Music 资料库 Compose 局部刷新 Hook 安装失败", it)
        }
    }

    fun installEpoxyHooks() {
        runCatching {
            val resolved = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.LIBRARY_EPOXY_BUILD
            )
            val buildMethods = resolved.method.declaringClass.declaredMethods.filter { method ->
                method.name == resolved.target.methodName &&
                    method.parameterCount == resolved.target.parameterCount &&
                    !method.isBridge
            }
            check(buildMethods.isNotEmpty()) {
                "LibraryMainContentEpoxyController.buildModels not found"
            }
            buildMethods.forEach { method ->
                method.isAccessible = true
                runtime.hookRegistrar.installHook(
                    method,
                    before = {
                        if (BuildConfig.DEBUG) {
                            host.logMetadataIdentity(
                                event = "library_epoxy_build_begin",
                                details = "triggerMediaId=" +
                                    "${debugLibraryModelRefreshMediaId.get()}, " +
                                    "stack=${host.debugStackSummary()}",
                            )
                        }
                    },
                    after = { chain, _ ->
                        val controller = chain.thisObject ?: return@installHook
                        val recentItems = chain.args.getOrNull(2) as? Iterable<*>
                            ?: return@installHook
                        val mediaIds = buildSet {
                            recentItems.forEach { entity ->
                                entity ?: return@forEach
                                val mediaId = entityMediaId(entity) ?: return@forEach
                                registerController(mediaId, controller)
                                add(mediaId)
                            }
                        }
                        if (BuildConfig.DEBUG) {
                            host.logMetadataIdentity(
                                event = "library_epoxy_build_end",
                                details = "triggerMediaId=" +
                                    "${debugLibraryModelRefreshMediaId.get()}, " +
                                    "controller=${controller.javaClass.name}, " +
                                    "contentIds=$mediaIds",
                            )
                        }
                    },
                )
            }
            ProviderLogger.info(
                "Apple Music 资料库 Epoxy 局部刷新 Hook 已安装: " +
                    "buildMethods=${buildMethods.size}, fallback=${resolved.compatibilityFallback}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 资料库 Epoxy 局部刷新 Hook 安装失败", it)
        }
    }

    fun recordComposeMediaId(mediaId: String) {
        activeComposeCapture.get()?.mediaIds?.add(mediaId)
    }

    fun registerController(mediaId: String, controller: Any) {
        val refs = controllerRefs.computeIfAbsent(mediaId) { ConcurrentLinkedQueue() }
        var registered = false
        refs.forEach { ref ->
            val target = ref.get()
            if (target == null) refs.remove(ref) else if (target === controller) registered = true
        }
        if (!registered) refs.add(WeakReference(controller))
    }

    fun recordControllerBuildAliases(
        controller: Any,
        mediaIds: Collection<String>,
        replace: Boolean,
    ) {
        val normalizedIds = host.normalizeMediaIds(mediaIds)
        synchronized(controllerAppliedAliases) {
            val appliedAliases = if (replace) {
                mutableMapOf<String, AppliedMetadataAlias>().also {
                    controllerAppliedAliases[controller] = it
                }
            } else {
                controllerAppliedAliases.getOrPut(controller) { mutableMapOf() }
            }
            normalizedIds.forEach { mediaId ->
                val alias = host.effectiveAlias(mediaId)
                if (alias == null) appliedAliases.remove(mediaId)
                else appliedAliases[mediaId] = AppliedMetadataAlias(mediaId, alias)
            }
            if (appliedAliases.isEmpty()) controllerAppliedAliases.remove(controller)
        }
        if (BuildConfig.DEBUG && normalizedIds.isNotEmpty()) {
            host.logMetadataIdentity(
                event = "library_epoxy_build_aliases_recorded",
                details = "controller=${controller.javaClass.name}@" +
                    "${System.identityHashCode(controller)}, contentIds=$normalizedIds, " +
                    "replace=$replace",
            )
        }
    }

    fun refreshControllers(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias? = null,
        hasDirectPlaylistRow: Boolean = false,
    ): Int {
        if (!host.isRefreshableMediaId(mediaId)) return 0
        val refs = controllerRefs[mediaId] ?: return 0
        val targets = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var collectedRefs = 0
        var staleRefs = 0
        var alreadyAppliedRefs = 0
        refs.forEach { ref ->
            val controller = ref.get()
            if (controller == null) {
                refs.remove(ref)
                staleRefs += 1
            } else {
                val expectedAppliedAlias = alias?.let { resolvedAlias ->
                    host.controllerAppliedAlias(controller, mediaId, resolvedAlias)
                }
                if (
                    expectedAppliedAlias == null ||
                    synchronized(controllerAppliedAliases) {
                        controllerAppliedAliases[controller]?.get(mediaId)
                    } != expectedAppliedAlias
                ) {
                    collectedRefs += 1
                    targets.add(controller)
                } else {
                    alreadyAppliedRefs += 1
                }
            }
        }
        if (BuildConfig.DEBUG) {
            host.logMetadataIdentity(
                event = "library_epoxy_refresh_decision",
                details = "contentId=$mediaId, refs=${refs.size}, collected=$collectedRefs, " +
                    "stale=$staleRefs, alreadyApplied=$alreadyAppliedRefs, " +
                    "targets=${targets.size}, alias=${alias?.title}/${alias?.artist}/${alias?.album}",
            )
        }
        var scheduledTargets = 0
        targets.forEach { controller ->
            val buildStrategy = host.controllerBuildStrategy(controller)
            if (shouldUsePlaylistDirectRowRefresh(buildStrategy, hasDirectPlaylistRow)) {
                alias?.let { directAlias ->
                    synchronized(controllerAppliedAliases) {
                        controllerAppliedAliases.getOrPut(controller) {
                            mutableMapOf()
                        }[mediaId] = AppliedMetadataAlias(mediaId, directAlias)
                    }
                }
                if (BuildConfig.DEBUG) {
                    host.logMetadataIdentity(
                        event = "library_epoxy_refresh_skipped",
                        details = "contentId=$mediaId, controller=${controller.javaClass.name}, " +
                            "strategy=$buildStrategy, reason=playlist_direct_row",
                    )
                }
                return@forEach
            }
            scheduledTargets += 1
            val dispatch = synchronized(controllerRefreshStates) {
                val state = controllerRefreshStates.getOrPut(controller) {
                    InAppLibraryControllerRefreshState()
                }
                state.enqueue(
                    mediaId = mediaId,
                    strategy = buildStrategy,
                    nowUptimeMillis = SystemClock.uptimeMillis(),
                    albumDebounceMillis = ALBUM_CONTROLLER_REFRESH_DEBOUNCE_MS,
                    playlistIntervalMillis = PLAYLIST_CONTROLLER_REFRESH_INTERVAL_MS,
                )
            }
            if (dispatch == null) {
                if (BuildConfig.DEBUG) {
                    host.logMetadataIdentity(
                        event = "library_epoxy_refresh_coalesced",
                        details = "contentId=$mediaId, controller=${controller.javaClass.name}, " +
                            "strategy=$buildStrategy, reason=rebuild_scheduled",
                    )
                }
                return@forEach
            }
            scheduleControllerRefresh(controller, dispatch)
        }
        return scheduledTargets
    }

    fun detachController(controller: Any): Int {
        var removed = 0
        controllerRefs.forEach { (mediaId, refs) ->
            refs.forEach { ref ->
                val target = ref.get()
                if (target == null || target === controller) {
                    if (refs.remove(ref) && target === controller) removed += 1
                }
            }
            if (refs.isEmpty()) controllerRefs.remove(mediaId, refs)
        }
        controllerRefreshStates.remove(controller)
        controllerAppliedAliases.remove(controller)
        return removed
    }

    fun hasControllerRefs(mediaId: String): Boolean =
        controllerRefs[mediaId]?.any { it.get() != null } == true

    fun liveControllers(mediaId: String): Set<Any> {
        val refs = controllerRefs[mediaId] ?: return emptySet()
        val controllers = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        refs.forEach { ref ->
            val controller = ref.get()
            if (controller == null) refs.remove(ref) else controllers.add(controller)
        }
        return controllers
    }

    fun controllerRefCount(mediaId: String): Int =
        controllerRefs[mediaId]?.count { it.get() != null } ?: 0

    fun refreshComposeStates(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias? = null,
    ): Int {
        if (!host.isRefreshableMediaId(mediaId)) return 0
        val refs = composeStateRefs[mediaId] ?: return 0
        val targets = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val appliedAlias = alias?.let { AppliedMetadataAlias(mediaId, it) }
        refs.forEach { ref ->
            val state = ref.get()
            if (state == null) {
                refs.remove(ref)
            } else if (
                shouldRefreshInAppLibraryComposeAlias(
                    appliedAliases = synchronized(composeAppliedAliases) {
                        composeAppliedAliases[state]?.toMap()
                    },
                    mediaId = mediaId,
                    requestedAlias = appliedAlias,
                )
            ) {
                targets.add(state)
            }
        }
        targets.forEach { state ->
            val shouldPost = synchronized(composeRefreshPending) {
                val pendingAliases = composeRefreshPending.getOrPut(state) { mutableMapOf() }
                val wasEmpty = pendingAliases.isEmpty()
                pendingAliases[mediaId] = appliedAlias
                wasEmpty
            }
            if (!shouldPost) return@forEach
            runtime.mainHandler.post {
                val pendingAliases = synchronized(composeRefreshPending) {
                    composeRefreshPending.remove(state).orEmpty()
                }
                val activeAliases = pendingAliases.filterKeys(host::isRefreshableMediaId)
                if (activeAliases.isEmpty()) return@post
                runCatching {
                    val observeTarget = checkNotNull(composeObserveTarget) {
                        "Compose observeAsState target unavailable"
                    }
                    val policyField = observeTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_POLICY_FIELD
                    )
                    val getValueMethod = observeTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_GET_VALUE_METHOD
                    )
                    val setValueMethod = observeTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_SET_VALUE_METHOD
                    )
                    val originalPolicy = AppleReflection.field(state, policyField)
                    val neverEqualPolicy = composeNeverEqualPolicy
                        ?: error("Compose NeverEqualPolicy unavailable")
                    val value = AppleReflection.call(state, getValueMethod)
                    AppleReflection.setField(state, policyField, neverEqualPolicy)
                    try {
                        AppleReflection.call(state, setValueMethod, value)
                    } finally {
                        AppleReflection.setField(state, policyField, originalPolicy)
                    }
                }.onSuccess {
                    synchronized(composeAppliedAliases) {
                        val stateAliases = composeAppliedAliases.getOrPut(state) { mutableMapOf() }
                        activeAliases.forEach { (activeMediaId, activeAlias) ->
                            if (activeAlias == null) stateAliases.remove(activeMediaId)
                            else stateAliases[activeMediaId] = activeAlias
                        }
                        if (stateAliases.isEmpty()) composeAppliedAliases.remove(state)
                    }
                    if (BuildConfig.DEBUG) {
                        ProviderLogger.info(
                            "Apple Music 元数据链路: " +
                                "seq=${host.nextMetadataTraceSequence()}, " +
                                "event=library_compose_invalidate, " +
                                "contentIds=${activeAliases.keys}, " +
                                "state=${state.javaClass.name}"
                        )
                    }
                }.onFailure {
                    ProviderLogger.error(
                        "Apple Music 资料库 Compose 局部刷新失败: " +
                            "ids=${activeAliases.keys}, state=${state.javaClass.name}",
                        it,
                    )
                }
            }
        }
        return targets.size
    }

    fun hasComposeStateRefs(mediaId: String): Boolean =
        composeStateRefs[mediaId]?.any { it.get() != null } == true

    fun composeStateRefCount(mediaId: String): Int =
        composeStateRefs[mediaId]?.count { it.get() != null } ?: 0

    fun clearConfigurationState() {
        controllerRefreshStates.clear()
        controllerAppliedAliases.clear()
        composeAppliedAliases.clear()
    }

    private fun scheduleControllerRefresh(
        controller: Any,
        dispatch: InAppLibraryControllerRefreshDispatch,
    ) {
        val refresh = Runnable { drainControllerRefresh(controller) }
        if (dispatch.delayMillis == 0L) {
            runtime.mainHandler.post(refresh)
        } else {
            runtime.mainHandler.postDelayed(refresh, dispatch.delayMillis)
        }
    }

    private fun drainControllerRefresh(controller: Any) {
        val pendingMediaIds = synchronized(controllerRefreshStates) {
            val state = controllerRefreshStates[controller]
                ?: return@synchronized emptyList()
            state.takePendingMediaIds()
        }
        val buildStrategy = host.controllerBuildStrategy(controller)
        val pendingMediaIdSet = pendingMediaIds.toSet()
        val albumTrackMediaIds = host.controllerAlbumTrackMediaIds(controller)
        val candidateMediaIds = if (
            buildStrategy == InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA
        ) {
            pendingMediaIds + albumTrackMediaIds
        } else {
            pendingMediaIds
        }
        val activeMediaIds = candidateMediaIds.distinct()
            .filter(host::isRefreshableMediaId)
            .filter { mediaId ->
                val alias = host.effectiveAlias(mediaId)
                if (alias == null) {
                    mediaId in pendingMediaIdSet
                } else {
                    val appliedAlias = host.controllerAppliedAlias(
                        controller = controller,
                        mediaId = mediaId,
                        alias = alias,
                    )
                    synchronized(controllerAppliedAliases) {
                        controllerAppliedAliases[controller]?.get(mediaId)
                    } != appliedAlias
                }
            }
        if (activeMediaIds.isNotEmpty()) {
            val traceMediaId = activeMediaIds.first()
            if (BuildConfig.DEBUG) {
                host.logMetadataIdentity(
                    event = "library_epoxy_refresh_invoke",
                    details = "contentIds=$activeMediaIds, " +
                        "controller=${controller.javaClass.name}, strategy=$buildStrategy",
                )
                debugLibraryModelRefreshMediaId.set(traceMediaId)
            }
            runCatching {
                host.requestControllerBuild(controller, buildStrategy)
            }.onSuccess {
                val rebuiltMediaIds = if (
                    buildStrategy == InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA
                ) {
                    (activeMediaIds + albumTrackMediaIds).distinct()
                } else {
                    activeMediaIds
                }
                synchronized(controllerAppliedAliases) {
                    val appliedAliases = controllerAppliedAliases.getOrPut(controller) {
                        mutableMapOf()
                    }
                    rebuiltMediaIds.forEach { mediaId ->
                        val alias = host.effectiveAlias(mediaId)
                        if (alias == null) {
                            appliedAliases.remove(mediaId)
                        } else {
                            appliedAliases[mediaId] = host.controllerAppliedAlias(
                                controller = controller,
                                mediaId = mediaId,
                                alias = alias,
                            )
                        }
                    }
                }
                if (BuildConfig.DEBUG) {
                    ProviderLogger.info(
                        "Apple Music 元数据链路: " +
                            "seq=${host.nextMetadataTraceSequence()}, " +
                            "event=library_epoxy_rebuild, contentIds=$activeMediaIds, " +
                            "controller=${controller.javaClass.name}"
                    )
                    runtime.mainHandler.post {
                    }
                }
            }.onFailure {
                ProviderLogger.error(
                    "Apple Music 资料库 Epoxy 合并刷新失败: " +
                        "ids=$activeMediaIds, controller=${controller.javaClass.name}",
                    it,
                )
            }
            synchronized(controllerRefreshStates) {
                controllerRefreshStates[controller]?.recordBuildAttempt(SystemClock.uptimeMillis())
            }
            if (BuildConfig.DEBUG) debugLibraryModelRefreshMediaId.remove()
        }
        val nextDispatch = synchronized(controllerRefreshStates) {
            val state = controllerRefreshStates[controller]
                ?: return@synchronized null
            val dispatch = state.finishDrain(
                strategy = buildStrategy,
                nowUptimeMillis = SystemClock.uptimeMillis(),
                albumDebounceMillis = ALBUM_CONTROLLER_REFRESH_DEBOUNCE_MS,
                playlistIntervalMillis = PLAYLIST_CONTROLLER_REFRESH_INTERVAL_MS,
            )
            if (
                !state.scheduled &&
                buildStrategy != InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD
            ) {
                controllerRefreshStates.remove(controller)
            }
            dispatch
        }
        if (nextDispatch != null) scheduleControllerRefresh(controller, nextDispatch)
    }

    private fun registerComposeContent(
        fragment: Any,
        viewModelGetter: java.lang.reflect.Method,
        recentItemsMethod: String,
    ): List<String> {
        val state = composeStates[fragment]?.get() ?: return emptyList()
        val viewModel = runCatching { viewModelGetter.invoke(fragment) }.getOrNull()
            ?: return emptyList()
        val liveData = runCatching {
            AppleReflection.call(viewModel, recentItemsMethod)
        }.getOrNull() ?: return emptyList()
        val recentItems = runCatching {
            AppleReflection.call(liveData, "getValue") as? Iterable<*>
        }.getOrNull() ?: return emptyList()
        return buildList {
            recentItems.forEach { entity ->
                entity ?: return@forEach
                val mediaId = entityMediaId(entity) ?: return@forEach
                registerComposeState(mediaId, state)
                add(mediaId)
            }
        }.distinct()
    }

    private fun scheduleVisibleResolution(
        fragment: Any,
        capturedMediaIds: Collection<String>,
        fallbackMediaIds: Collection<String>,
    ) {
        val state = composeStates[fragment]?.get() ?: return
        val mediaIds = composeVisibleMetadataResolutionIds(
            capturedMediaIds = capturedMediaIds,
            fallbackMediaIds = fallbackMediaIds,
            limit = MAX_VISIBLE_RESOLUTION_IDS,
        )
        if (mediaIds.isEmpty()) return
        mediaIds.forEach { mediaId -> registerComposeState(mediaId, state) }
        recordComposeBuildAliases(state, mediaIds)
        val shouldPost = synchronized(composeVisibleResolutionPending) {
            val pending = composeVisibleResolutionPending[state]
            if (pending != null) {
                pending.addAll(mediaIds)
                false
            } else {
                composeVisibleResolutionPending[state] = mediaIds.toCollection(linkedSetOf())
                true
            }
        }
        if (!shouldPost) return
        if (BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                    "event=library_compose_visible_candidates, " +
                    "source=${if (host.normalizeMediaIds(capturedMediaIds).isNotEmpty()) {
                        "render_capture"
                    } else {
                        "first_items_fallback"
                    }}, contentIds=$mediaIds"
            )
        }
        postVisibleResolution(state)
    }

    private fun postVisibleResolution(state: Any) {
        runtime.mainHandler.post {
            Choreographer.getInstance().postFrameCallback {
                drainVisibleResolution(state)
            }
        }
    }

    private fun drainVisibleResolution(state: Any) {
        val (mediaIds, hasMore) = synchronized(composeVisibleResolutionPending) {
            val pending = composeVisibleResolutionPending[state]
                ?: return@synchronized emptyList<String>() to false
            val batch = pending.take(MAX_VISIBLE_RESOLUTION_IDS)
            pending.removeAll(batch.toSet())
            val remaining = pending.isNotEmpty()
            if (!remaining) composeVisibleResolutionPending.remove(state)
            batch to remaining
        }
        resolveVisibleMediaIds(mediaIds)
        if (hasMore) postVisibleResolution(state)
    }

    private fun resolveVisibleMediaIds(mediaIds: Collection<String>) {
        val normalizedIds = host.normalizeMediaIds(mediaIds)
        if (normalizedIds.isEmpty()) return
        val aliasesBeforeEnrichment = normalizedIds.associateWith(host::effectiveAlias)
        enrichEntitiesForResolution(normalizedIds)
        host.markMetadataVisible(normalizedIds)
        normalizedIds.forEach { mediaId ->
            val alias = host.effectiveAlias(mediaId) ?: return@forEach
            if (aliasesBeforeEnrichment[mediaId] != alias) {
                host.applyAliasToMetadataRefs(mediaId, alias)
            }
        }
        host.scheduleMetadataResolution(
            mediaIds = normalizedIds,
            priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
        )
        if (BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                    "event=library_compose_visible_request, contentIds=$normalizedIds, " +
                    "afterFirstFrame=true"
            )
        }
    }

    private fun registerComposeState(mediaId: String, state: Any) {
        val refs = composeStateRefs.computeIfAbsent(mediaId) { ConcurrentLinkedQueue() }
        var registered = false
        refs.forEach { ref ->
            val target = ref.get()
            if (target == null) refs.remove(ref) else if (target === state) registered = true
        }
        if (!registered) {
            refs.add(WeakReference(state))
            if (BuildConfig.DEBUG) {
                ProviderLogger.info(
                    "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                        "event=library_compose_capture, contentId=$mediaId, " +
                        "state=${state.javaClass.name}"
                )
            }
        }
    }

    private fun recordComposeBuildAliases(state: Any, mediaIds: Collection<String>) {
        val normalizedIds = host.normalizeMediaIds(mediaIds)
        synchronized(composeAppliedAliases) {
            val appliedAliases = composeAppliedAliases.getOrPut(state) { mutableMapOf() }
            normalizedIds.forEach { mediaId ->
                val alias = host.effectiveAlias(mediaId)
                if (alias == null) appliedAliases.remove(mediaId)
                else appliedAliases[mediaId] = AppliedMetadataAlias(mediaId, alias)
            }
            if (appliedAliases.isEmpty()) composeAppliedAliases.remove(state)
        }
    }

    private val controllerRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<WeakReference<Any>>>()
    private val controllerRefreshStates =
        Collections.synchronizedMap(
            WeakHashMap<Any, InAppLibraryControllerRefreshState>()
        )
    private val controllerAppliedAliases =
        Collections.synchronizedMap(
            WeakHashMap<Any, MutableMap<String, AppliedMetadataAlias>>()
        )
}
