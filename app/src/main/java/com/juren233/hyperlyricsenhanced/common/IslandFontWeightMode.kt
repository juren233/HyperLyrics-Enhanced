/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences

object IslandFontWeightMode {
    const val UNSPECIFIED = -1
    const val SYSTEM = 0
    const val CUSTOM = 1

    fun resolve(storedMode: Int, hasLegacySettings: Boolean): Int = when (storedMode) {
        SYSTEM, CUSTOM -> storedMode
        UNSPECIFIED -> if (hasLegacySettings) CUSTOM else SYSTEM
        else -> SYSTEM
    }

    fun hasLegacySettings(prefs: SharedPreferences): Boolean =
        prefs.contains(RootConstants.KEY_HOOK_FONT_WEIGHT) ||
            prefs.contains(UIConstants.KEY_SETUP_COMPLETED) ||
            prefs.contains(UIConstants.KEY_LAST_SEEN_VERSION) ||
            prefs.contains(UIConstants.KEY_FEATURE_ENTRY_HYPER_ISLAND)

    fun read(prefs: SharedPreferences): Int = resolve(
        prefs.getInt(RootConstants.KEY_HOOK_FONT_WEIGHT_MODE, UNSPECIFIED),
        hasLegacySettings(prefs),
    )

    /** Run before first-launch defaults are written, so upgrades also retain the old 600 default. */
    fun initialize(prefs: SharedPreferences) {
        if (prefs.contains(RootConstants.KEY_HOOK_FONT_WEIGHT_MODE)) return
        prefs.edit().putInt(
            RootConstants.KEY_HOOK_FONT_WEIGHT_MODE,
            resolve(UNSPECIFIED, prefs.all.isNotEmpty()),
        ).apply()
    }
}
