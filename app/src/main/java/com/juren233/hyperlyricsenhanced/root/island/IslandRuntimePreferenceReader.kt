/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.common.IslandMusicWaveColorMode
import com.juren233.hyperlyricsenhanced.common.IslandFontWeightMode
import com.juren233.hyperlyricsenhanced.common.IslandProgressColorMode
import com.juren233.hyperlyricsenhanced.common.RootConstants

/** Reads the latest broadcast override before falling back to RemotePreferences storage. */
internal object IslandRuntimePreferenceReader {
    fun getFontWeightMode(prefs: SharedPreferences): Int = IslandFontWeightMode.resolve(
        getInt(prefs, RootConstants.KEY_HOOK_FONT_WEIGHT_MODE, IslandFontWeightMode.UNSPECIFIED),
        IslandFontWeightMode.hasLegacySettings(prefs) || contains(prefs, RootConstants.KEY_HOOK_FONT_WEIGHT),
    )

    fun getInt(
        prefs: SharedPreferences,
        key: String,
        default: Int,
    ): Int = IslandRuntimePreferenceOverrides.getInt(
        key,
        prefs.getInt(key, default),
    )

    fun getBoolean(
        prefs: SharedPreferences,
        key: String,
        default: Boolean,
    ): Boolean = IslandRuntimePreferenceOverrides.getBoolean(
        key,
        prefs.getBoolean(key, default),
    )

    fun getStringSet(
        prefs: SharedPreferences,
        key: String,
        default: Set<String>? = null,
    ): Set<String>? = IslandRuntimePreferenceOverrides.getStringSet(
        key,
        prefs.getStringSet(key, default),
    )

    fun getProgressColorMode(prefs: SharedPreferences): Int = IslandProgressColorMode.resolve(
        storedMode = getInt(
            prefs,
            RootConstants.KEY_HOOK_ISLAND_PROGRESS_COLOR_MODE,
            IslandProgressColorMode.UNSPECIFIED,
        ),
        legacyProgressEnabled = getBoolean(
            prefs,
            RootConstants.KEY_HOOK_ISLAND_PROGRESS_GLOW,
            RootConstants.DEFAULT_HOOK_ISLAND_PROGRESS_GLOW,
        ),
        legacyCoverEnabled = getBoolean(
            prefs,
            RootConstants.KEY_HOOK_ISLAND_GLOW_EXTRACT_COLOR,
            RootConstants.DEFAULT_HOOK_ISLAND_GLOW_EXTRACT_COLOR,
        ),
        legacyCoverGradient = getBoolean(
            prefs,
            RootConstants.KEY_HOOK_ISLAND_PROGRESS_GRADIENT,
            RootConstants.DEFAULT_HOOK_ISLAND_PROGRESS_GRADIENT,
        ),
    )

    fun contains(
        prefs: SharedPreferences,
        key: String,
    ): Boolean = IslandRuntimePreferenceOverrides.contains(key) || prefs.contains(key)

    fun getMusicWaveColorMode(prefs: SharedPreferences): Int = IslandMusicWaveColorMode.resolve(
        storedMode = getInt(
            prefs,
            RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR_MODE,
            IslandMusicWaveColorMode.UNSPECIFIED,
        ),
        hasLegacyCoverPreference = contains(
            prefs,
            RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR,
        ),
        legacyCoverEnabled = getBoolean(
            prefs,
            RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR,
            RootConstants.DEFAULT_HOOK_ISLAND_MUSIC_WAVE_COLOR,
        ),
        legacyCoverGradient = getBoolean(
            prefs,
            RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_GRADIENT,
            RootConstants.DEFAULT_HOOK_ISLAND_MUSIC_WAVE_GRADIENT,
        ),
    )
}
