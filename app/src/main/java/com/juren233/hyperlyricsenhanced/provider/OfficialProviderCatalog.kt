/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

object OfficialProviderCatalog {
    const val PLUGIN_API_VERSION = 3

    /**
     * 主程序按范围校验插件 API 版本：Host 接口只加带默认实现的新方法时不升
     * PLUGIN_API_VERSION（只提高插件的 minCoreVersionCode），因此旧 API 的
     * 插件在新版主程序上仍然可用，精确等值校验会让它们被误判为不兼容。
     */
    const val PLUGIN_API_MIN_SUPPORTED = 3
    val PLUGIN_API_SUPPORTED_RANGE: IntRange get() = PLUGIN_API_MIN_SUPPORTED..PLUGIN_API_VERSION
    const val CORE_PACKAGE_NAME = "com.juren233.hyperlyricsenhanced"
    const val APPLE_MUSIC_PACKAGE_NAME = "com.apple.android.music"
    const val APPLE_MUSIC_PLUGIN_ID = "applemusic"
    const val SALT_PLAYER_PACKAGE_NAME = "com.salt.music"
    const val OFFICIAL_PROVIDER_PACKAGE_PREFIX =
        "com.juren233.hyperlyricsenhanced.provider."

    data class Definition(
        val id: String,
        val displayName: String,
        val targetPackages: Set<String>,
        val secondaryProcesses: Set<String> = emptySet(),
        val description: String? = null,
        val targetDisplayNames: Map<String, String> = emptyMap(),
        val systemMediaRuntime: Boolean = false,
        val supportsNextTrackPreview: Boolean = true,
        val showInDownloadList: Boolean = true,
    ) {
        fun displayNameForPackage(packageName: String): String =
            targetDisplayNames[packageName] ?: displayName
    }

    // 插件列表按此顺序展示，Apple Music 置顶。
    val definitions = listOf(
        Definition(
            id = "applemusic",
            displayName = "Apple Music",
            targetPackages = setOf(APPLE_MUSIC_PACKAGE_NAME),
        ),
        Definition("netease", "网易云音乐", setOf("com.netease.cloudmusic", "com.hihonor.cloudmusic")),
        Definition(
            id = "qqmusic",
            displayName = "QQ音乐 & 小米音乐",
            targetPackages = setOf(
                "com.tencent.qqmusic",
                "com.tencent.qqmusicpad",
                "com.miui.player",
            ),
            secondaryProcesses = setOf(
                "com.tencent.qqmusic:QQPlayerService",
                "com.miui.player:remote",
            ),
            targetDisplayNames = mapOf(
                "com.tencent.qqmusic" to "QQ音乐",
                "com.tencent.qqmusicpad" to "QQ音乐HD",
                "com.miui.player" to "小米音乐",
            ),
        ),
        Definition(
            id = "kugou",
            displayName = "酷狗音乐",
            targetPackages = setOf("com.kugou.android", "com.kugou.android.lite"),
            secondaryProcesses = setOf(
                "com.kugou.android.support",
                "com.kugou.android.lite.support",
            ),
            targetDisplayNames = mapOf(
                "com.kugou.android.lite" to "酷狗概念版",
            ),
        ),
        Definition(
            id = "kuwo",
            displayName = "酷我音乐 & 波点音乐",
            targetPackages = setOf("cn.kuwo.player", "cn.wenyu.bodian"),
            secondaryProcesses = setOf("cn.wenyu.bodian:service"),
            targetDisplayNames = mapOf(
                "cn.kuwo.player" to "酷我音乐",
                "cn.wenyu.bodian" to "波点音乐",
            ),
        ),
        Definition("spotify", "Spotify", setOf("com.spotify.music")),
        Definition(
            "lxmusic",
            "LX音乐",
            setOf(
                "cn.toside.music.mobile",
                "com.ikunshare.music.mobile",
                "com.lxnetease.music.mobile",
            ),
            showInDownloadList = false,
        ),
        Definition(
            "poweramp",
            "Poweramp",
            setOf("com.maxmpz.audioplayer"),
            showInDownloadList = false,
        ),
        Definition(
            id = "salt-player",
            displayName = "椒盐音乐",
            targetPackages = setOf("com.salt.music"),
        ),
        Definition(
            id = "qishui",
            displayName = "汽水音乐",
            targetPackages = setOf("com.luna.music"),
            systemMediaRuntime = true,
            supportsNextTrackPreview = false,
        ),
        Definition(
            "musicfree",
            "MusicFree",
            setOf("fun.upup.musicfree"),
            showInDownloadList = false,
        ),
        Definition(
            "gramophone",
            "Gramophone",
            setOf("org.akanework.gramophone"),
            showInDownloadList = false,
        ),
        Definition(
            "symfonium",
            "Symfonium",
            setOf("app.symfonik.music.player"),
            showInDownloadList = false,
        ),
    )

    private val definitionsByPackage = buildMap {
        definitions.forEach { definition ->
            definition.targetPackages.forEach { put(it, definition) }
        }
    }

    fun definitionForPackage(packageName: String): Definition? =
        definitionsByPackage[packageName]

    fun definitionForId(id: String): Definition? =
        definitions.firstOrNull { it.id == id }

    fun shouldShowInDownloadList(pluginId: String): Boolean =
        definitionForId(pluginId)?.showInDownloadList == true

    fun isOfficialProviderPair(
        providerPackageName: String,
        playerPackageName: String,
    ): Boolean {
        val pluginId = providerPackageName.removePrefix(OFFICIAL_PROVIDER_PACKAGE_PREFIX)
        if (pluginId == providerPackageName) return false
        return definitionForId(pluginId)?.targetPackages?.contains(playerPackageName) == true
    }

    /**
     * 目标包名与插件的兼容判定（前向兼容）：包名属于该插件，或属于当前 App 版本
     * 尚不认识的任意新包。新包只在用户更新 App 后生效，旧版 App 忽略即可，
     * 不得因此拒绝整个目录或插件；把其他插件的包名声明进来仍然无效。
     */
    fun isTargetPackageCompatible(pluginId: String, targetPackage: String): Boolean {
        if (definitionForId(pluginId) == null) return false
        val owner = definitionForPackage(targetPackage) ?: return true
        return owner.id == pluginId
    }

    fun shouldLoadIntoProcess(packageName: String, processName: String): Boolean {
        if (definitionForPackage(packageName)?.systemMediaRuntime == true) return false
        if (processName == packageName) return true
        return processName in definitionForPackage(packageName)?.secondaryProcesses.orEmpty()
    }

    fun supportsNextTrackPreview(packageName: String): Boolean =
        definitionForPackage(packageName)?.supportsNextTrackPreview ?: true

    fun enabledKey(pluginId: String) =
        "key_official_provider_enabled_$pluginId"

    fun activeFileKey(packageName: String) =
        "key_official_provider_active_file_$packageName"

    fun installedVersionKey(pluginId: String) =
        "key_official_provider_installed_version_$pluginId"

    /** 用户显式移除过该插件：不再使用内置基线包，直到重新安装。 */
    fun removedKey(pluginId: String) =
        "key_official_provider_removed_$pluginId"

    fun installedVersionNameKey(pluginId: String) =
        "key_official_provider_installed_version_name_$pluginId"

    /** 上一版本号：崩溃回退时目标进程据此改载上一版插件包。 */
    fun previousVersionKey(pluginId: String) =
        "key_official_provider_previous_version_$pluginId"

    /** 目标进程上报的坏版本号集合，选择更新候选时跳过。 */
    fun badVersionsKey(pluginId: String) =
        "key_official_provider_bad_versions_$pluginId"

    /** 目标进程最近一次成功加载的版本号，插件页据此显示「重启后生效」。 */
    fun loadedVersionKey(pluginId: String) =
        "key_official_provider_loaded_version_$pluginId"

    /**
     * 用户对该版本执行过「立即生效」（强行停止目标 App）时记录的已安装版本号。
     * 停止后没有任何进程还在运行旧版本，下次启动必然加载该版本，因此不再提示「重启后生效」。
     */
    fun appliedVersionKey(pluginId: String) =
        "key_official_provider_applied_version_$pluginId"

    /** 验签缓存：键为包文件名，值为整包摘要与清单，仅由主程序写入远端偏好。 */
    const val VERIFIED_PACK_CACHE_KEY = "key_official_provider_verified_packs"

    fun remoteFileName(pluginId: String, versionCode: Int) =
        "hle-provider-$pluginId-$versionCode.hlp"
}
