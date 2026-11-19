package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.source.ttml.CatalogPlatform

/** 独立于歌词源选择；名单内的应用有活动媒体会话时自动补充歌词，名单为空即不生效。 */
internal object MediaSessionOnlineFallbackPreferences {
    const val PACKAGES = "key_hook_media_session_online_fallback_allowed_packages"

    /**
     * 三方在线源配置的「原生逐行歌词转用三方逐字歌词」：名单内的音乐 App 只给出整行歌词时，除目录源外也按三方在线源
     * 配置的顺序逐个查搜索源，只收逐字结果。不作用于 Apple Music（它走「原生逐行歌词转用三方逐字歌词」）。
     */
    const val LINE_TO_WORD = "key_hook_music_app_line_to_word_lyrics"
    const val DEFAULT_LINE_TO_WORD = false

    data class Settings(
        val sources: List<Source> = emptyList(),
        val packages: Set<String> = emptySet(),
        /** 「自动」来源：取词时 ttml 与搜索源同时开始；关闭时按用户编排的顺序依次查。 */
        val autoSelect: Boolean = false,
        /** 见 [LINE_TO_WORD]。 */
        val lineToWord: Boolean = DEFAULT_LINE_TO_WORD,
    )

    fun read(prefs: SharedPreferences): Settings = Settings(
        sources = OnlineTranslationSourcePreferences.orderedSources(prefs),
        packages = StringSetPreferences.read(prefs, PACKAGES, emptySet()).orEmpty(),
        autoSelect = OnlineTranslationSourcePreferences.isAutoSelectBestSourceEnabled(prefs),
        lineToWord = prefs.getBoolean(LINE_TO_WORD, DEFAULT_LINE_TO_WORD),
    )

    fun isPreference(key: String?): Boolean = key == PACKAGES || key == LINE_TO_WORD

    /**
     * 在「启用App」里有自己开关、打开后同时按本名单的方式补充歌词的播放器；它们不存进 [PACKAGES]。
     * 2026-11-19 之前网易云、QQ 音乐的开关存在 [PACKAGES] 里，旧值由 StorageKeyMigrator 迁到各自的开关键。
     */
    val OWN_SWITCH_PACKAGES: List<String> = listOf(
        CatalogPlatform.SPOTIFY_PACKAGE,
        OnlineTranslationSourcePreferences.QISHUI_PACKAGE,
        CatalogPlatform.NETEASE_PACKAGE,
        CatalogPlatform.QQ_MUSIC_PACKAGE,
    )
}
