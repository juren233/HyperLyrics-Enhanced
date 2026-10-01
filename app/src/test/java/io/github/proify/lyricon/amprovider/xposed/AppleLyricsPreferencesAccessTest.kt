/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Test

class AppleLyricsPreferencesAccessTest {
    @Test
    fun `legacy static getters retain false translation and true pronunciation`() {
        val target = AppleMusicHookTarget(
            LegacyPreferences::class.java.name,
            runtimeMemberNames = mapOf(
                AppleMusicRuntimeMember.LYRICS_PREFERENCES_TRANSLATION_GETTER to "translation",
                AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_GETTER to "pronunciation",
            ),
        )
        assertEquals(false, readAppleLyricsPreference(LegacyPreferences::class.java, target, false))
        assertEquals(true, readAppleLyricsPreference(LegacyPreferences::class.java, target, true))
    }

    @Test
    fun `typed store supplies cold pronunciation and cached false wins after toggling`() {
        val target = AppleMusicHookTarget(
            TypedPreferences::class.java.name,
            runtimeMemberNames = mapOf(
                AppleMusicRuntimeMember.LYRICS_PREFERENCES_TRANSLATION_GETTER to "translation",
                AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_CACHE_FIELD to "cached",
                AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_KEY_FIELD to "key",
                AppleMusicRuntimeMember.LYRICS_PREFERENCES_STORE_GETTER to "store",
                AppleMusicRuntimeMember.LYRICS_PREFERENCES_STORE_READ_METHOD to "read",
            ),
        )
        TypedPreferences.cached = null
        TypedPreferences.reads = 0
        val snapshot = AtomicReference<Boolean?>()
        assertEquals(true, readAppleLyricsPreference(TypedPreferences::class.java, target, true, snapshot))
        assertEquals(1, TypedPreferences.reads)
        assertEquals(true, readAppleLyricsPreference(TypedPreferences::class.java, target, true, snapshot))
        assertEquals(1, TypedPreferences.reads)
        TypedPreferences.cached = false
        assertEquals(false, readAppleLyricsPreference(TypedPreferences::class.java, target, true, snapshot))
        assertEquals(1, TypedPreferences.reads)
        assertEquals(false, readAppleLyricsPreference(TypedPreferences::class.java, target, false))
    }

    class LegacyPreferences {
        companion object {
            @JvmStatic fun translation() = false
            @JvmStatic fun pronunciation() = true
        }
    }

    class TypedPreferences {
        companion object {
            @JvmField var cached: Boolean? = null
            @JvmField val key = "pronunciation"
            @JvmField var reads = 0
            @JvmStatic fun translation() = false
            @JvmStatic fun store() = TypedStore()
        }
    }

    class TypedStore {
        fun read(key: String, default: Any): Any {
            TypedPreferences.reads++
            return if (key == TypedPreferences.key) true else default
        }
    }
}
