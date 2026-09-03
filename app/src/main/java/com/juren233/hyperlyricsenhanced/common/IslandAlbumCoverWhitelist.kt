/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences

object IslandAlbumCoverWhitelist {
    fun readEnabledPackages(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE_APP_WHITELIST, null)
            ?.toSet()
            ?: IslandMusicAppCatalog.packageNames

    fun isEnabled(
        enabledPackages: Set<String>?,
        packageName: String?,
        supportedPackages: Set<String> = IslandMusicAppCatalog.packageNames,
    ): Boolean {
        val targetPackage = packageName ?: return false
        // Before the user has saved a whitelist, preserve the original behavior:
        // recognized music apps are enabled and every other app uses the default style.
        if (enabledPackages == null) return targetPackage in supportedPackages
        // Once a whitelist exists, it is an explicit package set and may include any
        // installed media app, not only packages known to the built-in catalog.
        return targetPackage in enabledPackages
    }

    fun updateEnabledPackages(
        current: Set<String>?,
        packageName: String,
        enabled: Boolean,
        supportedPackages: Set<String> = IslandMusicAppCatalog.packageNames,
    ): Set<String> {
        val updated = (current ?: supportedPackages).toMutableSet()
        if (enabled) {
            updated += packageName
        } else {
            updated -= packageName
        }
        return updated
    }
}
