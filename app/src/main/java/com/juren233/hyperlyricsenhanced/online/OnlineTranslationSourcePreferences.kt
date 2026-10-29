/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.contract.AppleMusicPreferenceKeys
import com.juren233.hyperlyricsenhanced.online.model.Source

object OnlineTranslationSourcePreferences {
    const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
    const val QISHUI_PACKAGE = "com.luna.music"
    const val SPOTIFY_PACKAGE = "com.spotify.music"
    const val SALT_PACKAGE = "com.salt.music"

    val defaultOrder: List<Source> = listOf(
        Source.AMLL,
        Source.LB,
        Source.NE,
        Source.QM,
        Source.KUWO,
        Source.KUGOU,
    )

    fun orderedSources(prefs: SharedPreferences?): List<Source> {
        val stored = prefs?.getString(
            AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER,
            AppleMusicPreferenceKeys.DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER,
        )
        val order = resolveOrder(
            rawOrder = stored,
            automaticSelection = isAutoSelectBestSourceEnabled(prefs),
        )
        return resolveEnabledSources(order) { source -> isSourceEnabled(prefs, source) }
    }

    fun resolveEnabledSources(
        order: List<Source>,
        isEnabled: (Source) -> Boolean,
    ): List<Source> {
        val enabledSources = order.filter(isEnabled)
        return enabledSources.ifEmpty { order.take(1) }
    }

    fun canToggleSource(source: Source, enabledSources: Collection<Source>): Boolean =
        source !in enabledSources || enabledSources.size > 1

    fun resolveOrder(rawOrder: String?, automaticSelection: Boolean): List<Source> =
        if (automaticSelection) defaultOrder else normalizeOrder(rawOrder)

    fun isAutoSelectBestSourceEnabled(prefs: SharedPreferences?): Boolean =
        prefs?.getBoolean(
            AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE,
            AppleMusicPreferenceKeys.DEFAULT_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE,
        ) ?: AppleMusicPreferenceKeys.DEFAULT_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE

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
        Source.NE -> AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_NETEASE
        Source.QM -> AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_QQ
        Source.KUWO -> AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_KUWO
        Source.KUGOU -> AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_KUGOU
        Source.LB -> AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_SOURCE_LUNABEAT
        Source.AMLL -> AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_SOURCE_AMLL
    }

    fun sourceDefaultEnabled(source: Source): Boolean = when (source) {
        Source.NE -> AppleMusicPreferenceKeys.DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_NETEASE
        Source.QM -> AppleMusicPreferenceKeys.DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_QQ
        Source.KUWO -> AppleMusicPreferenceKeys.DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_KUWO
        Source.KUGOU -> AppleMusicPreferenceKeys.DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_KUGOU
        Source.LB -> AppleMusicPreferenceKeys.DEFAULT_HOOK_ONLINE_SOURCE_LUNABEAT
        Source.AMLL -> AppleMusicPreferenceKeys.DEFAULT_HOOK_ONLINE_SOURCE_AMLL
    }

    fun isSourceEnabled(prefs: SharedPreferences?, source: Source): Boolean =
        prefs?.getBoolean(sourcePreferenceKey(source), sourceDefaultEnabled(source))
            ?: sourceDefaultEnabled(source)

    fun appPreferenceKey(packageName: String): String? = when (packageName) {
        APPLE_MUSIC_PACKAGE -> AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION
        QISHUI_PACKAGE -> AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_APP_QISHUI
        SPOTIFY_PACKAGE -> AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_APP_SPOTIFY
        SALT_PACKAGE -> AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_APP_SALT
        else -> null
    }

    fun appDefaultEnabled(packageName: String): Boolean = when (packageName) {
        APPLE_MUSIC_PACKAGE -> AppleMusicPreferenceKeys.DEFAULT_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION
        QISHUI_PACKAGE -> AppleMusicPreferenceKeys.DEFAULT_HOOK_ONLINE_TRANSLATION_APP_QISHUI
        SPOTIFY_PACKAGE -> AppleMusicPreferenceKeys.DEFAULT_HOOK_ONLINE_TRANSLATION_APP_SPOTIFY
        SALT_PACKAGE -> AppleMusicPreferenceKeys.DEFAULT_HOOK_ONLINE_TRANSLATION_APP_SALT
        else -> false
    }

    fun isAppEnabled(prefs: SharedPreferences?, packageName: String?): Boolean {
        val packageValue = packageName ?: return false
        val key = appPreferenceKey(packageValue) ?: return false
        return prefs?.getBoolean(key, appDefaultEnabled(packageValue))
            ?: appDefaultEnabled(packageValue)
    }

    /**
     * 在线取词是否允许用于该包：「启用App」未收录的包（无对应开关）不受
     * 该门禁约束；已收录的包必须显式启用，否则不得经三方在线源取词。
     */
    fun isOnlineLyricsAllowedForApp(prefs: SharedPreferences?, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return true
        if (appPreferenceKey(packageName) == null) return true
        return isAppEnabled(prefs, packageName)
    }

    fun isSourcePreference(key: String?): Boolean = key in setOf(
        AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER,
        AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE,
        AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_NETEASE,
        AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_QQ,
        AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_KUWO,
        AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_KUGOU,
        AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_SOURCE_AMLL,
        AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_SOURCE_LUNABEAT,
    )

    fun isAppPreference(key: String?): Boolean = key in setOf(
        AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION,
        AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_APP_QISHUI,
        AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_APP_SPOTIFY,
        AppleMusicPreferenceKeys.KEY_HOOK_ONLINE_TRANSLATION_APP_SALT,
    )
}
