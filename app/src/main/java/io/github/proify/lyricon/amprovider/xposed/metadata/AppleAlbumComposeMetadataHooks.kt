/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import io.github.proify.lyricon.amprovider.xposed.AppleAlbumComposePageRegistry.Page

/** Owns only collection2 album rows. Native mapping and flow publication remain authoritative. */
internal class AppleAlbumComposeMetadataHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val host: AppleArtistSurfaceHost,
) {
    private data class Mapping(val page: Page, val revision: Long)
    private val pages = AppleAlbumComposePageRegistry()
    private val fragments = WeakIdentityMap<Any, WeakReference<Any>>()
    private val mapping = ThreadLocal<Mapping?>()
    private val diagnostics = if (BuildConfig.DEBUG) AppleAlbumComposeDiagnostics(host) else null
    private var currentData: Method? = null
    private var resumed: Method? = null
    private var refreshRows: Method? = null
    private var refreshHeader: Method? = null

    fun install() {
        val resolver = runtime.hookResolver
        if (AppleMusicHookProfiles.exactTargets(resolver.version, AppleMusicHookPoint.ALBUM_COMPOSE_CONTENT).isEmpty()) return
        runCatching {
            fun method(point: AppleMusicHookPoint) = resolver.resolveMethod(point).method
            currentData = method(AppleMusicHookPoint.ALBUM_COMPOSE_CURRENT_DATA)
            resumed = method(AppleMusicHookPoint.ARTIST_COMPOSE_FRAGMENT_RESUMED)
            refreshRows = method(AppleMusicHookPoint.ALBUM_COMPOSE_REFRESH)
            refreshHeader = method(AppleMusicHookPoint.ALBUM_COMPOSE_HEADER_REFRESH).also {
                // The registrar records installation and the first native hit in Debug only.
                if (BuildConfig.DEBUG) runtime.hookRegistrar.installHook(it)
            }
            val vmGetter = method(AppleMusicHookPoint.ALBUM_COMPOSE_VIEW_MODEL_GETTER)
            val pageId = method(AppleMusicHookPoint.ALBUM_COMPOSE_PAGE_ID)
            val entityId = method(AppleMusicHookPoint.ALBUM_COMPOSE_ENTITY_ID)
            val entityType = method(AppleMusicHookPoint.ARTIST_COMPOSE_ENTITY_TYPE)
            val relationships = method(AppleMusicHookPoint.ARTIST_COMPOSE_ENTITY_RELATIONSHIPS)
            val children = method(AppleMusicHookPoint.ARTIST_COMPOSE_RELATIONSHIP_ENTITIES)
            val rowTarget = resolver.resolveMethod(AppleMusicHookPoint.ALBUM_COMPOSE_ROW)
            val rowClass = rowTarget.method.parameterTypes[1]
            val key = rowClass.getDeclaredField(rowTarget.target.runtimeMemberName(AppleMusicRuntimeMember.ALBUM_COMPOSE_ROW_KEY_FIELD)).apply { isAccessible = true }
            val keyId = key.type.getDeclaredField(rowTarget.target.runtimeMemberName(AppleMusicRuntimeMember.ALBUM_COMPOSE_KEY_ID_FIELD)).apply { isAccessible = true }

            fun capture(vm: Any, root: Any): Page? {
                val nativeRoot = currentData?.invoke(vm)
                diagnostics?.once(root, "capture:${System.identityHashCode(nativeRoot)}", "album_compose_input") {
                    "vm=${System.identityHashCode(vm)}, input=${System.identityHashCode(root)}, " +
                        "inputType=${root.javaClass.name}, rawId=${entityId.invoke(root)}, " +
                        "catalogId=${host.mediaApiEntityCatalogId(root)}, " +
                        "native=${System.identityHashCode(nativeRoot)}, sameRoot=${nativeRoot === root}, " +
                        "nativeRawId=${nativeRoot?.let { entityId.invoke(it) }}, " +
                        "nativeCatalogId=${nativeRoot?.let { host.mediaApiEntityCatalogId(it) }}"
                }
                val page = pages.capture(
                    vm = vm,
                    ownerId = pageId.invoke(vm) as? String,
                    root = root,
                    nativeRoot = nativeRoot,
                    rawId = entityId.invoke(root) as? String,
                    catalogId = host.mediaApiEntityCatalogId(root),
                ) ?: return null
                fun register(entity: Any) {
                    val kind = artistComposeEntityKind(entityType.invoke(entity) as? String) ?: return
                    val id = host.mediaApiEntityCatalogId(entity) ?: return
                    val rawId = entityId.invoke(entity) as? String ?: return
                    page.ids[rawId] = id
                    if (page.registered[entity] != true) {
                        page.registered[entity] = true
                        // Registration applies a warm alias before native code copies the row strings.
                        host.registerLibraryEntity(id, entity, kind)
                        host.effectiveAlias(id)?.let { page.updateAlias(id, it) }
                    }
                }
                register(root)
                val relations = relationships.invoke(root) as? Map<*, *>
                relations?.values?.filterNotNull()?.forEach { relation ->
                    (children.invoke(relation) as? Array<*>)?.filterNotNull()?.forEach(::register)
                }
                diagnostics?.once(root, "registered", "album_compose_capture") {
                    "albumId=${page.id}, rootCatalogId=${page.catalogId}, entities=${page.ids.size}"
                }
                return page
            }

            runtime.hookRegistrar.installHook(method(AppleMusicHookPoint.ALBUM_COMPOSE_CONTENT), before = { chain ->
                val fragment = chain.thisObject ?: return@installHook
                val vm = vmGetter.invoke(fragment) ?: return@installHook
                fragments[vm] = WeakReference(fragment)
                val root = currentData?.invoke(vm) ?: return@installHook
                val page = capture(vm, root) ?: return@installHook
                page.catalogId?.let { request(page, it) }
                if (page.needsRefresh()) queueRefresh(page)
            })
            runtime.hookRegistrar.installHook(method(AppleMusicHookPoint.ALBUM_COMPOSE_RESUME), after = { chain, _ ->
                val fragment = chain.thisObject ?: return@installHook
                val vm = vmGetter.invoke(fragment) ?: return@installHook
                fragments[vm] = WeakReference(fragment)
                val root = currentData?.invoke(vm) ?: return@installHook
                val page = capture(vm, root) ?: return@installHook
                page.catalogId?.let { request(page, it) }
                if (page.needsRefresh()) queueRefresh(page)
            })
            runtime.hookRegistrar.installScopedHook(
                method(AppleMusicHookPoint.ALBUM_COMPOSE_TRACK_MAPPER),
                enter = { chain ->
                    val vm = chain.thisObject
                    val root = chain.args.firstOrNull()
                    val page = if (vm != null && root != null) capture(vm, root) else null
                    mapping.set(page?.let { Mapping(it, it.revision.snapshot()) })
                    true
                },
                after = { chain, result ->
                    val mapped = mapping.get()
                    val input = chain.args.firstOrNull()
                    if (input != null) diagnostics?.once(input, "mapped:${mapped?.page?.id}", "album_compose_output") {
                        val nativeRows = result as? List<*>
                        "albumId=${mapped?.page?.id}, vm=${System.identityHashCode(chain.thisObject)}, " +
                            "input=${System.identityHashCode(input)}, rows=${nativeRows?.size}, " +
                            "ids=${mapped?.page?.ids}, sample=${nativeRows?.take(3)?.toString()?.take(1800)}"
                    }
                    if (mapped != null) {
                        val page = mapped.page
                        (result as? List<*>)?.filterNotNull()?.forEach { row ->
                            if (rowClass.isInstance(row)) {
                                val rawId = key.get(row)?.let { keyId.get(it) } as? String
                                pages.bindRow(page, row, rawId)
                            }
                        }
                        page.revision.mapped(mapped.revision)
                        if (BuildConfig.DEBUG) host.logMetadataIdentity(
                            "album_compose_rebuild", "albumId=${page.id}, rows=${(result as? List<*>)?.size}, revision=${mapped.revision}"
                        )
                        if (page.needsRefresh()) queueRefresh(page)
                    }
                },
                exit = { mapping.remove() },
            )
            runtime.hookRegistrar.installResultOverrideHook(method(AppleMusicHookPoint.ALBUM_COMPOSE_TRACK_COMPARATOR)) { chain, original ->
                val root = chain.args.getOrNull(1)
                // Same-root aliases otherwise disappear at native distinctUntilChanged.
                if (original == true && root != null && pages.invalidates(root) { page ->
                        page.vm.get()?.let { currentData?.invoke(it) }
                    }
                ) false else original
            }
            runtime.hookRegistrar.installHook(rowTarget.method, before = { chain ->
                val nativeRow = chain.args.getOrNull(1) ?: return@installHook
                val row = pages.row(nativeRow)
                val page = row?.page?.get()
                diagnostics?.once(nativeRow, "row:${row?.id}:${page?.id}", "album_compose_row") {
                    val rawId = key.get(nativeRow)?.let { keyId.get(it) }
                    val knownPages = pages.snapshot().map {
                        "${it.id}:vm=${System.identityHashCode(it.vm.get())}:current=${current(it)}:active=${active(it)}:match=${if (rawId == null) null else it.ids[rawId]}"
                    }
                    "rawId=$rawId, contentId=${row?.id}, albumId=${page?.id}, " +
                        "knownPages=$knownPages, model=${nativeRow.toString().take(1200)}"
                }
                if (row == null || page == null) return@installHook
                request(page, row.id)
            })
        }.onFailure { ProviderLogger.error("Apple Music Compose 专辑行 Hook 安装失败", it) }
    }

    private fun current(page: Page): Boolean {
        val vm = page.vm.get() ?: return false
        return pages.current(page, currentData?.invoke(vm))
    }

    private fun active(page: Page): Boolean {
        val vm = page.vm.get() ?: return false
        val fragment = fragments[vm]?.get() ?: return false
        return current(page) && resumed?.invoke(fragment) == true
    }

    private fun request(page: Page, id: String) {
        runtime.mainHandler.post {
            val pageActive = active(page)
            val shouldRequest = pageActive && host.shouldRequestOverride(id)
            diagnostics?.once(page.root, "request:$id:$pageActive:$shouldRequest", "album_compose_request_gate") {
                val vm = page.vm.get()
                val fragment = vm?.let { fragments[it]?.get() }
                "albumId=${page.id}, contentId=$id, active=$pageActive, shouldRequest=$shouldRequest, " +
                    "vm=${System.identityHashCode(vm)}, fragment=${System.identityHashCode(fragment)}, " +
                    "resumed=${fragment?.let { resumed?.invoke(it) }}, current=${current(page)}, " +
                    "alias=${host.effectiveAlias(id)}"
            }
            if (!pageActive || !shouldRequest || !page.requested.add(id)) return@post
            runtime.mainHandler.post resolve@{
                page.requested.remove(id)
                if (!active(page)) return@resolve
                host.markMetadataVisible(listOf(id))
                host.enrichLibraryEntitiesForResolution(listOf(id))
                host.scheduleMetadataResolution(listOf(id), RequestPriority.VISIBLE, InAppOriginalResolutionMode.ORIGINAL_FIRST)
                if (BuildConfig.DEBUG) host.logMetadataIdentity("album_compose_visible", "albumId=${page.id}, contentId=$id")
            }
        }
    }

    fun refresh(id: String, alias: Alias): Int {
        val targets = pages.forMediaId(id)
        targets.forEach { if (it.updateAlias(id, alias)) queueRefresh(it) }
        return targets.size
    }

    private fun queueRefresh(page: Page) {
        runtime.mainHandler.post {
            if (page.queued) return@post
            page.queued = true
            runtime.mainHandler.post refresh@{
                page.queued = false
                if (!page.needsRefresh() || !active(page)) return@refresh
                if (page.headerRevision.dirty()) {
                    val revision = page.headerRevision.snapshot()
                    runCatching {
                        refreshHeader?.invoke(page.vm.get())
                        page.headerRevision.mapped(revision)
                        if (BuildConfig.DEBUG) host.logMetadataIdentity(
                            "album_compose_header_refresh", "albumId=${page.id}, revision=$revision"
                        )
                    }.onFailure { ProviderLogger.error("Apple Music Compose 专辑头刷新失败", it) }
                }
                if (!page.revision.dirty()) return@refresh
                runCatching {
                    refreshRows?.invoke(page.vm.get())
                    if (BuildConfig.DEBUG) host.logMetadataIdentity(
                        "album_compose_refresh", "albumId=${page.id}, refreshMethod=${refreshRows?.name}"
                    )
                }.onFailure { ProviderLogger.error("Apple Music Compose 专辑行刷新失败", it) }
            }
        }
    }
}

/** A completion for older row text must never acknowledge an alias that arrived while mapping. */
internal class AppleAlbumRowRevision {
    private val aliases = mutableMapOf<String, Alias>()
    private var revision = 0L
    private var rendered = 0L
    @Synchronized fun update(id: String, alias: Alias): Boolean {
        if (aliases.put(id, alias) == alias) return false
        revision++
        return true
    }
    @Synchronized fun snapshot(): Long = revision
    @Synchronized fun invalidateIfKnown() { if (aliases.isNotEmpty()) revision++ }
    @Synchronized fun mapped(value: Long) { rendered = maxOf(rendered, value) }
    @Synchronized fun dirty(): Boolean = rendered < revision
}
