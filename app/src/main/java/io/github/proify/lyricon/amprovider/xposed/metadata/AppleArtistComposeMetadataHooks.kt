/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.os.Looper
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/** Exact profiles2 page ownership; no now-playing identity or global text substitution. */
internal class AppleArtistComposeMetadataHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val host: AppleArtistSurfaceHost,
) {
    private class Page(
        val viewModel: WeakReference<Any>,
        var fragment: WeakReference<Any>?,
        val entities: Array<*>,
        val responseKind: Any,
        val rootId: String,
    ) {
        val root: Any get() = requireNotNull(entities.first())
        val mediaIds = linkedSetOf<String>()
        val aliases = mutableMapOf<String, Alias>()
        val requested = mutableSetOf<String>()
        var dirty = false
        var queued = false
    }

    private val pages = Collections.synchronizedMap(WeakHashMap<Any, Page>())
    private val fragments = WeakIdentityMap<Any, WeakReference<Any>>()
    private val owners = WeakIdentityMap<Any, WeakReference<Page>>()
    private var replaying = false
    private var responseMethod: Method? = null
    private var currentData: Method? = null
    private var isResumed: Method? = null

    fun install() {
        val resolver = runtime.hookResolver
        if (AppleMusicHookProfiles.exactTargets(resolver.version, AppleMusicHookPoint.ARTIST_COMPOSE_DATA).isEmpty()) return
        runCatching {
            fun method(point: AppleMusicHookPoint) = resolver.resolveMethod(point).method
            val data = resolver.resolveMethod(AppleMusicHookPoint.ARTIST_COMPOSE_DATA)
            responseMethod = data.method
            currentData = method(AppleMusicHookPoint.ARTIST_COMPOSE_CURRENT_DATA)
            isResumed = method(AppleMusicHookPoint.ARTIST_COMPOSE_FRAGMENT_RESUMED)
            val viewModelGetter = method(AppleMusicHookPoint.ARTIST_COMPOSE_VIEW_MODEL_GETTER)
            val entityType = method(AppleMusicHookPoint.ARTIST_COMPOSE_ENTITY_TYPE)
            val views = method(AppleMusicHookPoint.ARTIST_COMPOSE_ENTITY_VIEWS)
            val relationships = method(AppleMusicHookPoint.ARTIST_COMPOSE_ENTITY_RELATIONSHIPS)
            val children = method(AppleMusicHookPoint.ARTIST_COMPOSE_RELATIONSHIP_ENTITIES)
            val responseKinds = setOf(
                data.target.runtimeMemberName(AppleMusicRuntimeMember.ARTIST_COMPOSE_GENERIC_KIND),
                data.target.runtimeMemberName(AppleMusicRuntimeMember.ARTIST_COMPOSE_SIMPLIFIED_KIND),
            )
            runtime.hookRegistrar.installHook(data.method, after = { chain, _ ->
                if (replaying) return@installHook
                val vm = chain.thisObject ?: return@installHook
                if (!viewModelGetter.returnType.isInstance(vm)) return@installHook
                val entities = chain.args.getOrNull(0) as? Array<*> ?: return@installHook
                val root = entities.firstOrNull() ?: return@installHook
                val kind = chain.args.getOrNull(1) as? Enum<*> ?: return@installHook
                if (kind.name !in responseKinds || currentData?.invoke(vm) !== root) return@installHook
                val id = host.mediaApiEntityCatalogId(root) ?: return@installHook
                val page = Page(WeakReference(vm), fragments[vm], entities, kind, id)
                pages[vm] = page
                val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
                fun capture(entity: Any, depth: Int) {
                    if (depth > 3 || seen.size >= 256 || !seen.add(entity)) return
                    val mediaId = host.mediaApiEntityCatalogId(entity)
                    val type = artistComposeEntityKind(entityType.invoke(entity) as? String)
                    if (mediaId != null && type != null) {
                        owners[entity] = WeakReference(page)
                        page.mediaIds += mediaId
                        host.registerLibraryEntity(mediaId, entity, type)
                        // The native header was built before this callback. A warm alias may
                        // already be applied to the entity, but still needs a new UI state.
                        host.effectiveAlias(mediaId)?.let { alias ->
                            page.aliases[mediaId] = alias
                            page.dirty = true
                        }
                    }
                    listOf(views, relationships).forEach { getter ->
                        val relations = getter.invoke(entity) as? Map<*, *> ?: return@forEach
                        relations.values.filterNotNull().forEach { relation ->
                            (children.invoke(relation) as? Array<*>)?.filterNotNull()?.forEach {
                                capture(it, depth + 1)
                            }
                        }
                    }
                }
                capture(root, 0)
                if (BuildConfig.DEBUG) host.logMetadataIdentity(
                    "artist_compose_capture", "artistId=$id, entities=${page.mediaIds.size}, kind=${kind.name}"
                )
                request(page, id)
                if (page.dirty) queueRefresh(page)
            })
            runtime.hookRegistrar.installHook(method(AppleMusicHookPoint.ARTIST_COMPOSE_CONTENT), before = { chain ->
                val fragment = chain.thisObject ?: return@installHook
                val vm = viewModelGetter.invoke(fragment) ?: return@installHook
                fragments[vm] = WeakReference(fragment)
                pages[vm]?.let { page ->
                    page.fragment = WeakReference(fragment)
                    request(page, page.rootId)
                    if (page.dirty) queueRefresh(page)
                }
            })
            runtime.hookRegistrar.installHook(method(AppleMusicHookPoint.ARTIST_COMPOSE_ENTITY_TITLE), after = { chain, _ ->
                if (replaying) return@installHook
                val entity = chain.thisObject ?: return@installHook
                val page = owners[entity]?.get() ?: return@installHook
                val id = host.mediaApiEntityCatalogId(entity) ?: return@installHook
                request(page, id)
            })
        }.onFailure { ProviderLogger.error("Apple Music Compose 歌手页元数据 Hook 安装失败", it) }
    }

    private fun active(page: Page): Boolean {
        val vm = page.viewModel.get() ?: return false
        val fragment = page.fragment?.get() ?: return false
        return pages[vm] === page && currentData?.invoke(vm) === page.root &&
            isResumed?.invoke(fragment) == true
    }

    private fun request(page: Page, id: String) {
        if (Looper.myLooper() != runtime.mainHandler.looper) return
        // Actual title consumption triggers resolution; capture itself does not prefetch shelves.
        if (!active(page) || !host.shouldRequestOverride(id) || !page.requested.add(id)) return
        runtime.mainHandler.post {
            page.requested.remove(id)
            if (!active(page)) return@post
            host.markMetadataVisible(listOf(id))
            host.enrichLibraryEntitiesForResolution(listOf(id))
            host.scheduleMetadataResolution(listOf(id), RequestPriority.VISIBLE, InAppOriginalResolutionMode.ORIGINAL_FIRST)
            if (BuildConfig.DEBUG) host.logMetadataIdentity(
                "artist_compose_resolve", "artistId=${page.rootId}, contentId=$id"
            )
        }
    }

    fun refresh(mediaId: String, alias: Alias): Int {
        val targets = synchronized(pages) { pages.values.filter { mediaId in it.mediaIds } }
        targets.forEach { page ->
            if (page.aliases.put(mediaId, alias) != alias) {
                page.dirty = true
                queueRefresh(page)
            }
        }
        return targets.size
    }

    private fun queueRefresh(page: Page) {
        if (page.queued || replaying) return
        page.queued = true
        runtime.mainHandler.post {
            page.queued = false
            if (!page.dirty || !active(page)) return@post
            val vm = page.viewModel.get() ?: return@post
            runCatching {
                replaying = true
                responseMethod?.invoke(vm, page.entities, page.responseKind)
                page.dirty = false
                if (BuildConfig.DEBUG) host.logMetadataIdentity(
                    "artist_compose_rebind", "artistId=${page.rootId}, aliases=${page.aliases.size}"
                )
            }.onFailure { ProviderLogger.error("Apple Music Compose 歌手页元数据刷新失败", it) }
                .also { replaying = false }
        }
    }
}

internal fun artistComposeEntityKind(type: String?): InAppLibraryEntityKind? = when (type) {
    "artists", "library-artists" -> InAppLibraryEntityKind.ARTIST
    "songs", "library-songs" -> InAppLibraryEntityKind.SONG
    "albums", "library-albums" -> InAppLibraryEntityKind.ALBUM
    else -> null
}
