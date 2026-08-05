/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalStack
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

internal interface AppleDataBindingMetadataHost {
    fun contentItemMediaId(contentItem: Any): String?

    fun bindingCandidateMediaId(value: Any): String?

    fun onBeginBindingModel(binding: Any)

    fun onBindingMediaIdChanged(binding: Any, previousMediaId: String?, mediaId: String)

    fun originalResolutionMode(binding: Any): InAppOriginalResolutionMode

    fun shouldInvalidateAppliedAlias(
        binding: Any,
        mediaId: String,
        appliedAlias: AppliedMetadataAlias,
        pendingAlias: AppliedMetadataAlias?,
        renderedTexts: Collection<String>,
    ): Boolean

    fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias?

    fun aliasValues(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        binding: Any?,
    ): DataBindingAliasValues

    fun isCurrentSurfaceMediaId(mediaId: String): Boolean

    fun hasVisibleConsumer(mediaId: String): Boolean

    fun isRefreshableMediaId(mediaId: String): Boolean

    fun boundModelCandidates(mediaId: String): List<Any>

    fun enrichEntitiesForResolution(mediaIds: Collection<String>)

    fun markMetadataVisible(mediaIds: Collection<String>)

    fun scheduleMetadataResolution(
        mediaIds: Collection<String>,
        priority: AppleInternalCatalogResolver.RequestPriority,
        originalResolutionMode: InAppOriginalResolutionMode =
            InAppOriginalResolutionMode.AFTER_LOCALIZED,
    )

    fun isAppleLyricsRecyclerAdapter(adapter: Any?): Boolean

    fun isQueueAdapter(adapter: Any): Boolean

    fun isArtistProfileRecyclerAdapter(adapter: Any): Boolean

    fun nextMetadataTraceSequence(): Long
}

internal fun normalizedRecyclerBindingMediaIds(mediaIds: Collection<String>): Set<String> =
    mediaIds.asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && it.all(Char::isDigit) }
        .toCollection(linkedSetOf())

internal fun shouldScheduleVisibleRecyclerMetadata(
    previousMediaIds: Set<String>?,
    currentMediaIds: Set<String>,
    visible: Boolean,
): Boolean = visible && currentMediaIds.isNotEmpty() && previousMediaIds != currentMediaIds

internal fun shouldRegisterGenericRecyclerRefresh(
    mediaIds: Set<String>,
    dataBindingMediaId: String?,
    blockMultiItemStructuralRefresh: Boolean,
): Boolean {
    if (mediaIds.isEmpty()) return false
    if (mediaIds.size == 1 && dataBindingMediaId in mediaIds) return false
    return !blockMultiItemStructuralRefresh
}

internal fun shouldScheduleDataBindingAliasRefresh(
    appliedAlias: AppliedMetadataAlias?,
    pendingAlias: AppliedMetadataAlias?,
    requestedAlias: AppliedMetadataAlias?,
): Boolean = requestedAlias == null ||
    (appliedAlias != requestedAlias && pendingAlias != requestedAlias)

internal fun dataBindingRefreshStrategy(
    expectedTitle: String?,
    expectedSubtitle: String?,
    titleApplied: Boolean,
    subtitleApplied: Boolean,
): DataBindingRefreshStrategy {
    val titleRequired = !expectedTitle.isNullOrBlank()
    val subtitleRequired = !expectedSubtitle.isNullOrBlank()
    val allRequiredVariablesApplied =
        (titleRequired || subtitleRequired) &&
            (!titleRequired || titleApplied) &&
            (!subtitleRequired || subtitleApplied)
    return if (allRequiredVariablesApplied) {
        DataBindingRefreshStrategy.VARIABLES_ONLY
    } else {
        DataBindingRefreshStrategy.FULL_INVALIDATE
    }
}

internal fun dataBindingAliasAlreadyRendered(
    expectedTitle: String?,
    expectedSubtitle: String?,
    renderedTexts: Collection<String>,
): Boolean {
    val rendered = renderedTexts
        .map(String::trim)
        .filter(String::isNotEmpty)
    fun containsExpected(value: String?): Boolean {
        val expected = value?.trim()?.takeIf(String::isNotEmpty) ?: return true
        return rendered.any { text -> text == expected || expected in text }
    }
    return rendered.isNotEmpty() &&
        containsExpected(expectedTitle) &&
        containsExpected(expectedSubtitle)
}

internal fun isDataBindingRefreshCurrent(
    currentMediaId: String?,
    requestedMediaId: String,
    currentBindGeneration: Long,
    scheduledBindGeneration: Long,
): Boolean = currentMediaId == requestedMediaId &&
    currentBindGeneration == scheduledBindGeneration

internal class AppleDataBindingMetadataHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val host: AppleDataBindingMetadataHost,
) {
    private companion object {
        const val MAX_GENERIC_RECYCLER_MEDIA_IDS = 512
    }

    private val bindingRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<WeakReference<Any>>>()
    private val bindingInstances = ConcurrentLinkedQueue<WeakReference<Any>>()
    private val bindingMediaIds =
        Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val bindingRootViews =
        Collections.synchronizedMap(WeakHashMap<Any, WeakReference<View>>())
    private val bindingsByRoot = WeakIdentityMap<View, WeakReference<Any>>()
    private val activeRecyclerBindCaptures = ThreadLocalStack<RecyclerBindCapture>()
    private val recyclerRootMediaIds = WeakIdentityMap<View, Set<String>>()
    private val recyclerRootVisibleResolutionIds = WeakIdentityMap<View, Set<String>>()
    private val genericRecyclerItemRefs =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, ConcurrentLinkedQueue<InAppRecyclerItemRef>>(
                MAX_GENERIC_RECYCLER_MEDIA_IDS,
                0.75f,
                true,
            ) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<
                        String,
                        ConcurrentLinkedQueue<InAppRecyclerItemRef>,
                        >?,
                ): Boolean = size > MAX_GENERIC_RECYCLER_MEDIA_IDS
            }
        )
    private val appliedAliases =
        Collections.synchronizedMap(WeakHashMap<Any, AppliedMetadataAlias>())
    private val pendingRefreshes =
        Collections.synchronizedMap(WeakHashMap<Any, PendingDataBindingRefresh>())
    private val bindGenerations = Collections.synchronizedMap(WeakHashMap<Any, Long>())
    private val visibleResolutionPosts =
        Collections.synchronizedMap(
            WeakHashMap<Any, PendingVisibleDataBindingResolution>()
        )
    private val bindingContentFields = ConcurrentHashMap<Class<*>, List<Field>>()

    @Volatile
    private var bindingBaseClass: Class<*>? = null
    @Volatile
    private var recyclerViewClass: Class<*>? = null
    @Volatile
    private var invalidateAllMethod: Method? = null
    @Volatile
    private var executePendingBindingsMethod: Method? = null
    @Volatile
    private var setVariableMethod: Method? = null
    @Volatile
    private var titleVariableId: Int? = null
    @Volatile
    private var subtitleVariableId: Int? = null

    fun installDataBindingHooks() {
        runCatching {
            val resolvedClasses = runtime.hookResolver.resolveClasses(
                AppleMusicHookPoint.DATA_BINDING_RUNTIME_CLASSES
            )
            val classesByRole = resolvedClasses.associateBy { resolved ->
                resolved.target.runtimeMemberName(
                    AppleMusicRuntimeMember.DATA_BINDING_RUNTIME_ROLE
                )
            }
            val bindingResolved = checkNotNull(classesByRole["binding"])
            val bindingClass = bindingResolved.clazz
            val observableClass = checkNotNull(classesByRole["observable"]?.clazz)
            val brResolved = checkNotNull(classesByRole["br"])
            bindingBaseClass = bindingClass
            recyclerViewClass = classesByRole["recycler"]?.clazz
            val registrationMethod = AppleReflection.findMethod(
                bindingClass,
                bindingResolved.target.runtimeMemberName(
                    AppleMusicRuntimeMember.DATA_BINDING_REGISTRATION_METHOD
                ),
                parameterTypes = listOf(Int::class.javaPrimitiveType!!, observableClass),
            )
            invalidateAllMethod = AppleReflection.findMethod(
                bindingClass,
                bindingResolved.target.runtimeMemberName(
                    AppleMusicRuntimeMember.DATA_BINDING_INVALIDATE_METHOD
                ),
                parameterCount = 0,
            )
            executePendingBindingsMethod = AppleReflection.findMethod(
                bindingClass,
                bindingResolved.target.runtimeMemberName(
                    AppleMusicRuntimeMember.DATA_BINDING_EXECUTE_METHOD
                ),
                parameterCount = 0,
            )
            setVariableMethod = AppleReflection.findMethod(
                bindingClass,
                bindingResolved.target.runtimeMemberName(
                    AppleMusicRuntimeMember.DATA_BINDING_SET_VARIABLE_METHOD
                ),
                parameterTypes = listOf(
                    Int::class.javaPrimitiveType!!,
                    Any::class.java,
                ),
            )
            titleVariableId = brResolved.clazz.getDeclaredField(
                brResolved.target.runtimeMemberName(
                    AppleMusicRuntimeMember.DATA_BINDING_TITLE_VARIABLE_FIELD
                )
            ).apply { isAccessible = true }.getInt(null)
            subtitleVariableId = brResolved.clazz.getDeclaredField(
                brResolved.target.runtimeMemberName(
                    AppleMusicRuntimeMember.DATA_BINDING_SUBTITLE_VARIABLE_FIELD
                )
            ).apply { isAccessible = true }.getInt(null)
            val executeMethod = requireNotNull(executePendingBindingsMethod)
            val bindingConstructor = bindingClass.getDeclaredConstructor(
                Any::class.java,
                View::class.java,
                Int::class.javaPrimitiveType!!,
            ).apply { isAccessible = true }
            runtime.hookRegistrar.installHook(bindingConstructor, after = { chain, _ ->
                chain.thisObject?.let { binding ->
                    capture(binding, chain.args.getOrNull(1) as? View)
                }
            })
            runtime.hookRegistrar.installHook(registrationMethod, after = { chain, _ ->
                val binding = chain.thisObject ?: return@installHook
                capture(binding)
                val contentItem = chain.args.getOrNull(1) ?: return@installHook
                val contentItemClass = classesByRole["content_item"]?.clazz
                    ?: return@installHook
                if (!contentItemClass.isInstance(contentItem)) return@installHook
                val mediaId = host.contentItemMediaId(contentItem) ?: return@installHook
                register(mediaId, binding)
            })
            runtime.hookRegistrar.installHook(executeMethod, after = { chain, _ ->
                val binding = chain.thisObject ?: return@installHook
                val mediaId = bindingMediaIds[binding] ?: return@installHook
                val root = bindingRootViews[binding]?.get() ?: return@installHook
                if (!isRootVisible(root)) return@installHook
                invalidateOverwrittenAlias(binding, mediaId, root)
                postVisibleResolution(binding, mediaId)
            })
            ProviderLogger.info(
                "Apple Music 资料库精确重绑定 Hook 已安装: " +
                    "registration=${registrationMethod.name}, " +
                    "invalidate=${invalidateAllMethod?.name}, execute=${executeMethod.name}, " +
                    "setVariable=${setVariableMethod?.name}, titleVariable=$titleVariableId, " +
                    "subtitleVariable=$subtitleVariableId, constructor=true"
            )
        }.onFailure {
            invalidateAllMethod = null
            executePendingBindingsMethod = null
            setVariableMethod = null
            titleVariableId = null
            subtitleVariableId = null
            bindingBaseClass = null
            ProviderLogger.error("Apple Music 资料库精确重绑定 Hook 安装失败", it)
        }
    }

    fun installRecyclerHooks() {
        runCatching {
            val recyclerClass = recyclerViewClass
                ?: runtime.hookResolver.resolveClasses(
                    AppleMusicHookPoint.DATA_BINDING_RUNTIME_CLASSES
                ).first { resolved ->
                    resolved.target.runtimeMemberName(
                        AppleMusicRuntimeMember.DATA_BINDING_RUNTIME_ROLE
                    ) == "recycler"
                }.clazz.also { recyclerViewClass = it }
            val bindMethod = recyclerClass.declaredClasses.asSequence()
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { method ->
                    val types = method.parameterTypes
                    method.returnType == Boolean::class.javaPrimitiveType &&
                        types.size == 4 &&
                        generateSequence(types[0]) { it.superclass }.any { holderType ->
                            holderType.declaredFields.any { field ->
                                View::class.java.isAssignableFrom(field.type)
                            }
                        } &&
                        types[1] == Int::class.javaPrimitiveType &&
                        types[2] == Int::class.javaPrimitiveType &&
                        types[3] == Long::class.javaPrimitiveType
                }
                ?.apply { isAccessible = true }
                ?: error("RecyclerView central bind method unavailable")
            runtime.hookRegistrar.installHook(
                bindMethod,
                before = { chain ->
                    val recycler = recyclerViewFromRecycler(chain.thisObject)
                    val adapter = recycler?.let {
                        runCatching { AppleReflection.call(it, "getAdapter") }.getOrNull()
                    }
                    val position = chain.args.getOrNull(1) as? Int ?: -1
                    val captureMetadata = !host.isAppleLyricsRecyclerAdapter(adapter)
                    val root = if (captureMetadata) {
                        chain.args.firstOrNull()?.let(::recyclerViewHolderItemView)
                    } else {
                        null
                    }
                    root?.let { bindingsByRoot[it]?.get() }?.let(::beginModelBind)
                    activeRecyclerBindCaptures.push(
                        RecyclerBindCapture(
                            adapter = adapter?.let(::WeakReference),
                            position = position,
                            root = root?.let(::WeakReference),
                            captureMetadata = captureMetadata,
                        )
                    )
                },
                after = { chain, result ->
                    val capture = activeRecyclerBindCaptures.pop()
                    if (result != true || capture == null || !capture.captureMetadata) {
                        return@installHook
                    }
                    val holder = chain.args.firstOrNull() ?: return@installHook
                    val root = capture.root?.get()
                        ?: recyclerViewHolderItemView(holder)
                        ?: return@installHook
                    captureBoundRoot(root)?.let(capture.mediaIds::add)
                    if (capture.mediaIds.isEmpty()) {
                        recyclerRootMediaIds.remove(root)
                        recyclerRootVisibleResolutionIds.remove(root)
                        return@installHook
                    }
                    registerGenericRecyclerBinding(capture, root)
                    root.postOnAnimation {
                        val visible = isRootVisible(root)
                        val mediaIds = recyclerRootMediaIds[root].orEmpty()
                        if (!shouldScheduleVisibleRecyclerMetadata(
                                previousMediaIds = recyclerRootVisibleResolutionIds[root],
                                currentMediaIds = mediaIds,
                                visible = visible,
                            )
                        ) return@postOnAnimation
                        recyclerRootVisibleResolutionIds[root] = mediaIds
                        captureBoundRoot(root, visible = true)
                        host.enrichEntitiesForResolution(mediaIds)
                        host.markMetadataVisible(mediaIds)
                        host.scheduleMetadataResolution(
                            mediaIds,
                            AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                        )
                        if (BuildConfig.DEBUG) {
                            ProviderLogger.info(
                                "Apple Music 元数据链路: " +
                                    "seq=${host.nextMetadataTraceSequence()}, " +
                                    "event=generic_recycler_visible_request, " +
                                    "contentIds=$mediaIds, root=${root.javaClass.name}, " +
                                    "position=${capture.position}"
                            )
                        }
                    }
                },
            )
            ProviderLogger.info(
                "Apple Music RecyclerView 通用元数据绑定 Hook 已安装: " +
                    "class=${bindMethod.declaringClass.name}, method=${bindMethod.name}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music RecyclerView 通用元数据绑定 Hook 安装失败", it)
        }
    }

    fun isBindingInstance(candidate: Any): Boolean =
        bindingBaseClass?.isInstance(candidate) == true

    fun bindingFromHolder(holder: Any?): Any? {
        holder ?: return null
        val bindingClass = bindingBaseClass ?: return null
        return generateSequence(holder.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { field -> bindingClass.isAssignableFrom(field.type) }
            ?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(holder)
                }.getOrNull()
            }
    }

    fun itemViewFromHolder(holder: Any?): View? =
        holder?.let(::recyclerViewHolderItemView)

    fun capture(binding: Any, root: View? = null) {
        if (root != null) {
            bindingRootViews[binding] = WeakReference(root)
            bindingsByRoot[root] = WeakReference(binding)
        }
        var registered = false
        bindingInstances.forEach { ref ->
            val target = ref.get()
            if (target == null) bindingInstances.remove(ref) else if (target === binding) {
                registered = true
            }
        }
        if (!registered) bindingInstances.add(WeakReference(binding))
    }

    fun beginModelBind(binding: Any) {
        synchronized(bindGenerations) {
            bindGenerations[binding] = (bindGenerations[binding] ?: 0L) + 1L
        }
        host.onBeginBindingModel(binding)
        appliedAliases.remove(binding)
        pendingRefreshes.remove(binding)
    }

    fun clearMediaId(binding: Any) {
        bindingMediaIds.remove(binding)
    }

    fun generation(binding: Any): Long = synchronized(bindGenerations) {
        bindGenerations[binding] ?: 0L
    }

    fun register(
        mediaId: String,
        binding: Any,
        originalResolutionMode: InAppOriginalResolutionMode =
            InAppOriginalResolutionMode.AFTER_LOCALIZED,
    ) {
        val previousMediaId = bindingMediaIds[binding]
        host.onBindingMediaIdChanged(binding, previousMediaId, mediaId)
        bindingMediaIds[binding] = mediaId
        val refs = bindingRefs.computeIfAbsent(mediaId) { ConcurrentLinkedQueue() }
        var registered = false
        refs.forEach { ref ->
            val target = ref.get()
            if (target == null) refs.remove(ref) else if (target === binding) registered = true
        }
        if (!registered) refs.add(WeakReference(binding))
        val root = bindingRootViews[binding]?.get()
        if (root != null && isRootVisible(root)) {
            postVisibleResolution(binding, mediaId, originalResolutionMode)
        }
        if (BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                    "event=data_binding_register, contentId=$mediaId, " +
                    "previousContentId=$previousMediaId, " +
                    "binding=${binding.javaClass.name}@${System.identityHashCode(binding)}, " +
                    "rootVisible=${root?.let(::isRootVisible) == true}, " +
                    "texts=${root?.let(::debugTextSnapshot)}"
            )
        }
    }

    fun mediaId(binding: Any): String? = bindingMediaIds[binding]

    fun root(binding: Any): View? = bindingRootViews[binding]?.get()

    fun appliedAlias(binding: Any): AppliedMetadataAlias? = appliedAliases[binding]

    fun rememberAppliedAlias(binding: Any, alias: AppliedMetadataAlias) {
        appliedAliases[binding] = alias
    }

    fun renderedTexts(binding: Any): List<String> =
        root(binding)?.let(::dataBindingRenderedTexts).orEmpty()

    fun renderedTexts(root: View): List<String> = dataBindingRenderedTexts(root)

    fun applyAliasVariables(
        binding: Any,
        values: DataBindingAliasValues,
    ): DataBindingVariableApplyResult {
        val setVariable = setVariableMethod
            ?: return DataBindingVariableApplyResult(false, false)
        fun setVariableValue(variableId: Int?, value: String?): Boolean {
            if (variableId == null || value.isNullOrBlank()) return false
            return runCatching { setVariable.invoke(binding, variableId, value) == true }
                .getOrDefault(false)
        }
        return DataBindingVariableApplyResult(
            titleApplied = setVariableValue(titleVariableId, values.title),
            subtitleApplied = setVariableValue(subtitleVariableId, values.subtitle),
        )
    }

    fun invalidate(binding: Any) {
        invalidateAllMethod?.invoke(binding)
    }

    fun executePending(binding: Any) {
        executePendingBindingsMethod?.invoke(binding)
    }

    fun isRootVisible(root: View): Boolean {
        if (
            !root.isAttachedToWindow || root.visibility != View.VISIBLE ||
            !root.isShown || root.width <= 0 || root.height <= 0
        ) return false
        val visibleRect = Rect()
        return root.getGlobalVisibleRect(visibleRect) &&
            visibleRect.width() > 0 && visibleRect.height() > 0
    }

    fun recordCurrentRecyclerMediaId(mediaId: String): Boolean {
        val normalized = mediaId.trim()
        if (normalized.isEmpty() || !normalized.all(Char::isDigit)) return false
        val capture = activeRecyclerBindCaptures.current ?: return false
        if (!capture.captureMetadata) return false
        capture.mediaIds.add(normalized)
        return true
    }

    fun hasRefs(mediaId: String): Boolean =
        bindingRefs[mediaId]?.any { it.get() != null } == true

    fun refCount(mediaId: String): Int = bindingRefs[mediaId]?.count { it.get() != null } ?: 0

    fun hasVisibleExactConsumer(mediaId: String): Boolean =
        bindingRefs[mediaId]?.any { ref ->
            val binding = ref.get() ?: return@any false
            bindingMediaIds[binding] == mediaId &&
                bindingRootViews[binding]?.get()?.let(::isRootVisible) == true
        } == true

    fun hasGenericRecyclerRefs(mediaId: String): Boolean =
        genericRecyclerItemRefs[mediaId]?.any { ref ->
            val root = ref.root.get() ?: return@any false
            ref.adapter.get() != null && boundRootContainsMediaId(root, mediaId) &&
                isRootVisible(root)
        } == true

    fun refreshDataBindings(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias? = null,
    ): Int {
        val surfaceRelevant = host.isCurrentSurfaceMediaId(mediaId)
        if (!shouldRefreshInAppSurface(surfaceRelevant, host.hasVisibleConsumer(mediaId))) return 0
        val invalidateAll = invalidateAllMethod ?: return 0
        val executePending = executePendingBindingsMethod
        val appliedAlias = alias?.let { AppliedMetadataAlias(mediaId, it) }
        val defaultValues = alias?.let { host.aliasValues(mediaId, it, null) }
        val bindingCandidates = host.boundModelCandidates(mediaId)
        val targets = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        bindingRefs[mediaId]?.forEach { ref ->
            val binding = ref.get()
            if (binding == null) {
                bindingRefs[mediaId]?.remove(ref)
            } else if (
                shouldRefreshExactBoundTarget(
                    surfaceRelevant,
                    bindingMediaIds[binding] == mediaId,
                    bindingRootViews[binding]?.get()?.let(::isRootVisible) == true,
                ) && shouldScheduleDataBindingAliasRefresh(
                    appliedAliases[binding],
                    pendingRefreshes[binding]?.alias,
                    appliedAlias,
                )
            ) {
                targets.add(binding)
            }
        }
        if (bindingCandidates.isNotEmpty()) {
            bindingInstances.forEach { ref ->
                val binding = ref.get()
                if (binding == null) {
                    bindingInstances.remove(ref)
                } else if (bindingReferencesAny(binding, bindingCandidates)) {
                    bindingMediaIds[binding] = mediaId
                    if (
                        shouldRefreshExactBoundTarget(
                            surfaceRelevant,
                            true,
                            bindingRootViews[binding]?.get()?.let(::isRootVisible) == true,
                        ) && shouldScheduleDataBindingAliasRefresh(
                            appliedAliases[binding],
                            pendingRefreshes[binding]?.alias,
                            appliedAlias,
                        )
                    ) targets.add(binding)
                }
            }
        }
        if (BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                    "event=data_binding_refresh_targets, contentId=$mediaId, " +
                    "alias=${alias?.title}/${alias?.artist}/${alias?.album}, " +
                    "values=${defaultValues?.title}/${defaultValues?.subtitle}, " +
                    "directRefs=${bindingRefs[mediaId]?.size ?: 0}, " +
                    "candidates=${bindingCandidates.size}, targets=${targets.size}"
            )
        }
        targets.forEach { binding ->
            val bindGeneration = generation(binding)
            val pendingRefresh = appliedAlias?.let {
                PendingDataBindingRefresh(mediaId, it, bindGeneration)
            }
            val shouldPost = if (pendingRefresh == null) {
                true
            } else {
                synchronized(pendingRefreshes) {
                    if (!shouldScheduleDataBindingAliasRefresh(
                            appliedAliases[binding],
                            pendingRefreshes[binding]?.alias,
                            pendingRefresh.alias,
                        )
                    ) false else {
                        pendingRefreshes[binding] = pendingRefresh
                        true
                    }
                }
            }
            if (!shouldPost) return@forEach
            runtime.mainHandler.post {
                fun abandon() {
                    pendingRefresh?.let { clearPendingRefresh(binding, it) }
                }
                if (pendingRefresh != null && pendingRefreshes[binding] != pendingRefresh) {
                    return@post
                }
                if (
                    !isDataBindingRefreshCurrent(
                        bindingMediaIds[binding],
                        mediaId,
                        generation(binding),
                        bindGeneration,
                    ) || !shouldRefreshExactBoundTarget(
                        host.isCurrentSurfaceMediaId(mediaId),
                        true,
                        bindingRootViews[binding]?.get()?.let(::isRootVisible) == true,
                    )
                ) {
                    abandon()
                    return@post
                }
                val previousAppliedAlias = appliedAliases[binding]
                val root = bindingRootViews[binding]?.get()
                val values = alias?.let { host.aliasValues(mediaId, it, binding) }
                if (BuildConfig.DEBUG) {
                    ProviderLogger.info(
                        "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                            "event=data_binding_apply_before, contentId=$mediaId, " +
                            "binding=${binding.javaClass.name}@${System.identityHashCode(binding)}, " +
                            "values=${values?.title}/${values?.subtitle}, " +
                            "texts=${root?.let(::debugTextSnapshot)}"
                    )
                }
                var refreshStrategy = DataBindingRefreshStrategy.FULL_INVALIDATE
                runCatching {
                    val variableResults = values?.let { applyAliasVariables(binding, it) }
                    refreshStrategy = dataBindingRefreshStrategy(
                        expectedTitle = values?.title,
                        expectedSubtitle = values?.subtitle,
                        titleApplied = variableResults?.titleApplied == true,
                        subtitleApplied = variableResults?.subtitleApplied == true,
                    )
                    if (refreshStrategy == DataBindingRefreshStrategy.FULL_INVALIDATE) {
                        invalidateAll.invoke(binding)
                    }
                    executePending?.invoke(binding)
                    variableResults
                }.onSuccess { variableResults ->
                    if (bindingMediaIds[binding] == mediaId && generation(binding) == bindGeneration) {
                        appliedAlias?.let { appliedAliases[binding] = it }
                    }
                    abandon()
                    if (BuildConfig.DEBUG) {
                        ProviderLogger.info(
                            "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                                "event=data_binding_rebind, contentId=$mediaId, " +
                                "binding=${binding.javaClass.name}, " +
                                "titleApplied=${variableResults?.titleApplied}, " +
                                "subtitleApplied=${variableResults?.subtitleApplied}, " +
                                "refreshStrategy=$refreshStrategy, " +
                                "texts=${root?.let(::debugTextSnapshot)}"
                        )
                    }
                }.onFailure {
                    abandon()
                    if (previousAppliedAlias == null) appliedAliases.remove(binding)
                    else appliedAliases[binding] = previousAppliedAlias
                    ProviderLogger.error(
                        "Apple Music 资料库精确重绑定失败: " +
                            "id=$mediaId, binding=${binding.javaClass.name}",
                        it,
                    )
                }
            }
        }
        return targets.size
    }

    fun refreshGenericRecyclerItems(mediaId: String): Int {
        if (!host.isRefreshableMediaId(mediaId)) return 0
        val refs = genericRecyclerItemRefs[mediaId] ?: return 0
        var targets = 0
        refs.forEach { ref ->
            val adapter = ref.adapter.get()
            val root = ref.root.get()
            if (
                adapter == null || root == null || !boundRootContainsMediaId(root, mediaId) ||
                !shouldRefreshExactBoundTarget(
                    host.isCurrentSurfaceMediaId(mediaId),
                    true,
                    isRootVisible(root),
                )
            ) {
                refs.remove(ref)
                return@forEach
            }
            targets += 1
            runtime.mainHandler.post {
                if (shouldRefreshExactBoundTarget(
                        host.isCurrentSurfaceMediaId(mediaId),
                        boundRootContainsMediaId(root, mediaId),
                        isRootVisible(root),
                    )
                ) {
                    runCatching { AppleReflection.call(adapter, "notifyItemChanged", ref.position) }
                        .onFailure {
                            ProviderLogger.error(
                                "Apple Music RecyclerView 精确刷新失败: " +
                                    "id=$mediaId, position=${ref.position}",
                                it,
                            )
                        }
                }
            }
        }
        return targets
    }

    fun clearConfigurationState() {
        appliedAliases.clear()
        pendingRefreshes.clear()
        bindGenerations.clear()
        recyclerRootVisibleResolutionIds.clear()
    }

    private fun clearPendingRefresh(binding: Any, expected: PendingDataBindingRefresh) {
        synchronized(pendingRefreshes) {
            if (pendingRefreshes[binding] == expected) pendingRefreshes.remove(binding)
        }
    }

    private fun captureBoundRoot(root: View, visible: Boolean = false): String? {
        val binding = bindingsByRoot[root]?.get() ?: return null
        val mediaId = resolvedMediaId(binding) ?: return null
        if (visible && isRootVisible(root)) resolveVisible(binding, mediaId)
        return mediaId
    }

    private fun postVisibleResolution(
        binding: Any,
        mediaId: String,
        originalResolutionMode: InAppOriginalResolutionMode = host.originalResolutionMode(binding),
    ) {
        val root = bindingRootViews[binding]?.get() ?: return
        val pending = PendingVisibleDataBindingResolution(
            mediaId = mediaId,
            bindGeneration = generation(binding),
            originalResolutionMode = originalResolutionMode,
        )
        val shouldPost = synchronized(visibleResolutionPosts) {
            if (visibleResolutionPosts[binding] == pending) false else {
                visibleResolutionPosts[binding] = pending
                true
            }
        }
        if (!shouldPost) return
        root.postOnAnimation {
            val current = synchronized(visibleResolutionPosts) { visibleResolutionPosts[binding] }
            if (current != pending) return@postOnAnimation
            synchronized(visibleResolutionPosts) { visibleResolutionPosts.remove(binding) }
            if (
                bindingMediaIds[binding] != mediaId ||
                generation(binding) != pending.bindGeneration ||
                !isRootVisible(root)
            ) return@postOnAnimation
            resolveVisible(binding, mediaId, pending.originalResolutionMode)
        }
    }

    private fun invalidateOverwrittenAlias(binding: Any, mediaId: String, root: View) {
        val appliedAlias = appliedAliases[binding] ?: return
        val pendingAlias = pendingRefreshes[binding]?.alias
        val renderedTexts = dataBindingRenderedTexts(root)
        if (!host.shouldInvalidateAppliedAlias(
                binding,
                mediaId,
                appliedAlias,
                pendingAlias,
                renderedTexts,
            )
        ) return
        appliedAliases.remove(binding)
        if (BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                    "event=data_binding_alias_invalidated, contentId=$mediaId, " +
                    "rendered=$renderedTexts"
            )
        }
    }

    private fun resolveVisible(
        binding: Any,
        mediaId: String,
        originalResolutionMode: InAppOriginalResolutionMode = host.originalResolutionMode(binding),
    ) {
        host.enrichEntitiesForResolution(listOf(mediaId))
        host.markMetadataVisible(listOf(mediaId))
        host.effectiveAlias(mediaId)?.let { refreshDataBindings(mediaId, it) }
        host.scheduleMetadataResolution(
            listOf(mediaId),
            AppleInternalCatalogResolver.RequestPriority.VISIBLE,
            originalResolutionMode,
        )
    }

    private fun resolvedMediaId(binding: Any): String? {
        bindingMediaIds[binding]?.let { return it }
        val fields = bindingContentFields.computeIfAbsent(binding.javaClass) {
            discoverBindingContentFields(it)
        }
        val candidates = fields.mapNotNull { field ->
            val value = runCatching { field.get(binding) }.getOrNull() ?: return@mapNotNull null
            host.bindingCandidateMediaId(value)
        }.distinct()
        val mediaId = candidates.singleOrNull()
        if (mediaId == null) {
            if (BuildConfig.DEBUG) {
                ProviderLogger.info(
                    "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                        "event=data_binding_resolve_miss, " +
                        "binding=${binding.javaClass.name}@${System.identityHashCode(binding)}, " +
                        "candidates=$candidates, fieldCount=${fields.size}, " +
                        "texts=${bindingRootViews[binding]?.get()?.let(::debugTextSnapshot)}"
                )
            }
            return null
        }
        register(mediaId, binding)
        return mediaId
    }

    private fun registerGenericRecyclerBinding(capture: RecyclerBindCapture, root: View) {
        val mediaIds = normalizedRecyclerBindingMediaIds(capture.mediaIds)
        if (mediaIds.isEmpty()) return
        recyclerRootMediaIds[root] = mediaIds
        val adapter = capture.adapter?.get() ?: return
        if (capture.position < 0) return
        val dataBinding = bindingsByRoot[root]?.get()
        val dataBindingMediaId = dataBinding?.let(::resolvedMediaId)
            ?: mediaIds.singleOrNull()?.also { mediaId ->
                dataBinding?.let { register(mediaId, it) }
            }
        if (host.isQueueAdapter(adapter)) return
        val blockMultiItemStructuralRefresh =
            mediaIds.size > 1 && host.isArtistProfileRecyclerAdapter(adapter)
        if (!shouldRegisterGenericRecyclerRefresh(
                mediaIds,
                dataBindingMediaId,
                blockMultiItemStructuralRefresh,
            )
        ) {
            if (BuildConfig.DEBUG && blockMultiItemStructuralRefresh) {
                ProviderLogger.info(
                    "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                        "event=generic_recycler_structural_refresh_blocked, " +
                        "contentIds=$mediaIds, root=${root.javaClass.name}, " +
                        "position=${capture.position}, adapter=${adapter.javaClass.name}"
                )
            }
            return
        }
        mediaIds.filterNot { it == dataBindingMediaId }.forEach { mediaId ->
            val refs = genericRecyclerItemRefs.computeIfAbsent(mediaId) {
                ConcurrentLinkedQueue()
            }
            var registered = false
            refs.forEach { ref ->
                val targetAdapter = ref.adapter.get()
                val targetRoot = ref.root.get()
                if (targetAdapter == null || targetRoot == null) {
                    refs.remove(ref)
                } else if (
                    targetAdapter === adapter && targetRoot === root &&
                    ref.position == capture.position
                ) {
                    registered = true
                }
            }
            if (!registered) {
                refs.add(
                    InAppRecyclerItemRef(
                        adapter = WeakReference(adapter),
                        root = WeakReference(root),
                        position = capture.position,
                    )
                )
            }
        }
    }

    private fun boundRootContainsMediaId(root: View, mediaId: String): Boolean =
        mediaId in recyclerRootMediaIds[root].orEmpty()

    private fun recyclerViewHolderItemView(holder: Any): View? =
        generateSequence(holder.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { field -> View::class.java.isAssignableFrom(field.type) }
            ?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(holder) as? View
                }.getOrNull()
            }

    private fun recyclerViewFromRecycler(recycler: Any?): Any? {
        recycler ?: return null
        val recyclerClass = recyclerViewClass ?: return null
        return generateSequence(recycler.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { field -> recyclerClass.isAssignableFrom(field.type) }
            ?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(recycler)
                }.getOrNull()
            }
    }

    private fun bindingReferencesAny(binding: Any, candidates: List<Any>): Boolean {
        val candidateSet = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        candidateSet.addAll(candidates)
        val fields = bindingContentFields.computeIfAbsent(binding.javaClass) {
            discoverBindingContentFields(it)
        }
        return fields.any { field ->
            runCatching { field.get(binding) }.getOrNull()?.let(candidateSet::contains) == true
        }
    }

    private fun discoverBindingContentFields(bindingClass: Class<*>): List<Field> {
        val baseClass = bindingBaseClass ?: return emptyList()
        return generateSequence(bindingClass) { current ->
            current.superclass?.takeUnless { it == baseClass }
        }.flatMap { current -> current.declaredFields.asSequence() }
            .filter { field -> !Modifier.isStatic(field.modifiers) && !field.type.isPrimitive }
            .onEach { field -> field.isAccessible = true }
            .toList()
    }

    private fun dataBindingRenderedTexts(root: View): List<String> {
        val texts = mutableListOf<String>()
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited < 32 && texts.size < 8) {
            val view = pending.removeFirst()
            visited += 1
            if (view is TextView) {
                view.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(texts::add)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    view.getChildAt(index)?.let(pending::addLast)
                }
            }
        }
        return texts
    }

    private fun debugTextSnapshot(root: View): String =
        dataBindingRenderedTexts(root).joinToString(prefix = "[", postfix = "]")
}
