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

    @Volatile
    private var debugBaselines: Map<String, ProviderPackManifest> = emptyMap()

    /**
     * [debugBaselines] 只在 Debug 核心传入：插件 id 到随调试包内置的基线包清单。
     * 目标进程在「已装版本 > 0 且基线更新」时直接加载基线包（不看 active file），
     * 这里按同一条件放行，否则基线包新增的目标 App（已装远程包的清单里还没有它，
     * 所以没有 active file 记录）在目标进程里能跑，SystemUI 仲裁却会当成「插件未启用」丢掉。
     */
    fun configure(
        preferences: SharedPreferences,
        debugBaselines: Map<String, ProviderPackManifest> = emptyMap(),
    ) {
        this.preferences = preferences
        this.debugBaselines = debugBaselines
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
        return preferenceState(currentPreferences, playerPackageName, debugBaselines)
    }

    fun affectedPlayerPackages(key: String?): Set<String> {
        if (key.isNullOrBlank()) return emptySet()
        return OfficialProviderCatalog.definitions.firstNotNullOfOrNull { definition ->
            val matchesPluginKey = key == OfficialProviderCatalog.enabledKey(definition.id) ||
                key == OfficialProviderCatalog.installedVersionKey(definition.id) ||
                key == OfficialProviderCatalog.removedKey(definition.id)
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
        debugBaselines: Map<String, ProviderPackManifest> = emptyMap(),
    ): Boolean? {
        val definition = OfficialProviderCatalog.definitionForPackage(playerPackageName)
            ?: return null
        val isAppleMusic = definition.id == OfficialProviderCatalog.APPLE_MUSIC_PLUGIN_ID
        val enabled = preferences.getBoolean(
            OfficialProviderCatalog.enabledKey(definition.id),
            isAppleMusic,
        )
        // Apple Music ships a baseline Pack inside the module APK, so it is configured
        // without an installed-version or active-file record; a user removal disables it.
        if (isAppleMusic) {
            return enabled && !preferences.getBoolean(
                OfficialProviderCatalog.removedKey(definition.id),
                false,
            )
        }
        val installedVersion = preferences.getInt(
            OfficialProviderCatalog.installedVersionKey(definition.id),
            0,
        )
        val activeFile = preferences.getString(
            OfficialProviderCatalog.activeFileKey(playerPackageName),
            null,
        )?.takeIf(String::isNotBlank) ?: debugBaselines[definition.id]
            ?.takeIf { baseline ->
                isDebugBaselineActive(
                    baselineVersionCode = baseline.versionCode,
                    baselineTargets = baseline.targetPackages,
                    installedVersion = installedVersion,
                    playerPackageName = playerPackageName,
                )
            }
            ?.let { DEBUG_BASELINE_ACTIVE_FILE }
        return isConfigured(
            enabled = enabled,
            installedVersion = installedVersion,
            activeFile = activeFile,
        )
    }

    /** 与目标进程选择基线包的条件一致：已装过远程包、基线版本更新、基线清单含该 App。 */
    internal fun isDebugBaselineActive(
        baselineVersionCode: Int,
        baselineTargets: List<String>,
        installedVersion: Int,
        playerPackageName: String,
    ): Boolean = installedVersion > 0 &&
        baselineVersionCode > installedVersion &&
        playerPackageName in baselineTargets

    private const val DEBUG_BASELINE_ACTIVE_FILE = "debug-baseline"

    internal fun isConfigured(
        enabled: Boolean,
        installedVersion: Int,
        activeFile: String?,
    ): Boolean = enabled && installedVersion > 0 && !activeFile.isNullOrBlank()
}
