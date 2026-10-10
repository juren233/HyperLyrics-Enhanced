/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

class AppleLyricsPreferenceDiscoveryTest {

    private class FakeKey(val name: String)

    private class FakeStore {
        private val values = mutableMapOf<FakeKey, Any?>()

        fun read(key: FakeKey, default: Any): Any? = values[key] ?: default

        fun write(key: FakeKey, value: Any): Unit {
            values[key] = value
        }
    }

    private class Fixture {
        companion object {
            @JvmField val translationKey = FakeKey(AppleLyricsPreferenceDiscovery.TRANSLATION_KEY)
            @JvmField val pronunciationKey = FakeKey(AppleLyricsPreferenceDiscovery.PRONUNCIATION_KEY)
            @JvmField var translationCache: Boolean? = null
            @JvmField var pronunciationCache: Boolean? = null
            private val preferenceStore = FakeStore()

            @JvmStatic fun store(): FakeStore = preferenceStore
            @JvmStatic fun setTranslation(value: Boolean) {
                translationCache = value
                store().write(translationKey, value)
            }
            @JvmStatic fun setPronunciation(value: Boolean) {
                pronunciationCache = value
                store().write(pronunciationKey, value)
            }
            @JvmStatic fun readTranslation(): Boolean = translationCache ?: false
            @JvmStatic fun readPronunciation(): Boolean = pronunciationCache ?: false
        }
    }

    @Test
    fun resolvesKeyAnchoredSettersAndReadersWithoutObfuscatedNames() {
        val owner = Fixture::class.java
        fun evidence(name: String, cache: String, key: String): ApplePreferenceMethodEvidence {
            val setter = owner.getDeclaredMethod(name, Boolean::class.javaPrimitiveType)
            val store = owner.getDeclaredMethod("store")
            val write = FakeStore::class.java.getDeclaredMethod("write", FakeKey::class.java, Any::class.java)
            return ApplePreferenceMethodEvidence(
                setter,
                setOf(owner.getDeclaredField(key)),
                setOf(owner.getDeclaredField(cache)),
                setOf(store, write),
            )
        }
        fun reader(name: String, cache: String): ApplePreferenceMethodEvidence {
            val method = owner.getDeclaredMethod(name)
            return ApplePreferenceMethodEvidence(method, setOf(owner.getDeclaredField(cache)), emptySet(), emptySet())
        }
        val bindings = AppleLyricsPreferenceDiscovery.resolve(
            owner,
            listOf(
                evidence("setTranslation", "translationCache", "translationKey"),
                evidence("setPronunciation", "pronunciationCache", "pronunciationKey"),
                reader("readTranslation", "translationCache"),
                reader("readPronunciation", "pronunciationCache"),
            ),
        )

        assertNotNull(bindings)
        assertEquals("setTranslation", bindings!!.translation.setter.name)
        assertEquals("setPronunciation", bindings.pronunciation.setter.name)
        bindings.translation.setter.invoke(null, true)
        bindings.pronunciation.setter.invoke(null, true)
        assertEquals(true, bindings.translation.read())
        assertEquals(true, bindings.pronunciation.read())
    }
    @Test
    fun `preference hook installation isolates each setter failure`() {
        val calls = mutableListOf<Boolean>()
        val first = method("first")
        val second = method("second")
        val bindings = AppleLyricsPreferenceBindings(
            AppleLyricsPreferenceBinding(first) { false },
            AppleLyricsPreferenceBinding(second) { true },
        )

        installAppleLyricsPreferenceHooks(
            bindings,
            install = { pronunciation, _ ->
                calls += pronunciation
                if (!pronunciation) error("one broken target")
            },
            onFailure = { pronunciation, _ -> assertEquals(false, pronunciation) },
        )

        assertEquals(listOf(false, true), calls)
    }

    @Test
    fun `preference key anchors are stable and distinct`() {
        assertTrue(AppleLyricsPreferenceDiscovery.keys.contains(
            AppleLyricsPreferenceDiscovery.TRANSLATION_KEY,
        ))
        assertTrue(AppleLyricsPreferenceDiscovery.keys.contains(
            AppleLyricsPreferenceDiscovery.PRONUNCIATION_KEY,
        ))
        assertEquals(2, AppleLyricsPreferenceDiscovery.keys.size)
    }

    private fun method(name: String): Method =
        AppleLyricsPreferenceDiscoveryTest::class.java.getDeclaredMethod(name)

    @Suppress("unused")
    private fun first() = Unit

    @Suppress("unused")
    private fun second() = Unit
}
