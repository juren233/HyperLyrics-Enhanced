/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldUsingType

/** A cached owner is only a search hint; revalidate anchors and every consumer on reuse. */
internal fun discoverAppleLyricsPreferences(
    bridge: DexKitBridge,
    classLoader: ClassLoader,
    cachedOwner: String?,
): Pair<String, AppleLyricsPreferenceBindings>? {
    fun anchored(owner: ClassData) = owner.methods.any {
        it.isStaticInitializer && it.usingStrings.containsAll(AppleLyricsPreferenceDiscovery.keys)
    }
    fun resolve(owner: ClassData): Pair<String, AppleLyricsPreferenceBindings>? {
        if (!anchored(owner)) return null
        val clazz = owner.getInstance(classLoader)
        val evidence = owner.methods.filter { it.isMethod && !it.isStaticInitializer && !it.isConstructor }.map { data ->
            val fields = data.usingFields
            ApplePreferenceMethodEvidence(
                data.getMethodInstance(classLoader),
                fields.filter { it.usingType == FieldUsingType.Read }.map { it.field.getFieldInstance(classLoader) }.toSet(),
                fields.filter { it.usingType == FieldUsingType.Write }.map { it.field.getFieldInstance(classLoader) }.toSet(),
                data.invokes.filter { it.isMethod }.map { it.getMethodInstance(classLoader) }.toSet(),
            )
        }
        return AppleLyricsPreferenceDiscovery.resolve(clazz, evidence)?.let { clazz.name to it }
    }
    if (cachedOwner != null) {
        val cached = runCatching { bridge.getClassData(cachedOwner)?.let { owner -> resolve(owner) } }.getOrNull()
        if (cached != null) return cached
    }
    val owners = bridge.findMethod(FindMethod().apply {
        matcher { name("<clinit>"); usingStrings(AppleLyricsPreferenceDiscovery.keys.toList()) }
    }).mapNotNull { it.declaredClass }.distinctBy { it.name }.filter(::anchored)
    // Do not initialize or choose among competing managers, even if one would validate.
    return owners.singleOrNull()?.let(::resolve)
}
