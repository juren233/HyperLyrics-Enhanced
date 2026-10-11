/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class AppleLyricsPreferenceBinding(
    val setter: Method,
    val read: () -> Boolean?,
)

internal data class AppleLyricsPreferenceBindings(
    val translation: AppleLyricsPreferenceBinding,
    val pronunciation: AppleLyricsPreferenceBinding,
)

/** DEX evidence, kept independent of Android/DexKit for adversarial unit tests. */
internal data class ApplePreferenceMethodEvidence(
    val method: Method,
    val reads: Set<Field>,
    val writes: Set<Field>,
    val invokes: Set<Method>,
)

/**
 * Resolves the typed DataStore preference family without any obfuscated names or historical
 * baseline. Call only AFTER Application.onCreate: reading static keys initializes their owner.
 * No setters, key toString(), or arbitrary host helper methods are executed during discovery.
 * The caller must first anchor the owner to BOTH exact key strings in its DEX initializer.
 */
internal object AppleLyricsPreferenceDiscovery {
    const val TRANSLATION_KEY = "key_player_lyrics_translation_selected"
    const val PRONUNCIATION_KEY = "key_player_lyrics_pronunciation_selected"
    val keys = setOf(TRANSLATION_KEY, PRONUNCIATION_KEY)

    fun resolve(owner: Class<*>, evidence: List<ApplePreferenceMethodEvidence>): AppleLyricsPreferenceBindings? {
        val keyFields = keys.associateWith { key ->
            owner.declaredFields.filter { field -> isKey(field, key) }.singleOrNull() ?: return null
        }
        val translation = resolvePreference(owner, keyFields.getValue(TRANSLATION_KEY), keyFields.values.toSet(), evidence)
            ?: return null
        val pronunciation = resolvePreference(owner, keyFields.getValue(PRONUNCIATION_KEY), keyFields.values.toSet(), evidence)
            ?: return null
        if (translation.setter == pronunciation.setter || translation.cache == pronunciation.cache ||
            translation.storeGetter != pronunciation.storeGetter || translation.storeReader != pronunciation.storeReader) return null
        return AppleLyricsPreferenceBindings(translation.binding(true), pronunciation.binding(false))
    }

    private fun isKey(field: Field, key: String): Boolean {
        if (!Modifier.isStatic(field.modifiers) || !Modifier.isFinal(field.modifiers) ||
            field.type.isPrimitive || field.type == String::class.java || field.type.isArray) return false
        // DataStore Key has a single instance String containing its name. Read that field
        // directly instead of calling user code such as toString or an obfuscated getter.
        val members = field.type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        val name = members.singleOrNull { it.type == String::class.java && Modifier.isFinal(it.modifiers) }
            ?: return false
        if (members.size > 4) return false
        return runCatching {
            val value = field.apply { isAccessible = true }.get(null) ?: return false
            name.apply { isAccessible = true }.get(value) == key
        }.getOrDefault(false)
    }

    private data class Resolved(
        val setter: Method,
        val key: Field,
        val cache: Field,
        val getter: Method?,
        val storeGetter: Method,
        val storeReader: Method,
    ) {
        fun binding(default: Boolean) = AppleLyricsPreferenceBinding(setter) {
            (cache.get(null) as? Boolean) ?: if (getter != null) {
                getter.invoke(null) as? Boolean
            } else {
                val store = storeGetter.invoke(null)
                storeReader.invoke(store, key.get(null), default) as? Boolean
            }
        }
    }

    private fun resolvePreference(
        owner: Class<*>, key: Field, allKeys: Set<Field>, evidence: List<ApplePreferenceMethodEvidence>,
    ): Resolved? {
        val setters = evidence.filter { e ->
            e.method.declaringClass == owner && Modifier.isStatic(e.method.modifiers) &&
                e.method.returnType == Void.TYPE && e.method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) &&
                e.reads.intersect(allKeys) == setOf(key)
        }
        // Ambiguity is rejected even if only one candidate happens to fit later shape checks.
        val setter = setters.singleOrNull() ?: return null
        val cache = setter.writes.singleOrNull {
            it.declaringClass == owner && Modifier.isStatic(it.modifiers) && it.type == java.lang.Boolean::class.java
        } ?: return null
        val storeGetters = setter.invokes.filter {
            it.declaringClass == owner && Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                !it.returnType.isPrimitive && it.returnType != java.lang.Boolean::class.java
        }
        val storeGetter = storeGetters.singleOrNull() ?: return null
        val storeWriter = setter.invokes.singleOrNull {
            !Modifier.isStatic(it.modifiers) && it.declaringClass.isAssignableFrom(storeGetter.returnType) &&
                it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(key.type, Any::class.java))
        } ?: return null
        val storeReader = storeWriter.declaringClass.declaredMethods.singleOrNull {
            !Modifier.isStatic(it.modifiers) && !it.isBridge && !it.isSynthetic && it.returnType == Any::class.java &&
                it.parameterTypes.contentEquals(arrayOf(key.type, Any::class.java))
        } ?: return null
        val getters = evidence.filter {
            it.method.declaringClass == owner && Modifier.isStatic(it.method.modifiers) &&
                it.method.parameterCount == 0 && it.method.returnType == Boolean::class.javaPrimitiveType && cache in it.reads
        }
        if (getters.size > 1) return null
        // The boolean cache belongs to this setter/getter pair, not another preference.
        if (evidence.any { cache in it.writes && it !== setter && it !in getters }) return null
        listOf(key, cache).forEach { it.isAccessible = true }
        listOfNotNull(setter.method, getters.singleOrNull()?.method, storeGetter, storeReader).forEach { it.isAccessible = true }
        return Resolved(setter.method, key, cache, getters.singleOrNull()?.method, storeGetter, storeReader)
    }
}

/** A failed optional preference hook must not suppress the other hook or the provider. */
internal fun installAppleLyricsPreferenceHooks(
    bindings: AppleLyricsPreferenceBindings,
    install: (Boolean, AppleLyricsPreferenceBinding) -> Unit,
    onFailure: (Boolean, Throwable) -> Unit,
) {
    listOf(false to bindings.translation, true to bindings.pronunciation).forEach { (pronunciation, binding) ->
        runCatching { install(pronunciation, binding) }.onFailure { onFailure(pronunciation, it) }
    }
}
