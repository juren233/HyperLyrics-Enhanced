package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.MediaSessionOnlineFallbackPreferences.Settings
import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.source.ttml.CatalogPlatform
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity
import java.util.Locale
import kotlin.math.abs

internal object MediaSessionOnlineFallbackPolicy {
    const val SOURCE_ID = "media_session_online_fallback"
    private val placeholders = setOf(
        "playing", "now playing", "unknown", "unknown title", "unknown song", "unknown track",
        "unknown artist", "untitled", "no title", "loading", "null",
        "未知", "未知标题", "未知歌曲", "未知曲目", "未知歌手", "未知艺术家", "正在播放", "加载中",
    )

    /**
     * 主源只有整行歌词时用来升级逐字的来源，按三方在线源配置的顺序：目录源要求播放器有可寻址的平台 ID；
     * 开启三方在线源配置的「原生逐行歌词转用三方逐字歌词」时还含搜索源（取词时只收逐字结果），Apple Music 除外。
     */
    fun lineToWordSources(settings: Settings, track: TrackIdentity): List<Source> {
        val addressable = CatalogPlatform.ofPlayerPackage(track.packageName)
            ?.let { CatalogPlatform.songIdOf(it, track.mediaId) } != null
        val searchSources = settings.lineToWord &&
            track.packageName != OnlineTranslationSourcePreferences.APPLE_MUSIC_PACKAGE
        return settings.sources.filter { if (it.isTtmlCatalog) addressable else searchSources }
    }

    fun eligible(settings: Settings, track: TrackIdentity?, sessionUsable: Boolean): Boolean =
        sessionUsable && track != null &&
            track.packageName.isNotBlank() && track.packageName in settings.packages && !track.polluted &&
            meaningful(track.title) && meaningful(track.artist) && track.durationMs > 0L

    /** 浏览器可能复用 mediaId；真实标题/歌手变化仍要使旧查询失效。专辑补全不重取。 */
    fun sameLookupTrack(captured: TrackIdentity, current: TrackIdentity): Boolean =
        captured.matches(current) && captured.title.trim() == current.title.trim() &&
            captured.artist.trim() == current.artist.trim() &&
            abs(captured.durationMs - current.durationMs) < 1_500L

    private fun meaningful(text: String): Boolean = text.trim().let {
        it.any(Char::isLetterOrDigit) && it.lowercase(Locale.ROOT).trimEnd('~', '.', '…') !in placeholders
    }
}
