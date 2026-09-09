/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

internal class AppleListenNowHooks(
    internal val runtime: AppleMusicProviderRuntime,
    private val metadataStore: AppleMetadataOverrideStore,
    private val catalogResolver: AppleInternalCatalogResolver,
    internal val host: AppleListenNowHost,
) {
    private companion object {
        const val MAX_LISTEN_NOW_ARTWORK_CONTINUITY_ENTRIES = 1_024
        const val LISTEN_NOW_ARTWORK_CONTINUITY_TTL_MS = 10 * 60 * 1_000L
    }

    private val inAppListenNowArtworkContinuityCache =
        Collections.synchronizedMap(
            object : LinkedHashMap<
                InAppListenNowArtworkContinuityKey,
                InAppArtworkContinuityEntry,
                >(MAX_LISTEN_NOW_ARTWORK_CONTINUITY_ENTRIES, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<
                        InAppListenNowArtworkContinuityKey,
                        InAppArtworkContinuityEntry,
                        >?,
                ): Boolean = size > MAX_LISTEN_NOW_ARTWORK_CONTINUITY_ENTRIES
            }
        )
    internal val inAppListenNowArtworkKeysByLiveData =
        WeakIdentityMap<Any, InAppListenNowArtworkContinuityKey>()
    private val inAppListenNowSeededArtwork =
        WeakIdentityMap<Any, InAppListenNowSeededArtwork>()
    @Volatile
    internal var inAppListenNowArtworkContinuityHookInstalled = false
    internal val inAppListenNowDataBindingRefs =
        java.util.concurrent.ConcurrentHashMap<
            String,
            ConcurrentLinkedQueue<WeakReference<Any>>,
            >()
    internal val inAppListenNowDataBindingMediaIds =
        Collections.synchronizedMap(WeakHashMap<Any, String>())
    internal val inAppListenNowDataBindingPendingRefreshes =
        Collections.synchronizedMap(WeakHashMap<Any, PendingDataBindingRefresh>())
    internal val inAppListenNowModelBuildStates =
        WeakIdentityMap<Any, InAppListenNowModelBuildState>()
    internal val inAppListenNowModelBuildStatesByLiveData =
        WeakIdentityMap<Any, InAppListenNowModelBuildState>()
    internal val debugListenNowArtworkLiveData =
        WeakIdentityMap<Any, DebugListenNowArtworkTrace>()
    internal val debugListenNowArtworkDelegates =
        WeakIdentityMap<Any, DebugListenNowArtworkTrace>()
    internal val debugListenNowArtworkImageViews =
        WeakIdentityMap<Any, DebugListenNowArtworkTrace>()
    internal val debugListenNowLatestArtworkTraces =
        ConcurrentHashMap<String, DebugListenNowArtworkTrace>()
    private val collectionItemRuntimeTarget by lazy {
        runtime.hookResolver.resolveClass(
            AppleMusicHookPoint.LISTEN_NOW_COLLECTION_ITEM_VIEW
        ).target
    }
    private val libraryEntityRuntimeClasses by lazy {
        runtime.hookResolver.resolveClasses(AppleMusicHookPoint.LIBRARY_ENTITY_CLASSES)
    }

    fun installArtworkContinuityHooks() {
        runCatching {
            val resolvedBuilder = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.LISTEN_NOW_MODEL_BUILDER
            )
            val resolvedArtworkSubmit = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER
            )
            val modelClass = runtime.hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_MODEL
            ).clazz
            val mediaEntityClass = runtime.hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_MEDIA_ENTITY
            ).clazz
            val liveDataClass = runtime.classLoader.loadClass("androidx.lifecycle.MutableLiveData")
            val delegateClass = runtime.hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_DELEGATING_ITEM
            ).clazz
            val builderMethod = resolvedBuilder.method
            val resolverSubmitMethod = resolvedArtworkSubmit.method
            val modelLiveDataField = generateSequence(modelClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .single { field -> liveDataClass.isAssignableFrom(field.type) }
                .apply { isAccessible = true }
            val delegateLiveDataField = generateSequence(delegateClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .single { field -> liveDataClass.isAssignableFrom(field.type) }
                .apply { isAccessible = true }
            val liveDataGetValue = AppleReflection.findMethod(liveDataClass, "getValue", 0)
            val liveDataSetValue = AppleReflection.findMethod(liveDataClass, "setValue", 1)

            runtime.hookRegistrar.installHook(
                builderMethod,
                before = { chain ->
                    chain.args.getOrNull(3)
                        ?.takeIf(mediaEntityClass::isInstance)
                        ?.let(::primeInAppListenNowMetadata)
                },
                after = { chain, result ->
                    val model = result?.takeIf(modelClass::isInstance) ?: return@installHook
                    val entity = chain.args.getOrNull(3)
                        ?.takeIf(mediaEntityClass::isInstance)
                        ?: return@installHook
                    val identity = inAppListenNowArtworkIdentity(entity)
                    val liveData = runCatching { modelLiveDataField.get(model) }.getOrNull()
                        ?: return@installHook
                    recordInAppListenNowModelBuildState(
                        model = model,
                        entity = entity,
                        liveData = liveData,
                        builderKey = identity.key,
                    )
                    val currentUrls = normalizedInAppArtworkValueUrls(
                        runCatching { liveDataGetValue.invoke(liveData) }.getOrNull()
                    )
                    if (BuildConfig.DEBUG) {
                        host.logMetadataIdentity(
                            event = "listen_now_artwork_builder_identity",
                            details = "moduleVersion=${BuildConfig.VERSION_CODE}, " +
                                "liveData=${objectIdentity(liveData)}, " +
                                "currentUrlHash=${currentUrls.hashCode()}, " +
                                debugInAppListenNowArtworkIdentity(identity),
                        )
                    }
                    val key = identity.key ?: return@installHook
                    inAppListenNowArtworkKeysByLiveData[liveData] = key
                    if (currentUrls.isNotEmpty()) {
                        putInAppListenNowArtworkContinuity(key, currentUrls)
                        if (BuildConfig.DEBUG) {
                            host.logMetadataIdentity(
                                event = "listen_now_artwork_builder_cache_store",
                                details = "contentId=${key.id}, persistentId=${key.persistentId}, " +
                                    "contentType=${key.contentType}, artworkHash=" +
                                    "${key.artworkIdentity.hashCode()}, urls=${currentUrls.size}, " +
                                    "urlHash=${currentUrls.hashCode()}",
                            )
                        }
                        return@installHook
                    }
                    val cacheProbe = synchronized(inAppListenNowArtworkContinuityCache) {
                        InAppListenNowArtworkCacheProbe(
                            exact = inAppListenNowArtworkContinuityCache[key],
                            cacheSize = inAppListenNowArtworkContinuityCache.size,
                            sameBaseArtworkHashes = inAppListenNowArtworkContinuityCache.keys
                                .asSequence()
                                .filter { candidate ->
                                    candidate.id == key.id &&
                                        candidate.persistentId == key.persistentId &&
                                        candidate.contentType == key.contentType
                                }
                                .map { candidate -> candidate.artworkIdentity.hashCode() }
                                .distinct()
                                .toList(),
                        )
                    }
                    if (BuildConfig.DEBUG) {
                        host.logMetadataIdentity(
                            event = "listen_now_artwork_cache_lookup",
                            details = "contentId=${key.id}, persistentId=${key.persistentId}, " +
                                "contentType=${key.contentType}, artworkHash=" +
                                "${key.artworkIdentity.hashCode()}, exactHit=" +
                                "${cacheProbe.exact != null}, cacheSize=${cacheProbe.cacheSize}, " +
                                "sameBaseArtworkHashes=${cacheProbe.sameBaseArtworkHashes}",
                        )
                    }
                    val restoredUrls = selectInAppArtworkContinuityUrls(
                        currentUrls = currentUrls,
                        cachedUrls = cacheProbe.exact?.urls,
                        cachedAtUptimeMillis = cacheProbe.exact?.capturedAtUptimeMillis,
                        nowUptimeMillis = SystemClock.uptimeMillis(),
                        ttlMillis = LISTEN_NOW_ARTWORK_CONTINUITY_TTL_MS,
                    ) ?: run {
                        if (cacheProbe.exact != null) {
                            synchronized(inAppListenNowArtworkContinuityCache) {
                                inAppListenNowArtworkContinuityCache.remove(key)
                            }
                        }
                        return@installHook
                    }
                    liveDataSetValue.invoke(liveData, restoredUrls.toTypedArray())
                    inAppListenNowSeededArtwork[liveData] = InAppListenNowSeededArtwork(
                        key = key,
                        urls = restoredUrls,
                    )
                    if (BuildConfig.DEBUG) {
                        host.logMetadataIdentity(
                            event = "listen_now_artwork_continuity_seeded",
                            details = "contentId=${key.id}, persistentId=${key.persistentId}, " +
                                "contentType=${key.contentType}, artworkHash=" +
                                "${key.artworkIdentity.hashCode()}, urls=${restoredUrls.size}, " +
                                "urlHash=${restoredUrls.hashCode()}",
                        )
                    }
                },
            )

            runtime.hookRegistrar.installConditionalVoidSkipHook(resolverSubmitMethod) { chain ->
                val delegate = chain.args.firstOrNull()
                    ?.takeIf(delegateClass::isInstance)
                    ?: return@installConditionalVoidSkipHook false
                val liveData = runCatching { delegateLiveDataField.get(delegate) }.getOrNull()
                    ?: return@installConditionalVoidSkipHook false
                resolveInAppListenNowCatalogIdentity(
                    liveData = liveData,
                    delegateKey = inAppListenNowArtworkContinuityKey(delegate),
                )
                val seeded = inAppListenNowSeededArtwork[liveData]
                    ?: return@installConditionalVoidSkipHook false
                val effectiveKey = preferredInAppListenNowArtworkKey(
                    builderKey = inAppListenNowArtworkKeysByLiveData[liveData],
                    delegateKey = inAppListenNowArtworkContinuityKey(delegate),
                )
                val currentUrls = normalizedInAppArtworkValueUrls(
                    runCatching { liveDataGetValue.invoke(liveData) }.getOrNull()
                )
                val skip = shouldSkipInAppListenNowArtworkLookup(
                    keyMatches = effectiveKey == seeded.key,
                    currentUrls = currentUrls,
                    seededUrls = seeded.urls,
                )
                if (skip && BuildConfig.DEBUG) {
                    host.logMetadataIdentity(
                        event = "listen_now_artwork_lookup_skipped",
                        details = "contentId=${seeded.key.id}, " +
                            "persistentId=${seeded.key.persistentId}, " +
                            "contentType=${seeded.key.contentType}, " +
                            "urlHash=${seeded.urls.hashCode()}",
                    )
                }
                skip
            }
            inAppListenNowArtworkContinuityHookInstalled = true
            ProviderLogger.info(
                "Apple Music 主页 Listen Now 封面连续性 Hook 已安装: " +
                    "builder=${builderMethod.name}/${builderMethod.parameterCount}, " +
                    "resolver=${resolverSubmitMethod.name}/${resolverSubmitMethod.parameterCount}, " +
                    "fallback=${resolvedBuilder.compatibilityFallback ||
                        resolvedArtworkSubmit.compatibilityFallback}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 主页 Listen Now 封面连续性 Hook 安装失败", it)
        }
    }

    /**
     * Listen Now 的原 builder 会把 MediaEntity 当前文本复制到 Epoxy model。
     * 因此必须在原方法运行前登记类型并消费已预热缓存，且不改动封面 LiveData。
     */
    private fun primeInAppListenNowMetadata(
        entity: Any,
        resolvedCatalogId: String? = null,
    ) {
        val kind = inAppLibraryEntityKindForProfileClasses(
            entity = entity,
            resolvedClasses = libraryEntityRuntimeClasses,
        ) ?: return
        val attributes = host.mediaApiEntityAttributes(entity) ?: return
        val mediaId = resolvedCatalogId
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: host.mediaApiEntityCatalogId(entity, attributes)
            ?: return
        host.registerLibraryEntity(
            mediaId = mediaId,
            entity = entity,
            kind = kind,
            knownAttributes = attributes,
            requestResolution = false,
            retainEntityRef = true,
        )
        host.enrichLibraryEntity(mediaId, entity, kind, attributes)

        val entityType = localizedEntityTypeForInAppLibraryKind(kind)
        val localizedCacheHit = metadataStore.hasConfiguredMetadata(mediaId)
        val originalCacheProbeDue = host.isRestoreOriginalMetadataEnabled() &&
            !metadataStore.hasOriginalMetadata(mediaId) &&
            host.shouldRetryOriginalMetadataCacheProbe(mediaId)
        val originalCacheHit = if (originalCacheProbeDue) {
            catalogResolver.cachedOriginalEntity(
                mediaId = mediaId,
                entityType = entityType,
                lookupIds = metadataStore.lookupIds(mediaId),
            )?.also { alias ->
                metadataStore.clearOriginalPending(mediaId)
                host.rememberOriginalMetadataOverride(
                    mediaId = mediaId,
                    alias = alias,
                    confirmed = true,
                )
                alias.language.takeIf(String::isNotBlank)?.let { language ->
                    host.rememberOriginalLanguageForArtist(mediaId, language)
                }
            }
        } else {
            null
        }
        if (originalCacheProbeDue && originalCacheHit == null) {
            // A populated SQLite cache may not be in the bounded in-memory warm set. Probe it
            // before the localized request so a late original result cannot be stranded behind a
            // slow or deduplicated localized callback.
            host.resolveCachedOriginalEntityForInApp(
                mediaId = mediaId,
                entityType = entityType,
                preBind = true,
                priority = RequestPriority.VISIBLE,
            )
        }
        val alias = host.effectiveAlias(mediaId)
        val originalApplied = originalCacheHit?.let {
            host.applyAliasToLibraryEntity(entity, kind, it)
        } == true
        val cacheMiss = host.shouldRequestOverride(mediaId)
        if (cacheMiss) {
            // 主页先覆盖设定地区，原地区结果随后按优先级补回，避免空缓存阻塞首屏。
            host.markMetadataVisible(listOf(mediaId))
            host.scheduleMetadataResolution(
                mediaIds = listOf(mediaId),
                priority = RequestPriority.VISIBLE,
                originalResolutionMode = InAppOriginalResolutionMode.AFTER_LOCALIZED,
            )
            if (BuildConfig.DEBUG) {
                ProviderLogger.info(
                    "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                        "event=listen_now_metadata_resolution_dispatched, " +
                        "contentId=$mediaId, kind=$kind, priority=VISIBLE, " +
                        "originalResolutionMode=AFTER_LOCALIZED"
                )
            }
        }
        if (BuildConfig.DEBUG) {
            host.logMetadataIdentity(
                event = "listen_now_metadata_primed",
                details = "contentId=$mediaId, kind=$kind, entityType=$entityType, " +
                    "localizedCacheHit=$localizedCacheHit, " +
                    "originalCacheHit=${originalCacheHit != null}, " +
                    "originalCacheProbeDue=$originalCacheProbeDue, " +
                    "originalApplied=$originalApplied, " +
                    "cacheMiss=$cacheMiss, request=$cacheMiss, " +
                    "effective=${alias?.title}/${alias?.artist}/${alias?.album}",
            )
        }
    }

    private fun recordInAppListenNowModelBuildState(
        model: Any,
        entity: Any,
        liveData: Any,
        builderKey: InAppListenNowArtworkContinuityKey?,
    ) {
        val directMediaId = host.mediaApiEntityCatalogId(entity)
        val state = InAppListenNowModelBuildState(
            entity = WeakReference(entity),
            liveData = WeakReference(liveData),
            builderKey = builderKey,
            initialCatalogId = directMediaId,
            builtAlias = directMediaId?.let { mediaId ->
                host.effectiveAlias(mediaId)?.let { alias ->
                    AppliedMetadataAlias(mediaId, alias)
                }
            },
        )
        inAppListenNowModelBuildStates[model] = state
        inAppListenNowModelBuildStatesByLiveData[liveData] = state
    }

    /**
     * Listen Now's standard card copies MediaEntity text into its Epoxy model before binding.
     * The model has no observable metadata source, so a later catalog result must update the
     * already-bound DataBinding directly. This hook is kept on the profiled bound-listener
     * callback so recycled cards can be re-associated with their current MediaEntity.
     */
    fun installMetadataBindingHooks() {
        runCatching {
            val resolvedOnModelBound = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.LISTEN_NOW_BOUND_LISTENER
            )
            val modelClass = runtime.hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_MODEL
            ).clazz
            val mediaEntityClass = runtime.hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_MEDIA_ENTITY
            ).clazz
            val onModelBoundMethod = resolvedOnModelBound.method
            runtime.hookRegistrar.installHook(onModelBoundMethod, before = { chain ->
                listenNowDataBindingArgument(chain.args.getOrNull(1))?.let { binding ->
                    beginInAppListenNowDataBindingBind(binding)
                }
            }, after = { chain, _ ->
                val model = chain.args.firstOrNull()
                    ?.takeIf(modelClass::isInstance)
                    ?: return@installHook
                val listener = chain.thisObject ?: return@installHook
                val entity = fieldValueByType(listener, mediaEntityClass)
                    ?: return@installHook
                val binding = listenNowDataBindingArgument(chain.args.getOrNull(1))
                    ?: return@installHook
                val buildState = inAppListenNowModelBuildStates[model]
                buildState?.boundBinding = InAppListenNowBoundBinding(
                    binding = WeakReference(binding),
                    bindGeneration = host.dataBindingGeneration(binding),
                )
                val mediaId = host.mediaApiEntityCatalogId(entity)
                    ?: buildState?.catalogId
                    ?: return@installHook
                host.captureDataBinding(binding)
                host.registerDataBinding(mediaId, binding)
                registerInAppListenNowDataBinding(mediaId, binding)
                val alias = host.effectiveAlias(mediaId) ?: return@installHook
                val appliedAlias = AppliedMetadataAlias(mediaId, alias)
                if (
                    buildState?.catalogId == mediaId &&
                    buildState.builtAlias == appliedAlias
                ) {
                    val values = host.aliasValues(mediaId, alias, binding)
                    val renderedTexts = host.renderedTexts(binding)
                    if (
                        renderedTexts.isEmpty() ||
                        dataBindingAliasAlreadyRendered(
                            expectedTitle = values.title,
                            expectedSubtitle = values.subtitle,
                            renderedTexts = renderedTexts,
                        )
                    ) {
                        // The builder wrote the alias before model creation. Keep the fast path
                        // only while the bound views do not prove that Apple restored old text.
                        host.rememberAppliedAlias(binding, appliedAlias)
                        return@installHook
                    }
                }
                refreshDataBindings(mediaId, alias)
                if (BuildConfig.DEBUG) {
                    host.logMetadataIdentity(
                        event = "listen_now_metadata_binding_refresh",
                        details = "contentId=$mediaId, model=${model.javaClass.name}, " +
                            "binding=${binding.javaClass.name}@" +
                            "${System.identityHashCode(binding)}, " +
                            "buildAlias=${buildState?.builtAlias?.title}/" +
                            "${buildState?.builtAlias?.artist}, " +
                            "effective=${alias.title}/${alias.artist}/${alias.album}",
                    )
                }
            })
            ProviderLogger.info(
                "Apple Music 主页 Listen Now 文字绑定 Hook 已安装: " +
                    "bound=${onModelBoundMethod.name}/${onModelBoundMethod.parameterCount}, " +
                    "fallback=${resolvedOnModelBound.compatibilityFallback}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 主页 Listen Now 文字绑定 Hook 安装失败", it)
        }
    }

    private fun listenNowDataBindingArgument(argument: Any?): Any? =
        argument
            ?.takeIf { candidate ->
                host.isDataBindingInstance(candidate)
            }
            // Older profiles may still pass an Epoxy holder instead of ViewDataBinding.
            ?: host.dataBindingFromHolder(argument)

    private fun beginInAppListenNowDataBindingBind(binding: Any) {
        host.beginDataBindingModelBind(binding)
        synchronized(inAppListenNowDataBindingPendingRefreshes) {
            inAppListenNowDataBindingPendingRefreshes.remove(binding)
        }
        host.clearDataBindingMediaId(binding)
        inAppListenNowDataBindingMediaIds.remove(binding)
    }

    private fun registerInAppListenNowDataBinding(
        mediaId: String,
        binding: Any,
    ) {
        inAppListenNowDataBindingMediaIds[binding] = mediaId
        val refs = inAppListenNowDataBindingRefs.computeIfAbsent(mediaId) {
            ConcurrentLinkedQueue()
        }
        var registered = false
        refs.forEach { ref ->
            val target = ref.get()
            if (target == null) {
                refs.remove(ref)
            } else if (target === binding) {
                registered = true
            }
        }
        if (!registered) refs.add(WeakReference(binding))
    }

    internal fun resolveInAppListenNowCatalogIdentity(
        liveData: Any,
        delegateKey: InAppListenNowArtworkContinuityKey?,
    ) {
        val state = inAppListenNowModelBuildStatesByLiveData[liveData] ?: return
        val mediaId = listenNowCatalogIdForExactCard(
            builderLiveData = state.liveData.get(),
            delegateLiveData = liveData,
            builderKey = state.builderKey,
            delegateKey = delegateKey,
        ) ?: return
        if (!state.assignCatalogId(mediaId)) return
        if (BuildConfig.DEBUG) {
            host.logMetadataIdentity(
                event = "listen_now_catalog_identity_mapped",
                details = "localId=${state.builderKey?.id}, contentId=$mediaId, " +
                    "liveData=${objectIdentity(liveData)}, " +
                    "persistentId=${state.builderKey?.persistentId}, " +
                    "contentType=${state.builderKey?.contentType}",
            )
        }
        runtime.mainHandler.post {
            if (inAppListenNowModelBuildStatesByLiveData[liveData] !== state ||
                state.catalogId != mediaId
            ) return@post
            state.entity.get()?.let { entity ->
                primeInAppListenNowMetadata(entity, resolvedCatalogId = mediaId)
            }
            registerResolvedInAppListenNowBinding(state, mediaId)
        }
    }

    private fun registerResolvedInAppListenNowBinding(
        state: InAppListenNowModelBuildState,
        mediaId: String,
    ) {
        val bound = state.boundBinding ?: return
        val binding = bound.binding.get() ?: return
        if (host.dataBindingGeneration(binding) != bound.bindGeneration) return
        host.captureDataBinding(binding)
        host.registerDataBinding(mediaId, binding)
        registerInAppListenNowDataBinding(mediaId, binding)
        host.effectiveAlias(mediaId)?.let { alias ->
            refreshDataBindings(mediaId, alias)
        }
    }

    fun refreshDataBindings(
        mediaId: String,
        alias: Alias,
    ): Int {
        val refs = inAppListenNowDataBindingRefs[mediaId] ?: return 0
        val appliedAlias = AppliedMetadataAlias(mediaId, alias)
        var scheduledTargets = 0
        refs.forEach { ref ->
            val binding = ref.get()
            if (binding == null) {
                refs.remove(ref)
                return@forEach
            }
            if (inAppListenNowDataBindingMediaIds[binding] != mediaId) {
                refs.remove(ref)
                return@forEach
            }
            val previousAppliedAlias = host.appliedAlias(binding)
            if (previousAppliedAlias == appliedAlias) {
                val values = host.aliasValues(mediaId, alias, binding)
                val renderedTexts = host.renderedTexts(binding)
                if (!shouldRefreshListenNowDataBindingAlias(
                        appliedAlias = previousAppliedAlias,
                        requestedAlias = appliedAlias,
                        expectedTitle = values.title,
                        expectedSubtitle = values.subtitle,
                        renderedTexts = renderedTexts,
                    )
                ) {
                    return@forEach
                }
                if (BuildConfig.DEBUG) {
                    host.logMetadataIdentity(
                        event = "listen_now_binding_text_stale",
                        details = "contentId=$mediaId, binding=${binding.javaClass.name}@" +
                            "${System.identityHashCode(binding)}, " +
                            "expected=${values.title}/${values.subtitle}, rendered=$renderedTexts",
                    )
                }
            }
            val bindGeneration = host.dataBindingGeneration(binding)
            val pending = PendingDataBindingRefresh(
                mediaId = mediaId,
                alias = appliedAlias,
                bindGeneration = bindGeneration,
            )
            val shouldPost = synchronized(inAppListenNowDataBindingPendingRefreshes) {
                val current = inAppListenNowDataBindingPendingRefreshes[binding]
                if (current == pending) {
                    false
                } else {
                    inAppListenNowDataBindingPendingRefreshes[binding] = pending
                    true
                }
            }
            if (!shouldPost) return@forEach
            scheduledTargets += 1
            runtime.mainHandler.post {
                if (inAppListenNowDataBindingPendingRefreshes[binding] != pending) {
                    return@post
                }
                fun clearPending() {
                    synchronized(inAppListenNowDataBindingPendingRefreshes) {
                        if (inAppListenNowDataBindingPendingRefreshes[binding] == pending) {
                            inAppListenNowDataBindingPendingRefreshes.remove(binding)
                        }
                    }
                }
                if (!isDataBindingRefreshCurrent(
                        currentMediaId = inAppListenNowDataBindingMediaIds[binding],
                        requestedMediaId = mediaId,
                        currentBindGeneration = host.dataBindingGeneration(binding),
                        scheduledBindGeneration = bindGeneration,
                    )
                ) {
                    clearPending()
                    return@post
                }
                runCatching {
                    val values = host.aliasValues(mediaId, alias, binding)
                    val variableResults = host.applyAliasVariables(binding, values)
                    if (
                        dataBindingRefreshStrategy(
                            expectedTitle = values.title,
                            expectedSubtitle = values.subtitle,
                            titleApplied = variableResults.titleApplied,
                            subtitleApplied = variableResults.subtitleApplied,
                        ) == DataBindingRefreshStrategy.FULL_INVALIDATE
                    ) {
                        host.invalidateDataBinding(binding)
                    }
                    host.executePendingDataBindings(binding)
                    host.rememberAppliedAlias(binding, appliedAlias)
                }.onFailure {
                    ProviderLogger.error(
                        "Apple Music 主页 Listen Now 文字绑定刷新失败: " +
                            "id=$mediaId, binding=${binding.javaClass.name}",
                        it,
                    )
                }
                clearPending()
            }
        }
        return scheduledTargets
    }

    private fun inAppListenNowArtworkContinuityKey(
        item: Any,
    ): InAppListenNowArtworkContinuityKey? = inAppListenNowArtworkIdentity(item).key

    fun inAppListenNowArtworkIdentity(
        item: Any,
    ): InAppListenNowArtworkIdentity {
        val target = collectionItemRuntimeTarget
        val id = runCatching {
            AppleReflection.call(
                item,
                target.runtimeMemberName(AppleMusicRuntimeMember.COLLECTION_ITEM_GET_ID_METHOD),
            )?.toString()
        }
            .getOrNull()
            ?.trim()
            .orEmpty()
        val persistentId = runCatching {
            (AppleReflection.call(
                item,
                target.runtimeMemberName(
                    AppleMusicRuntimeMember.COLLECTION_ITEM_GET_PERSISTENT_ID_METHOD
                ),
            ) as? Number)?.toLong()
        }.getOrNull() ?: 0L
        val contentType = runCatching {
            (AppleReflection.call(
                item,
                target.runtimeMemberName(
                    AppleMusicRuntimeMember.COLLECTION_ITEM_GET_CONTENT_TYPE_METHOD
                ),
            ) as? Number)?.toInt()
        }.getOrNull() ?: -1
        val artworkTokenEntries = runCatching {
            @Suppress("UNCHECKED_CAST")
            (AppleReflection.call(
                item,
                target.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTWORK_GET_ALL_ARTWORK_TOKENS_METHOD
                ),
            ) as? Map<Any?, Any?>)
                .orEmpty()
                .entries
                .mapNotNull { (variant, token) ->
                    val normalizedToken = token?.toString()?.trim().orEmpty()
                    if (normalizedToken.isEmpty()) null else "$variant=$normalizedToken"
                }
                .sorted()
        }.getOrDefault(emptyList())
        val artworkTokens = artworkTokenEntries.joinToString("|")
        val fetchableArtworkToken = runCatching {
            AppleReflection.call(
                item,
                target.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTWORK_GET_FETCHABLE_ARTWORK_TOKEN_METHOD
                ),
            )?.toString()
        }.getOrNull()?.trim().orEmpty()
        val artworkToken = runCatching {
            AppleReflection.call(
                item,
                target.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTWORK_GET_ARTWORK_TOKEN_METHOD
                ),
            )?.toString()
        }.getOrNull()?.trim().orEmpty()
        val singularArtworkToken = fetchableArtworkToken.ifEmpty { artworkToken }
        val artworkIdentity = artworkTokens.ifEmpty { singularArtworkToken }
        val key = if (id.isEmpty() || persistentId == 0L || artworkIdentity.isEmpty()) {
            null
        } else {
            InAppListenNowArtworkContinuityKey(
                id = id,
                persistentId = persistentId,
                contentType = contentType,
                artworkIdentity = artworkIdentity,
            )
        }
        return InAppListenNowArtworkIdentity(
            id = id,
            persistentId = persistentId,
            contentType = contentType,
            allArtworkTokenCount = artworkTokenEntries.size,
            allArtworkIdentity = artworkTokens,
            fetchableArtworkToken = fetchableArtworkToken,
            artworkToken = artworkToken,
            selectedArtworkIdentity = artworkIdentity,
            key = key,
        )
    }

    fun debugInAppListenNowArtworkIdentity(
        identity: InAppListenNowArtworkIdentity,
    ): String =
        "contentId=${identity.id.ifEmpty { "none" }}, " +
            "persistentId=${identity.persistentId}, contentType=${identity.contentType}, " +
            "allTokenCount=${identity.allArtworkTokenCount}, " +
            "allTokenHash=${identity.allArtworkIdentity.takeIf(String::isNotEmpty)?.hashCode()}, " +
            "fetchableTokenHash=" +
            "${identity.fetchableArtworkToken.takeIf(String::isNotEmpty)?.hashCode()}, " +
            "artworkTokenHash=${identity.artworkToken.takeIf(String::isNotEmpty)?.hashCode()}, " +
            "selectedArtworkHash=" +
            "${identity.selectedArtworkIdentity.takeIf(String::isNotEmpty)?.hashCode()}, " +
            "keyValid=${identity.key != null}"

    internal fun putInAppListenNowArtworkContinuity(
        key: InAppListenNowArtworkContinuityKey,
        urls: Collection<String>,
    ) {
        val normalizedUrls = urls.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalizedUrls.isEmpty()) return
        synchronized(inAppListenNowArtworkContinuityCache) {
            inAppListenNowArtworkContinuityCache[key] = InAppArtworkContinuityEntry(
                urls = normalizedUrls,
                capturedAtUptimeMillis = SystemClock.uptimeMillis(),
            )
        }
    }

}
