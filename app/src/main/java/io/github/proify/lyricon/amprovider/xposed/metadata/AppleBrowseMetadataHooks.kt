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
import java.util.Collections
import java.util.IdentityHashMap

/** Exact native card consumption, including newtab/radio lazy items and search result rows. */
internal class AppleBrowseMetadataHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val host: AppleArtistSurfaceHost,
    private val bindings: AppleDataBindingMetadataHooks,
    metadataStore: AppleMetadataOverrideStore,
    catalog: AppleInternalCatalogResolver,
    originalEnabled: () -> Boolean,
) {
    private val scopes = AppleBrowseScopeRegistry()
    private val registered = WeakIdentityMap<Any, String>()
    private val rows = AppleBrowseEpoxyRows(runtime)
    private val pending = mutableSetOf<String>()
    private val invalidations = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private var invalidationPosted = false
    private var invalidate: Method? = null
    private val kinds by lazy { runtime.hookResolver.resolveClasses(AppleMusicHookPoint.LIBRARY_ENTITY_CLASSES) }
    private val radio = AppleRadioStationMetadataHooks(runtime, host, metadataStore, bindings, catalog, originalEnabled) { key, title ->
        queueInvalidation(scopes.changed(key, title))
        rows.changed(key, title)
    }

    fun install() {
        val resolver = runtime.hookResolver
        if (AppleMusicHookProfiles.exactTargets(resolver.version, AppleMusicHookPoint.BROWSE_COMPOSE_ITEM).isEmpty()) return
        runCatching { radio.install() }.onFailure { ProviderLogger.error("Apple Music 电台原名 Hook 安装失败", it) }
        runCatching {
            val currentScope = resolver.resolveMethod(AppleMusicHookPoint.BROWSE_COMPOSER_SCOPE).method
            val useScope = resolver.resolveMethod(AppleMusicHookPoint.BROWSE_COMPOSER_USE_SCOPE).method
            invalidate = resolver.resolveMethod(AppleMusicHookPoint.BROWSE_SCOPE_INVALIDATE).method
            resolver.resolveClasses(AppleMusicHookPoint.BROWSE_COMPOSE_ITEM).forEach { resolved ->
                val target = resolved.target
                val method = resolved.clazz.declaredMethods.single {
                    it.name == target.methodName && it.parameterTypes.map(Class<*>::getName) == target.parameterTypeNames &&
                        it.returnType == Void.TYPE
                }.apply { isAccessible = true }
                runtime.hookRegistrar.installHook(method, before = { chain ->
                    val index = chain.args.getOrNull(1) as? Int ?: return@installHook
                    val items = chain.args.getOrNull(2) as? List<*> ?: return@installHook
                    val entity = items.getOrNull(index) ?: return@installHook
                    val composer = chain.args.getOrNull(9) ?: return@installHook
                    // The DEX renderers use a replaceable group and synchronously read this item.
                    // Its parent native lazy-item scope re-enters the renderer on invalidate().
                    val scope = currentScope.invoke(composer) ?: return@installHook
                    useScope.invoke(composer, scope)
                    val id = register(entity)
                    if (id != null) {
                        scopes.record(id, scope, host.effectiveAlias(id))
                        request(id, scope)
                    } else {
                        radio.observe(entity)?.let { key -> scopes.record(key, scope, radio.display(key)) }
                    }
                })
            }
            ProviderLogger.info("Apple Music 新发现/广播单项元数据 Hook 已安装")
        }.onFailure { ProviderLogger.error("Apple Music 浏览卡片 Compose Hook 安装失败", it) }
        installHomeBinding()
        installSearchResults()
    }

    private fun register(entity: Any): String? {
        val kind = inAppLibraryEntityKindForProfileClasses(entity, kinds) ?: return null
        val id = host.mediaApiEntityCatalogId(entity) ?: return null
        if (registered[entity] != id) {
            host.registerLibraryEntity(id, entity, kind)
            registered[entity] = id
        }
        return id
    }

    private fun request(id: String, consumer: Any) {
        if (!host.shouldRequestOverride(id) || !pending.add(id)) return
        val weak = WeakReference(consumer)
        runtime.mainHandler.post {
            pending.remove(id)
            if (weak.get() == null) return@post
            host.markMetadataVisible(listOf(id))
            host.enrichLibraryEntitiesForResolution(listOf(id))
            host.scheduleMetadataResolution(listOf(id), RequestPriority.VISIBLE, InAppOriginalResolutionMode.ORIGINAL_FIRST)
            if (BuildConfig.DEBUG) host.logMetadataIdentity("browse_visible", "contentId=$id, originalResolutionMode=ORIGINAL_FIRST")
        }
    }

    private fun installHomeBinding() = runCatching {
        val resolver = runtime.hookResolver
        val entityType = resolver.resolveClass(AppleMusicHookPoint.LISTEN_NOW_MEDIA_ENTITY).clazz
        runtime.hookRegistrar.installHook(resolver.resolveMethod(AppleMusicHookPoint.LISTEN_NOW_BOUND_LISTENER).method, after = { chain, _ ->
            val listener = chain.thisObject ?: return@installHook
            val entity = collectionPageRowEntity(listener, entityType) ?: return@installHook
            val holder = chain.args.getOrNull(1) ?: return@installHook
            val binding = holder.takeIf(bindings::isBindingInstance) ?: bindings.bindingFromHolder(holder) ?: return@installHook
            radio.bind(entity, binding)
        })
    }.onFailure { ProviderLogger.error("Apple Music 主页电台绑定 Hook 安装失败", it) }

    private fun installSearchResults() = runCatching {
        val resolver = runtime.hookResolver
        rows.initialize()
        val entityType = resolver.resolveClass(AppleMusicHookPoint.RECENTLY_SEARCHED_MEDIA_ENTITY).clazz
        val modelHolderMethod = resolver.resolveMethod(AppleMusicHookPoint.EPOXY_FINAL_BIND).target
            .runtimeMemberName(AppleMusicRuntimeMember.EPOXY_FINAL_HOLDER_MODEL_HOLDER_METHOD)
        listOf(AppleMusicHookPoint.SEARCH_RESULTS_MODEL_BOUND, AppleMusicHookPoint.RECENTLY_SEARCHED_MODEL_BOUND).forEach { point ->
            runtime.hookRegistrar.installHook(resolver.resolveMethod(point).method, after = { chain, _ ->
                val holder = chain.args.firstOrNull() ?: return@installHook
                rows.forget(holder)
                val model = chain.args.getOrNull(1) ?: return@installHook
                val entity = collectionPageRowEntity(model, entityType) ?: return@installHook
                val nativeHolder = runCatching { AppleReflection.call(holder, modelHolderMethod) }.getOrNull()
                val binding = bindings.bindingFromHolder(nativeHolder) ?: bindings.bindingFromHolder(holder)
                if (binding != null) radio.bind(entity, binding)
                val stationKey = if (binding == null) radio.observe(entity) else null
                val id = register(entity)
                if (binding == null && (id != null || stationKey != null)) {
                    val key = id ?: requireNotNull(stationKey)
                    rows.bind(holder, chain.thisObject ?: return@installHook, model, chain.args[2] as Int,
                        key, if (id != null) host.effectiveAlias(id) else radio.display(key))
                }
                if (id == null) return@installHook
                if (binding != null) {
                    bindings.capture(binding)
                    bindings.register(id, binding, InAppOriginalResolutionMode.ORIGINAL_FIRST)
                    host.effectiveAlias(id)?.let { bindings.refreshDataBindings(id, it) }
                }
                request(id, binding ?: holder)
                if (BuildConfig.DEBUG) host.logMetadataIdentity("search_result_bound", "contentId=$id, point=$point, binding=${binding?.javaClass?.name}")
            })
        }
    }.onFailure { ProviderLogger.error("Apple Music 搜索结果精确绑定 Hook 安装失败", it) }

    fun refresh(id: String, alias: Alias): Int {
        val changed = scopes.changed(id, alias)
        queueInvalidation(changed)
        rows.changed(id, alias)
        radio.artistChanged(id)
        return changed.size
    }

    private fun queueInvalidation(targets: List<Any>) {
        invalidations.addAll(targets)
        if (invalidations.isEmpty() || invalidationPosted) return
        invalidationPosted = true
        runtime.mainHandler.post {
            invalidationPosted = false
            val batch = invalidations.toList()
            invalidations.clear()
            batch.forEach { scope ->
                runCatching { invalidate?.invoke(scope) }.onFailure { ProviderLogger.error("Apple Music 浏览卡片名称刷新失败", it) }
            }
            if (BuildConfig.DEBUG) host.logMetadataIdentity("browse_recompose", "scopes=${batch.size}")
        }
    }

    fun clear() {
        pending.clear()
        registered.clear()
        radio.clear()
        rows.clear()
        queueInvalidation(scopes.clear())
    }
}
