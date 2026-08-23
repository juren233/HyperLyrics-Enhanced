/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import java.lang.reflect.Method
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds cache keys for runtime repair results separately from cross-version trusted baselines.
 *
 * Runtime results are scoped to the concrete Apple Music APK identity and the resolver/contract
 * schema. A module upgrade or APK replacement therefore cannot silently reuse a structurally
 * similar target that was accepted under older rules. Trusted baselines remain cross-version,
 * but are written only after an exact version profile resolves successfully.
 */
internal object AppleMusicDexKitCachePolicy {
    const val RESOLVER_SCHEMA_VERSION = 2
    const val CONTRACT_SCHEMA_VERSION = 2

    fun runtimeScope(
        versionCode: Long,
        lastUpdateTime: Long,
        sourceLength: Long,
        sourceLastModified: Long,
    ): String = buildString {
        append("resolver-")
        append(RESOLVER_SCHEMA_VERSION)
        append(":contract-")
        append(CONTRACT_SCHEMA_VERSION)
        append(':')
        append(versionCode)
        append(':')
        append(lastUpdateTime)
        append(':')
        append(sourceLength)
        append(':')
        append(sourceLastModified)
    }

    fun methodCacheKey(runtimeScope: String, hookPoint: AppleMusicHookPoint): String =
        "cache-method:$runtimeScope:${hookPoint.name}"

    fun classCacheKey(
        runtimeScope: String,
        hookPoint: AppleMusicHookPoint,
        templateClassName: String,
    ): String = "cache-class:$runtimeScope:${hookPoint.name}:${templateClassName.encodedKey()}"

    fun classReferenceCacheKey(
        runtimeScope: String,
        hookPoint: AppleMusicHookPoint,
        templateClassName: String,
        member: AppleMusicRuntimeMember,
    ): String =
        "cache-class-ref:$runtimeScope:${hookPoint.name}:" +
            "${templateClassName.encodedKey()}:${member.name}"

    fun memberCacheKey(
        runtimeScope: String,
        hookPoint: AppleMusicHookPoint,
        templateClassName: String,
        member: AppleMusicRuntimeMember,
    ): String =
        "cache-member:$runtimeScope:${hookPoint.name}:" +
            "${templateClassName.encodedKey()}:${member.name}"

    fun trustedClassBaselineKey(
        hookPoint: AppleMusicHookPoint,
        templateClassName: String,
    ): String = "trusted-baseline-class:v2:${hookPoint.name}:${templateClassName.encodedKey()}"

    fun trustedClassReferenceBaselineKey(
        hookPoint: AppleMusicHookPoint,
        templateClassName: String,
        member: AppleMusicRuntimeMember,
    ): String =
        "trusted-baseline-class-ref:v2:${hookPoint.name}:" +
            "${templateClassName.encodedKey()}:${member.name}"

    fun trustedMemberBaselineKey(
        hookPoint: AppleMusicHookPoint,
        templateClassName: String,
        member: AppleMusicRuntimeMember,
    ): String =
        "trusted-baseline-member:v2:${hookPoint.name}:" +
            "${templateClassName.encodedKey()}:${member.name}"

    fun trustedHookMethodBaselineKey(
        hookPoint: AppleMusicHookPoint,
        templateClassName: String,
    ): String =
        "trusted-baseline-hook-method:v2:${hookPoint.name}:${templateClassName.encodedKey()}"

    private fun String.encodedKey(): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(toByteArray(Charsets.UTF_8))

    fun selectUniqueBest(candidates: List<Pair<String, Int>>): String? {
        if (candidates.isEmpty()) return null
        val sorted = candidates.sortedByDescending { (_, score) -> score }
        val best = sorted.first()
        val second = sorted.getOrNull(1)
        return if (second == null || best.second > second.second) best.first else null
    }
}

/** Per-process quarantine for candidates rejected by the business Hook installation step. */
internal class AppleMusicDexKitRejectionRegistry {
    private val rejectedClassNames = ConcurrentHashMap<String, MutableSet<String>>()
    private val rejectedMethodNames = ConcurrentHashMap<String, MutableSet<String>>()

    fun reject(
        hookPoint: AppleMusicHookPoint,
        templateClassName: String,
        actualClassName: String,
    ): Boolean = rejectedClassNames
        .computeIfAbsent(key(hookPoint, templateClassName)) {
            ConcurrentHashMap.newKeySet()
        }
        .add(actualClassName)

    fun contains(
        hookPoint: AppleMusicHookPoint,
        templateClassName: String,
        actualClassName: String,
    ): Boolean = rejectedClassNames[key(hookPoint, templateClassName)]
        ?.contains(actualClassName) == true

    fun rejectMethod(
        hookPoint: AppleMusicHookPoint,
        templateClassName: String,
        method: Method,
    ): Boolean = rejectedMethodNames
        .computeIfAbsent(key(hookPoint, templateClassName)) {
            ConcurrentHashMap.newKeySet()
        }
        .add(method.toGenericString())

    fun containsMethod(
        hookPoint: AppleMusicHookPoint,
        templateClassName: String,
        method: Method,
    ): Boolean = rejectedMethodNames[key(hookPoint, templateClassName)]
        ?.contains(method.toGenericString()) == true

    private fun key(hookPoint: AppleMusicHookPoint, templateClassName: String): String =
        "${hookPoint.name}:$templateClassName"
}
