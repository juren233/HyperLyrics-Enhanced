package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
import com.juren233.hyperlyricsenhanced.online.model.Source

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
}
