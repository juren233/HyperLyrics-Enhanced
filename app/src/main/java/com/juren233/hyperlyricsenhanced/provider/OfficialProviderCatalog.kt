/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

object OfficialProviderCatalog {
    const val PLUGIN_API_VERSION = 2
    const val CORE_PACKAGE_NAME = "com.juren233.hyperlyricsenhanced"
    const val APPLE_MUSIC_PACKAGE_NAME = "com.apple.android.music"
    const val OFFICIAL_PROVIDER_PACKAGE_PREFIX =
        "com.juren233.hyperlyricsenhanced.provider."

    data class Definition(
        val id: String,
        val displayName: String,
        val targetPackages: Set<String>,
    )

    val definitions = listOf(
        Definition("netease", "网易云音乐", setOf("com.netease.cloudmusic", "com.hihonor.cloudmusic")),
        Definition("qqmusic", "QQ 音乐", setOf("com.tencent.qqmusic")),
        Definition("qqmusic-hd", "QQ 音乐 HD", setOf("com.tencent.qqmusicpad")),
        Definition("kugou", "酷狗音乐", setOf("com.kugou.android", "com.kugou.android.lite")),
        Definition("kuwo", "酷我音乐", setOf("cn.kuwo.player")),
        Definition("spotify", "Spotify", setOf("com.spotify.music")),
        Definition(
            "lxmusic",
            "LX 音乐",
            setOf(
                "cn.toside.music.mobile",
                "com.ikunshare.music.mobile",
                "com.lxnetease.music.mobile",
            ),
        ),
        Definition("poweramp", "Poweramp", setOf("com.maxmpz.audioplayer")),
        Definition("salt-player", "Salt Player", setOf("com.salt.music")),
        Definition("qishui", "汽水音乐", setOf("com.luna.music")),
        Definition("musicfree", "MusicFree", setOf("fun.upup.musicfree")),
        Definition("gramophone", "Gramophone", setOf("org.akanework.gramophone")),
        Definition("symfonium", "Symfonium", setOf("app.symfonik.music.player")),
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

    fun enabledKey(pluginId: String) =
        "key_official_provider_enabled_$pluginId"

    fun activeFileKey(packageName: String) =
        "key_official_provider_active_file_$packageName"

    fun installedVersionKey(pluginId: String) =
        "key_official_provider_installed_version_$pluginId"

    fun installedVersionNameKey(pluginId: String) =
        "key_official_provider_installed_version_name_$pluginId"

    fun remoteFileName(pluginId: String, versionCode: Int) =
        "hle-provider-$pluginId-$versionCode.hlp"
}
