/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.common.IslandProgressColorMode
import com.juren233.hyperlyricsenhanced.common.RootConstants
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandRuntimePreferenceReaderTest {
    @After
    fun clearOverrides() {
        IslandRuntimePreferenceOverrides.clear()
    }

    @Test
    fun `runtime broadcast override wins over stored remote preference`() {
        val key = "cover_gradient"
        val prefs = BooleanPreferences(mapOf(key to false))
        IslandRuntimePreferenceOverrides.put(key, true)

        assertTrue(IslandRuntimePreferenceReader.getBoolean(prefs, key, false))
    }

    @Test
    fun `runtime integer override wins over stored custom color`() {
        val key = "custom_color"
        val prefs = BooleanPreferences(mapOf(key to 0xFF112233.toInt()))
        IslandRuntimePreferenceOverrides.put(key, 0xFF445566.toInt())

        org.junit.Assert.assertEquals(
            0xFF445566.toInt(),
            IslandRuntimePreferenceReader.getInt(prefs, key, 0xFFFFFFFF.toInt())
        )
    }

    @Test
    fun `stored remote preference is used without a runtime override`() {
        val key = "cover_gradient"
        val prefs = BooleanPreferences(mapOf(key to false))

        assertFalse(IslandRuntimePreferenceReader.getBoolean(prefs, key, true))
    }

    @Test
    fun `progress color mode uses live override before legacy preferences`() {
        val prefs = BooleanPreferences(
            mapOf(
                RootConstants.KEY_HOOK_ISLAND_PROGRESS_GLOW to true,
                RootConstants.KEY_HOOK_ISLAND_GLOW_EXTRACT_COLOR to true,
                RootConstants.KEY_HOOK_ISLAND_PROGRESS_GRADIENT to false,
            )
        )
        IslandRuntimePreferenceOverrides.put(
            RootConstants.KEY_HOOK_ISLAND_PROGRESS_COLOR_MODE,
            RootConstants.ISLAND_PROGRESS_COLOR_MODE_CUSTOM,
        )

        org.junit.Assert.assertEquals(
            RootConstants.ISLAND_PROGRESS_COLOR_MODE_CUSTOM,
            IslandRuntimePreferenceReader.getProgressColorMode(prefs)
        )
    }

    @Test
    fun `progress color mode falls back to equivalent legacy selection`() {
        val prefs = BooleanPreferences(
            mapOf(
                RootConstants.KEY_HOOK_ISLAND_PROGRESS_GLOW to true,
                RootConstants.KEY_HOOK_ISLAND_GLOW_EXTRACT_COLOR to true,
                RootConstants.KEY_HOOK_ISLAND_PROGRESS_GRADIENT to true,
            )
        )

        org.junit.Assert.assertEquals(
            RootConstants.ISLAND_PROGRESS_COLOR_MODE_COVER_GRADIENT,
            IslandRuntimePreferenceReader.getProgressColorMode(prefs)
        )
        org.junit.Assert.assertEquals(
            IslandProgressColorMode.UNSPECIFIED,
            IslandRuntimePreferenceReader.getInt(
                prefs,
                RootConstants.KEY_HOOK_ISLAND_PROGRESS_COLOR_MODE,
                IslandProgressColorMode.UNSPECIFIED,
            )
        )
    }

    private class BooleanPreferences(
        private val values: Map<String, Any>
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (values[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?
        ): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int =
            (values[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }
}
