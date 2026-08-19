/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online

import com.juren233.hyperlyricsenhanced.online.model.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineTranslationSourcePreferencesTest {
    @Test
    fun `default order puts NetEase first`() {
        assertEquals(
            listOf(Source.NE, Source.QM, Source.KUWO, Source.KUGOU),
            OnlineTranslationSourcePreferences.defaultOrder,
        )
    }

    @Test
    fun `automatic best source selection is enabled by default`() {
        assertEquals(
            true,
            OnlineTranslationSourcePreferences.isAutoSelectBestSourceEnabled(null),
        )
    }

    @Test
    fun `automatic selection ignores stored priority order`() {
        assertEquals(
            OnlineTranslationSourcePreferences.defaultOrder,
            OnlineTranslationSourcePreferences.resolveOrder(
                rawOrder = "KUGOU,KUWO,QM,NE",
                automaticSelection = true,
            ),
        )
    }

    @Test
    fun `manual selection restores stored priority order`() {
        assertEquals(
            listOf(Source.KUGOU, Source.KUWO, Source.QM, Source.NE),
            OnlineTranslationSourcePreferences.resolveOrder(
                rawOrder = "KUGOU,KUWO,QM,NE",
                automaticSelection = false,
            ),
        )
    }

    @Test
    fun `NetEase and QQ are enabled by default while added sources are opt in`() {
        assertEquals(true, OnlineTranslationSourcePreferences.sourceDefaultEnabled(Source.NE))
        assertEquals(true, OnlineTranslationSourcePreferences.sourceDefaultEnabled(Source.QM))
        assertEquals(false, OnlineTranslationSourcePreferences.sourceDefaultEnabled(Source.KUWO))
        assertEquals(false, OnlineTranslationSourcePreferences.sourceDefaultEnabled(Source.KUGOU))
    }

    @Test
    fun `target apps keep explicit opt in defaults`() {
        assertEquals(
            false,
            OnlineTranslationSourcePreferences.appDefaultEnabled(
                OnlineTranslationSourcePreferences.APPLE_MUSIC_PACKAGE,
            ),
        )
        assertEquals(
            false,
            OnlineTranslationSourcePreferences.appDefaultEnabled(
                OnlineTranslationSourcePreferences.QISHUI_PACKAGE,
            ),
        )
        assertEquals(
            false,
            OnlineTranslationSourcePreferences.appDefaultEnabled(
                OnlineTranslationSourcePreferences.SPOTIFY_PACKAGE,
            ),
        )
    }

    @Test
    fun `stored order is deduplicated and missing sources are appended`() {
        assertEquals(
            listOf(Source.KUGOU, Source.NE, Source.QM, Source.KUWO),
            OnlineTranslationSourcePreferences.normalizeOrder("KUGOU,NE,KUGOU"),
        )
    }

    @Test
    fun `unknown source values do not corrupt the configured order`() {
        assertEquals(
            listOf(Source.QM, Source.NE, Source.KUWO, Source.KUGOU),
            OnlineTranslationSourcePreferences.normalizeOrder("unknown,QM"),
        )
    }

    @Test
    fun `orderedSources defaults to enabled sources only`() {
        assertEquals(
            listOf(Source.NE, Source.QM),
            OnlineTranslationSourcePreferences.orderedSources(null),
        )
    }

    @Test
    fun `orderedSources filters disabled sources and respects custom order`() {
        val prefs = TestSharedPreferences(
            mapOf(
                com.juren233.hyperlyricsenhanced.common.RootConstants
                    .KEY_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE to false,
                com.juren233.hyperlyricsenhanced.common.RootConstants
                    .KEY_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER to "KUGOU,KUWO,QM,NE",
                OnlineTranslationSourcePreferences.sourcePreferenceKey(Source.NE) to false,
                OnlineTranslationSourcePreferences.sourcePreferenceKey(Source.QM) to true,
                OnlineTranslationSourcePreferences.sourcePreferenceKey(Source.KUWO) to false,
                OnlineTranslationSourcePreferences.sourcePreferenceKey(Source.KUGOU) to true,
            )
        )
        assertEquals(
            listOf(Source.KUGOU, Source.QM),
            OnlineTranslationSourcePreferences.orderedSources(prefs),
        )
    }

    @Test
    fun `orderedSources returns empty list when all sources are disabled`() {
        val prefs = TestSharedPreferences(
            mapOf(
                OnlineTranslationSourcePreferences.sourcePreferenceKey(Source.NE) to false,
                OnlineTranslationSourcePreferences.sourcePreferenceKey(Source.QM) to false,
                OnlineTranslationSourcePreferences.sourcePreferenceKey(Source.KUWO) to false,
                OnlineTranslationSourcePreferences.sourcePreferenceKey(Source.KUGOU) to false,
            )
        )
        assertEquals(
            emptyList<Source>(),
            OnlineTranslationSourcePreferences.orderedSources(prefs),
        )
    }

    private class TestSharedPreferences(
        private val values: Map<String, Any?>
    ) : android.content.SharedPreferences {
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? =
            (values[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int =
            (values[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long =
            (values[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float =
            (values[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (values[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor =
            throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(
            listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?
        ) {}
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?
        ) {}
    }
}
