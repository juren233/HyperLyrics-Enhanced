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

/**
 * Debug-only trace for the real Listen Now / Home artwork path.
 *
 * The profiled model builder creates one MutableLiveData<String[]> per card and seeds it
 * from the feed image URL. The profiled bound listener submits a second medialibrary artwork
 * lookup only when the entity has a persistent ID. The trace follows that exact LiveData
 * through the profiled resolver, delegate, and image view so a reproduction can distinguish
 * a duplicate URL publication from an actual clear/rebind or a replacement card View.
 */
internal fun AppleListenNowHooks.installDebugArtworkLifecycleHooks() {
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

internal fun AppleListenNowHooks.fieldValueByType(instance: Any, fieldType: Class<*>): Any? =
    generateSequence(instance.javaClass) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .filter { field -> fieldType.isAssignableFrom(field.type) }
        .firstNotNullOfOrNull { field ->
            runCatching {
                field.isAccessible = true
                field.get(instance)
            }.getOrNull()
        }

internal fun AppleListenNowHooks.debugListenNowTraceForDelegate(
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

internal fun AppleListenNowHooks.debugListenNowLogTraceSnapshot(
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

internal fun AppleListenNowHooks.debugListenNowArtworkTraceIdentity(
    trace: DebugListenNowArtworkTrace,
): String =
    "mediaId=${trace.mediaId}, persistentId=${trace.persistentId}, " +
        "contentType=${trace.contentType}, title=${trace.title ?: "none"}"

internal fun AppleListenNowHooks.debugListenNowArtworkUrls(value: Any?): List<String> = when (value) {
    null -> emptyList()
    is CharSequence -> listOf(value.toString())
    is Array<*> -> value.mapNotNull { it?.toString() }
    is Iterable<*> -> value.mapNotNull { it?.toString() }
    else -> emptyList()
}.map(String::trim).filter(String::isNotEmpty)

internal fun AppleListenNowHooks.debugListenNowArtworkValueSummary(value: Any?): String {
    val urls = debugListenNowArtworkUrls(value)
    val values = urls.joinToString(prefix = "[", postfix = "]") { url ->
        "len=${url.length},hash=${url.hashCode()},error=${url == "error url"}"
    }
    return "type=${value?.javaClass?.name ?: "null"},count=${urls.size}," +
        "hash=${urls.hashCode()},values=$values"
}

internal fun AppleListenNowHooks.debugListenNowDelegateArtworkSummary(
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

internal fun AppleListenNowHooks.debugListenNowImageViews(root: View?): List<ImageView> {
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

internal fun AppleListenNowHooks.debugListenNowArtworkImageStates(
    trace: DebugListenNowArtworkTrace,
): String = trace.imageViews.mapNotNull(WeakReference<ImageView>::get)
    .joinToString(prefix = "[", postfix = "]") { imageView ->
        debugListenNowImageViewState(imageView)
    }

internal fun AppleListenNowHooks.debugListenNowImageViewState(imageView: ImageView): String =
    "${objectIdentity(imageView)}{" +
        "drawable=${objectIdentity(imageView.drawable)}/" +
        "${drawableSignature(imageView.drawable)}," +
        "background=${objectIdentity(imageView.background)}/" +
        "${drawableSignature(imageView.background)}," +
        "visibility=${imageView.visibility},alpha=${imageView.alpha}," +
        "shown=${imageView.isShown},attached=${imageView.isAttachedToWindow}}"

internal fun AppleListenNowHooks.debugListenNowImageMutationSummary(value: Any?): String = when (value) {
    null -> "null"
    is Bitmap ->
        "${objectIdentity(value)}:" +
            "${value.width}x${value.height},generation=${value.generationId}"
    is Drawable ->
        "${objectIdentity(value)}/" +
            drawableSignature(value)
    else -> objectIdentity(value)
}


internal fun AppleListenNowHooks.clearMetadataState() {
    inAppListenNowDataBindingRefs.clear()
    inAppListenNowDataBindingMediaIds.clear()
    inAppListenNowDataBindingPendingRefreshes.clear()
    inAppListenNowModelBuildStates.clear()
    inAppListenNowModelBuildStatesByLiveData.clear()
}

internal fun AppleListenNowHooks.hasDataBindingRefs(mediaId: String): Boolean =
    inAppListenNowDataBindingRefs[mediaId]?.isNotEmpty() == true

internal fun AppleListenNowHooks.isArtworkContinuityInstalled(): Boolean =
    inAppListenNowArtworkContinuityHookInstalled

internal fun AppleListenNowHooks.onArtworkDelegateResolved(
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

internal fun AppleListenNowHooks.objectIdentity(value: Any?): String =
    value?.let { "${it.javaClass.name}@${System.identityHashCode(it)}" } ?: "null"

internal fun AppleListenNowHooks.drawableSignature(value: Any?): String = when (value) {
    null -> "null"
    is ColorDrawable -> "${value.javaClass.name}:color=${value.color}"
    else -> "${value.javaClass.name}:hash=" +
        (runCatching { value.hashCode() }.getOrNull() ?: "error")
}

