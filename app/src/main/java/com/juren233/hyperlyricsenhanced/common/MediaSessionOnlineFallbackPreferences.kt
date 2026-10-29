package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.source.ttml.CatalogPlatform

/** 独立于歌词源选择；名单内的应用有活动媒体会话时自动补充歌词，名单为空即不生效。 */
internal object MediaSessionOnlineFallbackPreferences {
    const val PACKAGES = "key_hook_media_session_online_fallback_allowed_packages"

    data class Settings(
        val sources: List<Source> = emptyList(),
        val packages: Set<String> = emptySet(),
    )

    fun read(prefs: SharedPreferences): Settings = Settings(
        sources = OnlineTranslationSourcePreferences.orderedSources(prefs),
        packages = prefs.getStringSet(PACKAGES, emptySet()).orEmpty().toSet(),
    )

    fun isPreference(key: String?): Boolean = key == PACKAGES

    /** 在「启用App」里外显、且开关就存放在本名单里的播放器（其余外显 App 各有自己的开关键）。 */
    val SURFACED_PACKAGES: Set<String> = setOf(
        CatalogPlatform.NETEASE_PACKAGE,
        CatalogPlatform.QQ_MUSIC_PACKAGE,
    )

    /** 「启用App」里外显的播放器与「更多App」共用这份名单，开关只增删名单成员。 */
    fun withPackage(packages: Set<String>, packageName: String, enabled: Boolean): Set<String> =
        if (enabled) packages + packageName else packages - packageName
}
