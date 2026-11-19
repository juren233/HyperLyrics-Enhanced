/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.common.MediaSessionOnlineFallbackPreferences
import com.juren233.hyperlyricsenhanced.common.StringSetPreferences
import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
import com.juren233.hyperlyricsenhanced.root.island.IslandRuntimePreferenceOverrides

/** 哪些播放器的歌词由三方在线源补翻译与发音。 */
internal object OnlineEnrichmentAppGate {
    /** 「更多App」名单：配置广播下发的运行时值优先，其次是已落盘的配置。 */
    fun moreAppsPackages(prefs: SharedPreferences?): Set<String> {
        val stored = prefs?.let {
            StringSetPreferences.read(it, MediaSessionOnlineFallbackPreferences.PACKAGES, emptySet())
        }.orEmpty()
        return IslandRuntimePreferenceOverrides
            .getStringSet(MediaSessionOnlineFallbackPreferences.PACKAGES, stored)
            .orEmpty()
    }

    /** 不由「更多App」名单决定、各有自己开关的播放器（Apple Music、汽水、Spotify、椒盐、网易云、QQ 音乐）。 */
    fun hasOwnSwitch(packageName: String): Boolean =
        OnlineTranslationSourcePreferences.appPreferenceKey(packageName) != null

    /** 有自己开关的播放器看自己的开关；其余播放器（含插件应用）在「更多App」里启用即补。 */
    fun isEnabled(prefs: SharedPreferences?, packageName: String): Boolean =
        if (hasOwnSwitch(packageName)) {
            OnlineTranslationSourcePreferences.isAppEnabled(prefs, packageName)
        } else {
            packageName in moreAppsPackages(prefs)
        }
}
