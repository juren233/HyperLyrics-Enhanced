/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.common.MediaSessionOnlineFallbackPreferences
import com.juren233.hyperlyricsenhanced.contract.AppleMusicPreferenceKeys
import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
import com.juren233.hyperlyricsenhanced.root.island.IslandRuntimePreferenceOverrides
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OnlineEnrichmentAppGateTest {
    @Before
    fun setUp() = IslandRuntimePreferenceOverrides.clear()

    @After
    fun tearDown() = IslandRuntimePreferenceOverrides.clear()

    @Test
    fun `plugin app is enriched only when enabled in more apps`() {
        val prefs = prefs(MediaSessionOnlineFallbackPreferences.PACKAGES to mutableSetOf(KUGOU_LITE))

        assertTrue(OnlineEnrichmentAppGate.isEnabled(prefs, KUGOU_LITE))
        assertFalse(OnlineEnrichmentAppGate.isEnabled(prefs, KUWO))
        assertFalse(OnlineEnrichmentAppGate.isEnabled(null, KUGOU_LITE))
    }

    @Test
    fun `apps with their own switch ignore the more apps list`() {
        val listed = prefs(
            MediaSessionOnlineFallbackPreferences.PACKAGES to mutableSetOf(
                OnlineTranslationSourcePreferences.SALT_PACKAGE,
                OnlineTranslationSourcePreferences.APPLE_MUSIC_PACKAGE,
            ),
        )
        assertFalse(OnlineEnrichmentAppGate.isEnabled(listed, OnlineTranslationSourcePreferences.SALT_PACKAGE))
        assertFalse(OnlineEnrichmentAppGate.isEnabled(listed, OnlineTranslationSourcePreferences.APPLE_MUSIC_PACKAGE))

        val switchedOn = prefs(AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_APP_SALT to true)
        assertTrue(OnlineEnrichmentAppGate.isEnabled(switchedOn, OnlineTranslationSourcePreferences.SALT_PACKAGE))
    }

    @Test
    fun `NetEase follows its own switch, not the more apps list`() {
        val netease = OnlineTranslationSourcePreferences.NETEASE_PACKAGE
        val listed = prefs(MediaSessionOnlineFallbackPreferences.PACKAGES to mutableSetOf(netease))
        assertFalse(OnlineEnrichmentAppGate.isEnabled(listed, netease))

        val switchedOn = prefs(AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_APP_NETEASE to true)
        assertTrue(OnlineEnrichmentAppGate.isEnabled(switchedOn, netease))
    }

    @Test
    fun `runtime override of the more apps list wins over stored value`() {
        val prefs = prefs(MediaSessionOnlineFallbackPreferences.PACKAGES to mutableSetOf(KUGOU_LITE))
        IslandRuntimePreferenceOverrides.put(MediaSessionOnlineFallbackPreferences.PACKAGES, setOf(KUWO))

        assertEquals(setOf(KUWO), OnlineEnrichmentAppGate.moreAppsPackages(prefs))
        assertFalse(OnlineEnrichmentAppGate.isEnabled(prefs, KUGOU_LITE))
        assertTrue(OnlineEnrichmentAppGate.isEnabled(prefs, KUWO))
    }

    @Test
    fun `more apps list stored as legacy comma string is still read`() {
        val prefs = prefs(MediaSessionOnlineFallbackPreferences.PACKAGES to "$KUGOU_LITE, $KUWO")

        assertEquals(setOf(KUGOU_LITE, KUWO), OnlineEnrichmentAppGate.moreAppsPackages(prefs))
    }

    private fun prefs(vararg values: Pair<String, Any>): SharedPreferences = MapPreferences(mapOf(*values))

    private class MapPreferences(private val values: Map<String, Any?>) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = (values[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (values[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (values[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (values[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (values[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    private companion object {
        const val KUGOU_LITE = "com.kugou.android.lite"
        const val KUWO = "cn.kuwo.player"
    }
}
