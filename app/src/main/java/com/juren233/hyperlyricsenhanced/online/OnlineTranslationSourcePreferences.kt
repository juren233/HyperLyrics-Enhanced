/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.online.model.Source

object OnlineTranslationSourcePreferences {
    const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
    const val QISHUI_PACKAGE = "com.luna.music"
    const val SPOTIFY_PACKAGE = "com.spotify.music"
    const val SALT_PACKAGE = "com.salt.music"

    val defaultOrder: List<Source> = listOf(
        Source.NE,
        Source.QM,
        Source.KUWO,
        Source.KUGOU,
    )

    fun orderedSources(prefs: SharedPreferences?): List<Source> {
        val stored = prefs?.getString(
            RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER,
            RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER,
        )
        val order = resolveOrder(
            rawOrder = stored,
            automaticSelection = isAutoSelectBestSourceEnabled(prefs),
        )
        return order.filter { source -> isSourceEnabled(prefs, source) }
    }

    fun resolveOrder(rawOrder: String?, automaticSelection: Boolean): List<Source> =
        if (automaticSelection) defaultOrder else normalizeOrder(rawOrder)

    fun isAutoSelectBestSourceEnabled(prefs: SharedPreferences?): Boolean =
        prefs?.getBoolean(
            RootConstants.KEY_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE,
            RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE,
        ) ?: RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE

    fun normalizeOrder(raw: String?): List<Source> {
        val parsed = raw.orEmpty()
            .split(',')
            .mapNotNull { value ->
                runCatching { Source.valueOf(value.trim()) }.getOrNull()
            }
            .distinct()
        return parsed + defaultOrder.filterNot(parsed::contains)
    }

    fun serializeOrder(order: List<Source>): String =
        normalizeOrder(order.joinToString(",", transform = Source::name))
            .joinToString(",", transform = Source::name)

    fun sourcePreferenceKey(source: Source): String = when (source) {
        Source.NE -> RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_NETEASE
        Source.QM -> RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_QQ
        Source.KUWO -> RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_KUWO
        Source.KUGOU -> RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_KUGOU
    }

    fun sourceDefaultEnabled(source: Source): Boolean = when (source) {
        Source.NE -> RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_NETEASE
        Source.QM -> RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_QQ
        Source.KUWO -> RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_KUWO
        Source.KUGOU -> RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_KUGOU
    }

    fun isSourceEnabled(prefs: SharedPreferences?, source: Source): Boolean =
        prefs?.getBoolean(sourcePreferenceKey(source), sourceDefaultEnabled(source))
            ?: sourceDefaultEnabled(source)

    fun appPreferenceKey(packageName: String): String? = when (packageName) {
        APPLE_MUSIC_PACKAGE -> RootConstants.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION
        QISHUI_PACKAGE -> RootConstants.KEY_HOOK_ONLINE_TRANSLATION_APP_QISHUI
        SPOTIFY_PACKAGE -> RootConstants.KEY_HOOK_ONLINE_TRANSLATION_APP_SPOTIFY
        SALT_PACKAGE -> RootConstants.KEY_HOOK_ONLINE_TRANSLATION_APP_SALT
        else -> null
    }

    fun appDefaultEnabled(packageName: String): Boolean = when (packageName) {
        APPLE_MUSIC_PACKAGE -> RootConstants.DEFAULT_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION
        QISHUI_PACKAGE -> RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_APP_QISHUI
        SPOTIFY_PACKAGE -> RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_APP_SPOTIFY
        SALT_PACKAGE -> RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_APP_SALT
        else -> false
    }

    fun isAppEnabled(prefs: SharedPreferences?, packageName: String?): Boolean {
        val packageValue = packageName ?: return false
        val key = appPreferenceKey(packageValue) ?: return false
        return prefs?.getBoolean(key, appDefaultEnabled(packageValue))
            ?: appDefaultEnabled(packageValue)
    }

    fun isSourcePreference(key: String?): Boolean = key in setOf(
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER,
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE,
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_NETEASE,
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_QQ,
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_KUWO,
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_KUGOU,
    )

    fun isAppPreference(key: String?): Boolean = key in setOf(
        RootConstants.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION,
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_APP_QISHUI,
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_APP_SPOTIFY,
        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_APP_SALT,
    )
}
