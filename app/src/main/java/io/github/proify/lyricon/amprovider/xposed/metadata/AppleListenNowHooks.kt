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

internal interface AppleListenNowHost {
    fun mediaApiEntityAttributes(entity: Any): Any?

    fun mediaApiEntityCatalogId(entity: Any, knownAttributes: Any? = null): String?

    fun registerLibraryEntity(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        knownAttributes: Any?,
        requestResolution: Boolean,
        retainEntityRef: Boolean,
    )

    fun enrichLibraryEntity(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        attributes: Any,
    )

    fun isRestoreOriginalMetadataEnabled(): Boolean

    fun shouldRetryOriginalMetadataCacheProbe(mediaId: String): Boolean

    fun rememberOriginalMetadataOverride(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        confirmed: Boolean,
    )

    fun rememberOriginalLanguageForArtist(mediaId: String, language: String)

    fun resolveCachedOriginalEntityForInApp(
        mediaId: String,
        entityType: AppleInternalCatalogResolver.LocalizedEntityType,
        preBind: Boolean,
        priority: AppleInternalCatalogResolver.RequestPriority,
    )

    fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias?

    fun applyAliasToLibraryEntity(
        entity: Any,
        kind: InAppLibraryEntityKind,
        alias: AppleInternalCatalogResolver.Alias,
    ): Boolean

    fun shouldRequestOverride(mediaId: String): Boolean

    fun markMetadataVisible(mediaIds: Collection<String>)

    fun scheduleMetadataResolution(
        mediaIds: Collection<String>,
        priority: AppleInternalCatalogResolver.RequestPriority,
        originalResolutionMode: InAppOriginalResolutionMode,
    )

    fun nextMetadataTraceSequence(): Long

    fun logMetadataIdentity(event: String, details: String)

    fun isDataBindingInstance(candidate: Any): Boolean

    fun dataBindingFromHolder(argument: Any?): Any?

    fun beginDataBindingModelBind(binding: Any)

    fun clearDataBindingMediaId(binding: Any)

    fun dataBindingGeneration(binding: Any): Long

    fun captureDataBinding(binding: Any)

    fun registerDataBinding(mediaId: String, binding: Any)

    fun aliasValues(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        binding: Any?,
    ): DataBindingAliasValues

    fun renderedTexts(binding: Any): List<String>

    fun appliedAlias(binding: Any): AppliedMetadataAlias?

    fun rememberAppliedAlias(binding: Any, alias: AppliedMetadataAlias)

    fun applyAliasVariables(
        binding: Any,
        values: DataBindingAliasValues,
    ): DataBindingVariableApplyResult

    fun invalidateDataBinding(binding: Any)

    fun executePendingDataBindings(binding: Any)
}

internal fun shouldRefreshListenNowDataBindingAlias(
    appliedAlias: AppliedMetadataAlias?,
    requestedAlias: AppliedMetadataAlias,
    expectedTitle: String?,
    expectedSubtitle: String?,
    renderedTexts: Collection<String>,
): Boolean {
    if (appliedAlias != requestedAlias) return true
    if (renderedTexts.isEmpty()) return false
    return !dataBindingAliasAlreadyRendered(
        expectedTitle = expectedTitle,
        expectedSubtitle = expectedSubtitle,
        renderedTexts = renderedTexts,
    )
}

internal fun normalizedInAppArtworkValueUrls(value: Any?): List<String> {
    val values: Sequence<Any?> = when (value) {
        null -> emptySequence()
        is CharSequence -> sequenceOf(value)
        is Array<*> -> value.asSequence()
        is Iterable<*> -> value.asSequence()
        else -> emptySequence()
    }
    return values.mapNotNull { item ->
        item?.toString()?.trim()?.takeIf(String::isNotEmpty)
    }.distinct().toList()
}

internal fun preferredInAppListenNowArtworkKey(
    builderKey: InAppListenNowArtworkContinuityKey?,
    delegateKey: InAppListenNowArtworkContinuityKey?,
): InAppListenNowArtworkContinuityKey? = builderKey ?: delegateKey

internal fun listenNowCatalogIdForExactCard(
    builderLiveData: Any?,
    delegateLiveData: Any?,
    builderKey: InAppListenNowArtworkContinuityKey?,
    delegateKey: InAppListenNowArtworkContinuityKey?,
): String? {
    if (builderLiveData == null || builderLiveData !== delegateLiveData) return null
    val builder = builderKey ?: return null
    val delegate = delegateKey ?: return null
    if (
        builder.persistentId != delegate.persistentId ||
        builder.contentType != delegate.contentType ||
        builder.artworkIdentity != delegate.artworkIdentity
    ) return null
    val delegateCatalogId = delegate.id.trim()
        .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?: return null
    val builderId = builder.id.trim()
    val builderCatalogId = builderId.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    if (builderCatalogId != null) {
        return delegateCatalogId.takeIf { it == builderCatalogId }
    }
    return delegateCatalogId.takeIf { builderId.startsWith("l.") }
}

internal fun shouldSkipInAppListenNowArtworkLookup(
    keyMatches: Boolean,
    currentUrls: Collection<String>,
    seededUrls: Collection<String>,
): Boolean {
    if (!keyMatches) return false
    val normalizedCurrent = currentUrls.map(String::trim).filter(String::isNotEmpty).distinct()
    val normalizedSeeded = seededUrls.map(String::trim).filter(String::isNotEmpty).distinct()
    return normalizedCurrent.isNotEmpty() && normalizedCurrent == normalizedSeeded
}

internal class AppleListenNowHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val metadataStore: AppleMetadataOverrideStore,
    private val catalogResolver: AppleInternalCatalogResolver,
    private val host: AppleListenNowHost,
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
    private val inAppListenNowArtworkKeysByLiveData =
        WeakIdentityMap<Any, InAppListenNowArtworkContinuityKey>()
    private val inAppListenNowSeededArtwork =
        WeakIdentityMap<Any, InAppListenNowSeededArtwork>()
    @Volatile
    private var inAppListenNowArtworkContinuityHookInstalled = false
    private val inAppListenNowDataBindingRefs =
        java.util.concurrent.ConcurrentHashMap<
            String,
            ConcurrentLinkedQueue<WeakReference<Any>>,
            >()
    private val inAppListenNowDataBindingMediaIds =
        Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val inAppListenNowDataBindingPendingRefreshes =
        Collections.synchronizedMap(WeakHashMap<Any, PendingDataBindingRefresh>())
    private val inAppListenNowModelBuildStates =
        WeakIdentityMap<Any, InAppListenNowModelBuildState>()
    private val inAppListenNowModelBuildStatesByLiveData =
        WeakIdentityMap<Any, InAppListenNowModelBuildState>()
    private val debugListenNowArtworkLiveData =
        WeakIdentityMap<Any, DebugListenNowArtworkTrace>()
    private val debugListenNowArtworkDelegates =
        WeakIdentityMap<Any, DebugListenNowArtworkTrace>()
    private val debugListenNowArtworkImageViews =
        WeakIdentityMap<Any, DebugListenNowArtworkTrace>()
    private val debugListenNowLatestArtworkTraces =
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
                priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
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
                priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
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

    private fun resolveInAppListenNowCatalogIdentity(
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
        alias: AppleInternalCatalogResolver.Alias,
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

    private fun putInAppListenNowArtworkContinuity(
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

    /**
     * Debug-only trace for the real Listen Now / Home artwork path.
     *
     * The profiled model builder creates one MutableLiveData<String[]> per card and seeds it
     * from the feed image URL. The profiled bound listener submits a second medialibrary artwork
     * lookup only when the entity has a persistent ID. The trace follows that exact LiveData
     * through the profiled resolver, delegate, and image view so a reproduction can distinguish
     * a duplicate URL publication from an actual clear/rebind or a replacement card View.
     */
    fun installDebugArtworkLifecycleHooks() {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val resolvedOnModelBound = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.LISTEN_NOW_BOUND_LISTENER
            )
            val resolvedArtworkSubmit = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER
            )
            val modelClass = runtime.hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_MODEL
            ).clazz
            val resolvedDelegate = runtime.hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_DELEGATING_ITEM
            )
            val delegateClass = resolvedDelegate.clazz
            val resolvedCustomImageView = runtime.hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_CUSTOM_IMAGE_VIEW
            )
            val customImageViewClass = resolvedCustomImageView.clazz
            val resolvedMediaEntity = runtime.hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_MEDIA_ENTITY
            )
            val mediaEntityClass = resolvedMediaEntity.clazz
            val liveDataClass = runtime.classLoader.loadClass("androidx.lifecycle.MutableLiveData")

            val onModelBoundMethod = resolvedOnModelBound.method
            val resolverSubmitMethod = resolvedArtworkSubmit.method
            val delegateLiveDataField = generateSequence(delegateClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .single { field -> liveDataClass.isAssignableFrom(field.type) }
                .apply { isAccessible = true }
            val delegateGetImageUrl = AppleReflection.findMethod(
                delegateClass,
                resolvedDelegate.target.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTWORK_GET_IMAGE_URL_METHOD
                ),
                0,
            )
            val delegateGetImageUrls = AppleReflection.findMethod(
                delegateClass,
                resolvedDelegate.target.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTWORK_GET_IMAGE_URLS_METHOD
                ),
                0,
            )
            val liveDataGetValue = AppleReflection.findMethod(liveDataClass, "getValue", 0)
            val liveDataMutationMethods = listOf(
                AppleReflection.findMethod(liveDataClass, "postValue", 1),
                AppleReflection.findMethod(liveDataClass, "setValue", 1),
            )
            val delegateArtworkMethods = delegateClass.declaredMethods.filter { method ->
                (method.name == resolvedDelegate.target.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTWORK_SET_IMAGE_URL_METHOD
                ) &&
                    method.parameterTypes.firstOrNull() == String::class.java) ||
                    (method.name == resolvedDelegate.target.runtimeMemberName(
                        AppleMusicRuntimeMember.ARTWORK_SET_IMAGE_URLS_METHOD
                    ) &&
                        method.parameterTypes.contentEquals(arrayOf(Array<String>::class.java)))
            }.onEach { it.isAccessible = true }
            check(delegateArtworkMethods.isNotEmpty()) {
                "Listen Now delegate artwork setters unavailable"
            }
            val customImageMutationMethods = listOf(
                customImageViewClass.getDeclaredMethod(
                    "setImageDrawable",
                    Drawable::class.java,
                ),
                customImageViewClass.getDeclaredMethod(
                    resolvedCustomImageView.target.runtimeMemberName(
                        AppleMusicRuntimeMember.CUSTOM_IMAGE_SET_BITMAP_METHOD
                    ),
                    Bitmap::class.java,
                ),
            ).onEach { it.isAccessible = true }

            runtime.hookRegistrar.installHook(
                onModelBoundMethod,
                before = { chain ->
                    val listener = chain.thisObject ?: return@installHook
                    val model = chain.args.firstOrNull()
                        ?.takeIf(modelClass::isInstance)
                        ?: return@installHook
                    val entity = fieldValueByType(listener, mediaEntityClass)
                        ?: return@installHook
                    val persistentIdValue = runCatching {
                        AppleReflection.call(
                            entity,
                            resolvedMediaEntity.target.runtimeMemberName(
                                AppleMusicRuntimeMember.COLLECTION_ITEM_GET_PERSISTENT_ID_METHOD
                            ),
                        )
                    }.getOrNull() ?: return@installHook
                    val persistentId = (persistentIdValue as? Number)?.toLong()
                        ?: return@installHook
                    val liveData = fieldValueByType(listener, liveDataClass)
                        ?: return@installHook
                    val binding = host.dataBindingFromHolder(chain.args.getOrNull(1))
                    val root = runCatching {
                        binding?.let { AppleReflection.call(it, "getRoot") as? View }
                    }.getOrNull()
                    val imageViews = debugListenNowImageViews(root)
                    val mediaId = runCatching {
                        AppleReflection.call(
                            entity,
                            resolvedMediaEntity.target.runtimeMemberName(
                                AppleMusicRuntimeMember.COLLECTION_ITEM_GET_ID_METHOD
                            ),
                        )?.toString()
                    }.getOrNull()?.trim().orEmpty()
                    val title = runCatching {
                        AppleReflection.call(
                            entity,
                            resolvedMediaEntity.target.runtimeMemberName(
                                AppleMusicRuntimeMember.COLLECTION_ITEM_GET_TITLE_METHOD
                            ),
                        )?.toString()
                    }.getOrNull()?.replace('\n', ' ')?.take(96)
                    val contentType = runCatching {
                        (AppleReflection.call(
                            entity,
                            resolvedMediaEntity.target.runtimeMemberName(
                                AppleMusicRuntimeMember.COLLECTION_ITEM_GET_CONTENT_TYPE_METHOD
                            ),
                        ) as? Number)?.toInt()
                    }.getOrNull() ?: -1
                    val mediaKey = "$mediaId:$persistentId:$contentType"
                    val trace = DebugListenNowArtworkTrace(
                        mediaKey = mediaKey,
                        mediaId = mediaId.ifEmpty { "none" },
                        title = title,
                        persistentId = persistentId,
                        contentType = contentType,
                        liveData = WeakReference(liveData),
                        model = WeakReference(model),
                        root = root?.let(::WeakReference),
                        imageViews = imageViews.map(::WeakReference),
                    )
                    val previous = debugListenNowLatestArtworkTraces.put(mediaKey, trace)
                    debugListenNowArtworkLiveData[liveData] = trace
                    imageViews.forEach { imageView ->
                        debugListenNowArtworkImageViews[imageView] = trace
                    }
                    val currentValue = runCatching {
                        liveDataGetValue.invoke(liveData)
                    }.getOrNull()
                    ProviderLogger.diagnostic(
                        "ListenNowArtwork: event=model_bound_before, " +
                            debugListenNowArtworkTraceIdentity(trace) + ", " +
                            "moduleVersion=${BuildConfig.VERSION_CODE}, " +
                            "continuityInstalled=" +
                            "${isArtworkContinuityInstalled()}, " +
                            "model=${objectIdentity(model)}, " +
                            "previousModel=${objectIdentity(previous?.model?.get())}, " +
                            "root=${objectIdentity(root)}, " +
                            "previousRoot=${objectIdentity(previous?.root?.get())}, " +
                            "liveData=${objectIdentity(liveData)}, " +
                            "value=${debugListenNowArtworkValueSummary(currentValue)}, " +
                            "images=${debugListenNowArtworkImageStates(trace)}"
                    )
                },
                after = { chain, _ ->
                    val listener = chain.thisObject ?: return@installHook
                    val liveData = fieldValueByType(listener, liveDataClass)
                        ?: return@installHook
                    val trace = debugListenNowArtworkLiveData[liveData]
                        ?: return@installHook
                    debugListenNowLogTraceSnapshot(
                        trace = trace,
                        stage = "model_bound_after",
                        liveDataGetValue = liveDataGetValue,
                    )
                    trace.root?.get()?.let { root ->
                        root.post {
                            debugListenNowLogTraceSnapshot(
                                trace = trace,
                                stage = "model_bound_next_frame",
                                liveDataGetValue = liveDataGetValue,
                            )
                        }
                        root.postDelayed(
                            {
                                debugListenNowLogTraceSnapshot(
                                    trace = trace,
                                    stage = "model_bound_250ms",
                                    liveDataGetValue = liveDataGetValue,
                                )
                            },
                            250L,
                        )
                    }
                },
            )

            runtime.hookRegistrar.installHook(
                resolverSubmitMethod,
                before = { chain ->
                    val delegate = chain.args.firstOrNull()
                        ?.takeIf(delegateClass::isInstance)
                        ?: return@installHook
                    val liveData = runCatching { delegateLiveDataField.get(delegate) }
                        .getOrNull()
                        ?: return@installHook
                    val trace = debugListenNowArtworkLiveData[liveData]
                        ?: return@installHook
                    debugListenNowArtworkDelegates[delegate] = trace
                    ProviderLogger.diagnostic(
                        "ListenNowArtwork: event=library_lookup_submit, " +
                            debugListenNowArtworkTraceIdentity(trace) + ", " +
                            "delegate=${objectIdentity(delegate)}, " +
                            "liveData=${objectIdentity(liveData)}, " +
                            "delegateValue=${debugListenNowDelegateArtworkSummary(
                                delegate,
                                delegateGetImageUrl,
                                delegateGetImageUrls,
                            )}, liveValue=${debugListenNowArtworkValueSummary(
                                runCatching { liveDataGetValue.invoke(liveData) }.getOrNull()
                            )}"
                    )
                },
                after = { chain, _ ->
                    val delegate = chain.args.firstOrNull()
                        ?.takeIf(delegateClass::isInstance)
                        ?: return@installHook
                    val trace = debugListenNowArtworkDelegates[delegate]
                        ?: return@installHook
                    ProviderLogger.diagnostic(
                        "ListenNowArtwork: event=library_lookup_submitted, " +
                            debugListenNowArtworkTraceIdentity(trace) + ", " +
                            "delegate=${objectIdentity(delegate)}"
                    )
                },
            )

            delegateArtworkMethods.forEach { method ->
                runtime.hookRegistrar.installHook(
                    method,
                    before = { chain ->
                        val delegate = chain.thisObject ?: return@installHook
                        val trace = debugListenNowTraceForDelegate(
                            delegate = delegate,
                            delegateLiveDataField = delegateLiveDataField,
                        ) ?: return@installHook
                        val liveData = trace.liveData.get()
                        val currentLiveValue = liveData?.let { target ->
                            runCatching { liveDataGetValue.invoke(target) }.getOrNull()
                        }
                        val incoming = chain.args.firstOrNull()
                        ProviderLogger.diagnostic(
                            "ListenNowArtwork: event=delegate_${method.name}_before, " +
                                debugListenNowArtworkTraceIdentity(trace) + ", " +
                                "delegate=${objectIdentity(delegate)}, " +
                                "incoming=${debugListenNowArtworkValueSummary(incoming)}, " +
                                "sameAsLive=${debugListenNowArtworkUrls(incoming) ==
                                    debugListenNowArtworkUrls(currentLiveValue)}, " +
                                "liveValue=${debugListenNowArtworkValueSummary(currentLiveValue)}"
                        )
                    },
                    after = { chain, _ ->
                        val delegate = chain.thisObject ?: return@installHook
                        val trace = debugListenNowTraceForDelegate(
                            delegate = delegate,
                            delegateLiveDataField = delegateLiveDataField,
                        ) ?: return@installHook
                        ProviderLogger.diagnostic(
                            "ListenNowArtwork: event=delegate_${method.name}_after, " +
                                debugListenNowArtworkTraceIdentity(trace) + ", " +
                                "delegateValue=${debugListenNowDelegateArtworkSummary(
                                    delegate,
                                    delegateGetImageUrl,
                                    delegateGetImageUrls,
                                )}, images=${debugListenNowArtworkImageStates(trace)}"
                        )
                    },
                )
            }

            liveDataMutationMethods.forEach { method ->
                runtime.hookRegistrar.installHook(
                    method,
                    before = { chain ->
                        val liveData = chain.thisObject ?: return@installHook
                        val trace = debugListenNowArtworkLiveData[liveData]
                            ?: return@installHook
                        val current = runCatching { liveDataGetValue.invoke(liveData) }
                            .getOrNull()
                        val incoming = chain.args.firstOrNull()
                        ProviderLogger.diagnostic(
                            "ListenNowArtwork: event=live_data_${method.name}_before, " +
                                debugListenNowArtworkTraceIdentity(trace) + ", " +
                                "liveData=${objectIdentity(liveData)}, " +
                                "incoming=${debugListenNowArtworkValueSummary(incoming)}, " +
                                "current=${debugListenNowArtworkValueSummary(current)}, " +
                                "same=${debugListenNowArtworkUrls(incoming) ==
                                    debugListenNowArtworkUrls(current)}, " +
                                "images=${debugListenNowArtworkImageStates(trace)}"
                        )
                    },
                    after = { chain, _ ->
                        val liveData = chain.thisObject ?: return@installHook
                        val trace = debugListenNowArtworkLiveData[liveData]
                            ?: return@installHook
                        debugListenNowLogTraceSnapshot(
                            trace = trace,
                            stage = "live_data_${method.name}_after",
                            liveDataGetValue = liveDataGetValue,
                        )
                        if (method.name == "postValue") {
                            runtime.mainHandler.post {
                                debugListenNowLogTraceSnapshot(
                                    trace = trace,
                                    stage = "live_data_postValue_committed",
                                    liveDataGetValue = liveDataGetValue,
                                )
                            }
                        }
                    },
                )
            }

            customImageMutationMethods.forEach { method ->
                runtime.hookRegistrar.installHook(
                    method,
                    before = { chain ->
                        val imageView = chain.thisObject ?: return@installHook
                        val trace = debugListenNowArtworkImageViews[imageView]
                            ?: return@installHook
                        ProviderLogger.diagnostic(
                            "ListenNowArtwork: event=image_${method.name}_before, " +
                                debugListenNowArtworkTraceIdentity(trace) + ", " +
                                "view=${objectIdentity(imageView)}, " +
                                "incoming=${debugListenNowImageMutationSummary(
                                    chain.args.firstOrNull()
                                )}, state=${debugListenNowImageViewState(imageView as ImageView)}"
                        )
                    },
                    after = { chain, _ ->
                        val imageView = chain.thisObject ?: return@installHook
                        val trace = debugListenNowArtworkImageViews[imageView]
                            ?: return@installHook
                        ProviderLogger.diagnostic(
                            "ListenNowArtwork: event=image_${method.name}_after, " +
                                debugListenNowArtworkTraceIdentity(trace) + ", " +
                                "view=${objectIdentity(imageView)}, " +
                                "state=${debugListenNowImageViewState(imageView as ImageView)}"
                        )
                    },
                )
            }

            ProviderLogger.info(
                "Apple Music 主页 Listen Now 封面诊断 Hook 已安装: " +
                    "bound=${onModelBoundMethod.name}/${onModelBoundMethod.parameterCount}, " +
                    "resolver=${resolverSubmitMethod.name}/${resolverSubmitMethod.parameterCount}, " +
                    "delegateMethods=${delegateArtworkMethods.size}, " +
                    "imageMethods=${customImageMutationMethods.size}, " +
                    "fallback=${resolvedOnModelBound.compatibilityFallback ||
                        resolvedArtworkSubmit.compatibilityFallback}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 主页 Listen Now 封面诊断 Hook 安装失败", it)
        }
    }

    private fun fieldValueByType(instance: Any, fieldType: Class<*>): Any? =
        generateSequence(instance.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { field -> fieldType.isAssignableFrom(field.type) }
            .firstNotNullOfOrNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(instance)
                }.getOrNull()
            }

    private fun debugListenNowTraceForDelegate(
        delegate: Any,
        delegateLiveDataField: Field,
    ): DebugListenNowArtworkTrace? {
        debugListenNowArtworkDelegates[delegate]?.let { return it }
        val liveData = runCatching { delegateLiveDataField.get(delegate) }.getOrNull()
            ?: return null
        return debugListenNowArtworkLiveData[liveData]?.also { trace ->
            debugListenNowArtworkDelegates[delegate] = trace
        }
    }

    private fun debugListenNowLogTraceSnapshot(
        trace: DebugListenNowArtworkTrace,
        stage: String,
        liveDataGetValue: Method,
    ) {
        val liveData = trace.liveData.get()
        val value = liveData?.let { target ->
            runCatching { liveDataGetValue.invoke(target) }.getOrNull()
        }
        ProviderLogger.diagnostic(
            "ListenNowArtwork: event=$stage, " +
                debugListenNowArtworkTraceIdentity(trace) + ", " +
                "liveData=${objectIdentity(liveData)}, " +
                "value=${debugListenNowArtworkValueSummary(value)}, " +
                "root=${objectIdentity(trace.root?.get())}, " +
                "images=${debugListenNowArtworkImageStates(trace)}"
        )
    }

    private fun debugListenNowArtworkTraceIdentity(
        trace: DebugListenNowArtworkTrace,
    ): String =
        "mediaId=${trace.mediaId}, persistentId=${trace.persistentId}, " +
            "contentType=${trace.contentType}, title=${trace.title ?: "none"}"

    private fun debugListenNowArtworkUrls(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is CharSequence -> listOf(value.toString())
        is Array<*> -> value.mapNotNull { it?.toString() }
        is Iterable<*> -> value.mapNotNull { it?.toString() }
        else -> emptyList()
    }.map(String::trim).filter(String::isNotEmpty)

    private fun debugListenNowArtworkValueSummary(value: Any?): String {
        val urls = debugListenNowArtworkUrls(value)
        val values = urls.joinToString(prefix = "[", postfix = "]") { url ->
            "len=${url.length},hash=${url.hashCode()},error=${url == "error url"}"
        }
        return "type=${value?.javaClass?.name ?: "null"},count=${urls.size}," +
            "hash=${urls.hashCode()},values=$values"
    }

    private fun debugListenNowDelegateArtworkSummary(
        delegate: Any,
        getImageUrl: Method,
        getImageUrls: Method,
    ): String {
        val single = runCatching { getImageUrl.invoke(delegate) }.getOrNull()
        if (single != null) return debugListenNowArtworkValueSummary(single)
        return debugListenNowArtworkValueSummary(
            runCatching { getImageUrls.invoke(delegate) }.getOrNull()
        )
    }

    private fun debugListenNowImageViews(root: View?): List<ImageView> {
        root ?: return emptyList()
        val result = mutableListOf<ImageView>()
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited < 48 && result.size < 4) {
            val view = pending.removeFirst()
            visited += 1
            if (view is ImageView) result += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    view.getChildAt(index)?.let(pending::addLast)
                }
            }
        }
        return result
    }

    private fun debugListenNowArtworkImageStates(
        trace: DebugListenNowArtworkTrace,
    ): String = trace.imageViews.mapNotNull(WeakReference<ImageView>::get)
        .joinToString(prefix = "[", postfix = "]") { imageView ->
            debugListenNowImageViewState(imageView)
        }

    private fun debugListenNowImageViewState(imageView: ImageView): String =
        "${objectIdentity(imageView)}{" +
            "drawable=${objectIdentity(imageView.drawable)}/" +
            "${drawableSignature(imageView.drawable)}," +
            "background=${objectIdentity(imageView.background)}/" +
            "${drawableSignature(imageView.background)}," +
            "visibility=${imageView.visibility},alpha=${imageView.alpha}," +
            "shown=${imageView.isShown},attached=${imageView.isAttachedToWindow}}"

    private fun debugListenNowImageMutationSummary(value: Any?): String = when (value) {
        null -> "null"
        is Bitmap ->
            "${objectIdentity(value)}:" +
                "${value.width}x${value.height},generation=${value.generationId}"
        is Drawable ->
            "${objectIdentity(value)}/" +
                drawableSignature(value)
        else -> objectIdentity(value)
    }


    fun clearMetadataState() {
        inAppListenNowDataBindingRefs.clear()
        inAppListenNowDataBindingMediaIds.clear()
        inAppListenNowDataBindingPendingRefreshes.clear()
        inAppListenNowModelBuildStates.clear()
        inAppListenNowModelBuildStatesByLiveData.clear()
    }

    fun hasDataBindingRefs(mediaId: String): Boolean =
        inAppListenNowDataBindingRefs[mediaId]?.isNotEmpty() == true

    fun isArtworkContinuityInstalled(): Boolean =
        inAppListenNowArtworkContinuityHookInstalled

    fun onArtworkDelegateResolved(
        delegate: Any,
        liveData: Any?,
        urls: List<String>,
    ) {
        val identity = inAppListenNowArtworkIdentity(delegate)
        val builderKey = liveData?.let(inAppListenNowArtworkKeysByLiveData::get)
        val hasDebugTrace = debugListenNowArtworkDelegates[delegate] != null
        liveData?.let { exactLiveData ->
            resolveInAppListenNowCatalogIdentity(
                liveData = exactLiveData,
                delegateKey = identity.key,
            )
        }
        if (BuildConfig.DEBUG && (builderKey != null || hasDebugTrace)) {
            host.logMetadataIdentity(
                event = "listen_now_artwork_delegate_cache_candidate",
                details = "moduleVersion=${BuildConfig.VERSION_CODE}, " +
                    "liveData=${objectIdentity(liveData)}, " +
                    "builderArtworkHash=${builderKey?.artworkIdentity?.hashCode()}, " +
                    "builderKeyMatchesDelegate=${builderKey == identity.key}, " +
                    "urls=${urls.size}, urlHash=${urls.hashCode()}, " +
                    debugInAppListenNowArtworkIdentity(identity),
            )
        }
        val cacheKey = preferredInAppListenNowArtworkKey(
            builderKey = builderKey,
            delegateKey = identity.key,
        )
        cacheKey?.let { key ->
            putInAppListenNowArtworkContinuity(key, urls)
            if (BuildConfig.DEBUG && (builderKey != null || hasDebugTrace)) {
                host.logMetadataIdentity(
                    event = "listen_now_artwork_delegate_cache_stored",
                    details = "contentId=${key.id}, persistentId=${key.persistentId}, " +
                        "contentType=${key.contentType}, artworkHash=" +
                        "${key.artworkIdentity.hashCode()}, urls=${urls.size}, " +
                        "urlHash=${urls.hashCode()}, keyOrigin=" +
                        "${if (builderKey != null) "builder_live_data" else "delegate"}",
                )
            }
        }
    }

    private fun objectIdentity(value: Any?): String =
        value?.let { "${it.javaClass.name}@${System.identityHashCode(it)}" } ?: "null"

    private fun drawableSignature(value: Any?): String = when (value) {
        null -> "null"
        is ColorDrawable -> "${value.javaClass.name}:color=${value.color}"
        else -> "${value.javaClass.name}:hash=" +
            (runCatching { value.hashCode() }.getOrNull() ?: "error")
    }

}
