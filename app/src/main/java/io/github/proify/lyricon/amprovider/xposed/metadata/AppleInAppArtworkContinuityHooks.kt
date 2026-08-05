/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import java.util.Collections
import java.util.LinkedHashMap

internal interface AppleInAppArtworkContinuityHost {
    fun onArtworkDelegateResolved(delegate: Any, liveData: Any?, urls: List<String>)

    fun logMetadataIdentity(event: String, details: String)
}

internal class AppleInAppArtworkContinuityHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val host: AppleInAppArtworkContinuityHost,
) {
    private companion object {
        const val MAX_CACHE_ENTRIES = 1_024
        const val CACHE_TTL_MS = 10 * 60 * 1_000L
    }

    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<
            InAppArtworkContinuityKey,
            InAppArtworkContinuityEntry,
            >(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<
                    InAppArtworkContinuityKey,
                    InAppArtworkContinuityEntry,
                    >?,
            ): Boolean = size > MAX_CACHE_ENTRIES
        }
    )

    fun installHooks() {
        runCatching {
            val resolvedDelegate = runtime.hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_DELEGATING_ITEM
            )
            val delegateClass = resolvedDelegate.clazz
            val delegateTarget = resolvedDelegate.target
            val liveDataClass = runtime.classLoader.loadClass("androidx.lifecycle.MutableLiveData")
            val imageUrlsLiveDataField = generateSequence(delegateClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .single { field -> liveDataClass.isAssignableFrom(field.type) }
                .apply { isAccessible = true }
            val accessors = InAppArtworkContinuityAccessors(
                getId = AppleReflection.findMethod(
                    delegateClass,
                    delegateTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.COLLECTION_ITEM_GET_ID_METHOD
                    ),
                    0,
                ),
                getPersistentId = AppleReflection.findMethod(
                    delegateClass,
                    delegateTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.COLLECTION_ITEM_GET_PERSISTENT_ID_METHOD
                    ),
                    0,
                ),
                getContentType = AppleReflection.findMethod(
                    delegateClass,
                    delegateTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.COLLECTION_ITEM_GET_CONTENT_TYPE_METHOD
                    ),
                    0,
                ),
                getArtworkToken = AppleReflection.findMethod(
                    delegateClass,
                    delegateTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.ARTWORK_GET_ARTWORK_TOKEN_METHOD
                    ),
                    0,
                ),
                getAllArtworkTokens = AppleReflection.findMethod(
                    delegateClass,
                    delegateTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.ARTWORK_GET_ALL_ARTWORK_TOKENS_METHOD
                    ),
                    0,
                ),
                getImageUrl = AppleReflection.findMethod(
                    delegateClass,
                    delegateTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.ARTWORK_GET_IMAGE_URL_METHOD
                    ),
                    0,
                ),
                getImageUrls = AppleReflection.findMethod(
                    delegateClass,
                    delegateTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.ARTWORK_GET_IMAGE_URLS_METHOD
                    ),
                    0,
                ),
                setImageUrl = delegateClass.declaredMethods.single { method ->
                    method.name == delegateTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.ARTWORK_SET_IMAGE_URL_METHOD
                    ) &&
                        method.parameterTypes.contentEquals(arrayOf(String::class.java))
                }.apply { isAccessible = true },
                setImageUrls = delegateClass.declaredMethods.single { method ->
                    method.name == delegateTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.ARTWORK_SET_IMAGE_URLS_METHOD
                    ) &&
                        method.parameterTypes.contentEquals(arrayOf(Array<String>::class.java))
                }.apply { isAccessible = true },
                imageUrlsLiveData = imageUrlsLiveDataField,
            )
            val notifyInitialMethod = AppleReflection.findMethod(
                delegateClass,
                delegateTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.ARTWORK_NOTIFY_INITIAL_IMAGE_URL_METHOD
                ),
                0,
            )
            val setImageUrlMethodName = delegateTarget.runtimeMemberName(
                AppleMusicRuntimeMember.ARTWORK_SET_IMAGE_URL_METHOD
            )
            val setImageUrlsMethodName = delegateTarget.runtimeMemberName(
                AppleMusicRuntimeMember.ARTWORK_SET_IMAGE_URLS_METHOD
            )
            val artworkResultMethods = delegateClass.declaredMethods.filter { method ->
                (method.name == setImageUrlMethodName && method.parameterTypes.firstOrNull() ==
                    String::class.java) ||
                    (method.name == setImageUrlsMethodName &&
                        method.parameterTypes.contentEquals(arrayOf(Array<String>::class.java)))
            }
            check(artworkResultMethods.isNotEmpty()) {
                "Apple Music artwork delegate result methods unavailable"
            }
            artworkResultMethods.forEach { method ->
                method.isAccessible = true
                runtime.hookRegistrar.installHook(method, after = { chain, _ ->
                    chain.thisObject?.let { delegate -> cacheResolvedArtwork(delegate, accessors) }
                })
            }
            runtime.hookRegistrar.installHook(notifyInitialMethod, before = { chain ->
                val delegate = chain.thisObject ?: return@installHook
                val key = continuityKey(delegate, accessors) ?: return@installHook
                val currentUrls = artworkUrls(delegate, accessors)
                if (currentUrls.isNotEmpty()) {
                    remember(key, currentUrls)
                    return@installHook
                }
                val cached = synchronized(cache) { cache[key] }
                val restoredUrls = selectInAppArtworkContinuityUrls(
                    currentUrls = currentUrls,
                    cachedUrls = cached?.urls,
                    cachedAtUptimeMillis = cached?.capturedAtUptimeMillis,
                    nowUptimeMillis = SystemClock.uptimeMillis(),
                    ttlMillis = CACHE_TTL_MS,
                ) ?: run {
                    if (cached != null) synchronized(cache) { cache.remove(key) }
                    return@installHook
                }
                if (restoredUrls.size == 1) {
                    accessors.setImageUrl.invoke(delegate, restoredUrls.single())
                } else {
                    accessors.setImageUrls.invoke(delegate, restoredUrls.toTypedArray())
                }
                if (BuildConfig.DEBUG) {
                    host.logMetadataIdentity(
                        event = "artwork_continuity_restored",
                        details = "contentId=${key.id}, persistentId=${key.persistentId}, " +
                            "contentType=${key.contentType}, urls=${restoredUrls.size}, " +
                            "urlHash=${restoredUrls.hashCode()}",
                    )
                }
            })
            ProviderLogger.info(
                "Apple Music 资料库封面连续性 Hook 已安装: " +
                    "resultMethods=${artworkResultMethods.size}, " +
                    "fallback=${resolvedDelegate.compatibilityFallback}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 资料库封面连续性 Hook 安装失败", it)
        }
    }

    private fun cacheResolvedArtwork(
        delegate: Any,
        accessors: InAppArtworkContinuityAccessors,
    ) {
        val urls = artworkUrls(delegate, accessors)
        if (urls.isEmpty()) return
        val liveData = runCatching { accessors.imageUrlsLiveData.get(delegate) }.getOrNull()
        continuityKey(delegate, accessors)?.let { key -> remember(key, urls) }
        host.onArtworkDelegateResolved(delegate, liveData, urls)
    }

    private fun remember(key: InAppArtworkContinuityKey, urls: List<String>) {
        synchronized(cache) {
            cache[key] = InAppArtworkContinuityEntry(
                urls = urls.toList(),
                capturedAtUptimeMillis = SystemClock.uptimeMillis(),
            )
        }
    }

    private fun continuityKey(
        delegate: Any,
        accessors: InAppArtworkContinuityAccessors,
    ): InAppArtworkContinuityKey? {
        val id = runCatching { accessors.getId.invoke(delegate)?.toString() }
            .getOrNull()?.trim().orEmpty()
        val persistentId = runCatching {
            (accessors.getPersistentId.invoke(delegate) as? Number)?.toLong()
        }.getOrNull() ?: 0L
        val contentType = runCatching {
            (accessors.getContentType.invoke(delegate) as? Number)?.toInt()
        }.getOrNull() ?: -1
        val artworkToken = runCatching {
            accessors.getArtworkToken.invoke(delegate)?.toString()
        }.getOrNull()?.trim().orEmpty()
        val artworkTokens = runCatching {
            @Suppress("UNCHECKED_CAST")
            (accessors.getAllArtworkTokens.invoke(delegate) as? Map<Any?, Any?>)
                .orEmpty()
                .entries
                .mapNotNull { (variant, token) ->
                    val normalizedToken = token?.toString()?.trim().orEmpty()
                    if (normalizedToken.isEmpty()) null else "$variant=$normalizedToken"
                }
                .sorted()
                .joinToString("|")
        }.getOrDefault("")
        if (id.isEmpty() && persistentId == 0L) return null
        if (artworkToken.isEmpty() && artworkTokens.isEmpty()) return null
        return InAppArtworkContinuityKey(
            id = id,
            persistentId = persistentId,
            contentType = contentType,
            artworkToken = artworkToken,
            artworkTokens = artworkTokens,
        )
    }

    private fun artworkUrls(
        delegate: Any,
        accessors: InAppArtworkContinuityAccessors,
    ): List<String> {
        val singleUrl = runCatching {
            accessors.getImageUrl.invoke(delegate)?.toString()
        }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)
        if (singleUrl != null) return listOf(singleUrl)
        return runCatching {
            (accessors.getImageUrls.invoke(delegate) as? Array<*>)
                .orEmpty()
                .mapNotNull { value -> value?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                .distinct()
        }.getOrDefault(emptyList())
    }
}
