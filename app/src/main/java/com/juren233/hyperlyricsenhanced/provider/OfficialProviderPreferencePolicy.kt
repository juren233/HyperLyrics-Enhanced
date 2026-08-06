/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.content.SharedPreferences

/**
 * Runtime preference view shared by the embedded Central and official Provider loader.
 *
 * An official Provider is preferred only when it is both installed and enabled and the
 * target player has an active Pack file. Installed legacy APKs are intentionally ignored.
 */
internal object OfficialProviderPreferencePolicy {
    @Volatile
    private var preferences: SharedPreferences? = null

    fun configure(preferences: SharedPreferences) {
        this.preferences = preferences
    }

    fun isOfficialProviderPreferred(playerPackageName: String): Boolean {
        return preferenceState(playerPackageName) == true
    }

    /**
     * Returns null until the SystemUI runtime has attached its Remote Preferences view.
     * This preserves the previous source-priority behavior during early bootstrap instead
     * of accidentally treating an unavailable preference store as "Pack disabled".
     */
    fun preferenceState(playerPackageName: String): Boolean? {
        val currentPreferences = preferences ?: return null
        return preferenceState(currentPreferences, playerPackageName)
    }

    fun affectedPlayerPackages(key: String?): Set<String> {
        if (key.isNullOrBlank()) return emptySet()
        return OfficialProviderCatalog.definitions.firstNotNullOfOrNull { definition ->
            val matchesPluginKey = key == OfficialProviderCatalog.enabledKey(definition.id) ||
                key == OfficialProviderCatalog.installedVersionKey(definition.id)
            val matchesPlayerKey = definition.targetPackages.any { packageName ->
                key == OfficialProviderCatalog.activeFileKey(packageName)
            }
            if (matchesPluginKey || matchesPlayerKey) definition.targetPackages else null
        }.orEmpty()
    }

    internal fun isOfficialProviderPreferred(
        preferences: SharedPreferences,
        playerPackageName: String,
    ): Boolean = preferenceState(preferences, playerPackageName) == true

    internal fun preferenceState(
        preferences: SharedPreferences,
        playerPackageName: String,
    ): Boolean? {
        val definition = OfficialProviderCatalog.definitionForPackage(playerPackageName)
            ?: return null
        val enabled = preferences.getBoolean(
            OfficialProviderCatalog.enabledKey(definition.id),
            false,
        )
        val installedVersion = preferences.getInt(
            OfficialProviderCatalog.installedVersionKey(definition.id),
            0,
        )
        val activeFile = preferences.getString(
            OfficialProviderCatalog.activeFileKey(playerPackageName),
            null,
        )
        return isConfigured(
            enabled = enabled,
            installedVersion = installedVersion,
            activeFile = activeFile,
        )
    }

    internal fun isConfigured(
        enabled: Boolean,
        installedVersion: Int,
        activeFile: String?,
    ): Boolean = enabled && installedVersion > 0 && !activeFile.isNullOrBlank()
}
