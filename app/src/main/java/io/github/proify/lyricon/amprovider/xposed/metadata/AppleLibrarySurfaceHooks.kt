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

internal class AppleLibrarySurfaceHooks(
    internal val runtime: AppleMusicProviderRuntime,
    private val metadataStore: AppleMetadataOverrideStore,
    internal val host: AppleLibrarySurfaceHost,
) {
    internal companion object {
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

    internal val activeComposeCapture = ThreadLocal<InAppLibraryComposeCapture?>()
    internal val debugLibraryModelRefreshMediaId = ThreadLocal<String?>()
    internal val composeStates =
        Collections.synchronizedMap(WeakHashMap<Any, WeakReference<Any>>())
    internal val composeStateRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<WeakReference<Any>>>()
    internal val composeRefreshPending =
        Collections.synchronizedMap(
            WeakHashMap<Any, MutableMap<String, AppliedMetadataAlias?>>()
        )
    internal val composeVisibleResolutionPending =
        Collections.synchronizedMap(WeakHashMap<Any, MutableSet<String>>())
    internal val composeAppliedAliases =
        Collections.synchronizedMap(
            WeakHashMap<Any, MutableMap<String, AppliedMetadataAlias>>()
        )
    @Volatile
    internal var composeNeverEqualPolicy: Any? = null
    @Volatile
    internal var composeObserveTarget: AppleMusicHookTarget? = null

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
            RequestPriority.VISIBLE
        ) {
            enrichEntity(mediaId, entity, kind, attributes)
            host.scheduleMetadataResolution(
                mediaIds = listOf(mediaId),
                priority = RequestPriority.VISIBLE,
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
        alias: Alias,
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
        alias: Alias,
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
        alias: Alias,
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

    internal val controllerRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<WeakReference<Any>>>()
    internal val controllerRefreshStates =
        Collections.synchronizedMap(
            WeakHashMap<Any, InAppLibraryControllerRefreshState>()
        )
    internal val controllerAppliedAliases =
        Collections.synchronizedMap(
            WeakHashMap<Any, MutableMap<String, AppliedMetadataAlias>>()
        )
}
