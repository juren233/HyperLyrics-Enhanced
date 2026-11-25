package com.juren233.hyperlyricsenhanced.ui.navigation

import top.yukonga.miuix.kmp.nav.core.NavKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Setup : Route
    @Serializable
    data object Main : Route
    /** 从设置页回顾基础设置流程；完成后返回设置页。 */
    @Serializable
    data object SetupReview : Route

    /**
     * 应用设置页。
     *
     * @param scrollToFeatureSwitches 打开时自动定位到“功能开关”分组（不支持设备页的入口按钮使用）。
     */
    @Serializable
    data class Settings(val scrollToFeatureSwitches: Boolean = false) : Route
    /** 应用设置 → 外观 →「更多」子页：手势与界面、高级。 */
    @Serializable
    data object MoreSettings : Route
    @Serializable
    data object HookSettings : Route
    @Serializable
    data object AppleMusicOptimization : Route
    @Serializable
    data object AppleLyricsImageBackground : Route
    @Serializable
    data object AppleLyricsImageBackgroundColor : Route
    @Serializable
    data object DynamicIslandNotification : Route
    @Serializable
    data object Log : Route
    @Serializable
    data object LyricProvider : Route
    @Serializable
    data object LyricProviderDownloads : Route
    @Serializable
    data object LyricAnimation : Route
    @Serializable
    data object LyricSettings : Route
    @Serializable
    data object OnlineTranslationSources : Route
    @Serializable
    data object OnlineLyricCache : Route
    /** 歌词缓存管理里一个来源的页面；[source] 为 [com.juren233.hyperlyricsenhanced.online.model.Source] 的名字。 */
    @Serializable
    data class OnlineLyricCacheSource(val source: String) : Route
    /**
     * 歌词缓存管理里一位歌手或一张专辑的歌曲页：[grouping] 为 `CachedSongGrouping` 的名字，
     * [name] 为歌手名或专辑名（空为未知）；[source]、[players] 是进入时所在的来源页与选中的播放器（空为全部）。
     */
    @Serializable
    data class OnlineLyricCacheGroup(
        val grouping: String,
        val name: String,
        val source: String? = null,
        val players: List<String> = emptyList(),
    ) : Route
    /** 歌词缓存管理里的一首歌；[tag] 为歌曲标签（两份缓存里同一首歌的标签相同）。 */
    @Serializable
    data class OnlineLyricCacheSongDetail(val tag: String) : Route
    @Serializable
    data object LyricDisplay : Route
    @Serializable
    data object LyricScroll : Route
    @Serializable
    data object VerbatimLyric : Route
    @Serializable
    data object LyricTranslation : Route
    // Keep the saved Navigation route ID from 220002-220006; verified in its compiled serializer.
    @Serializable
    @SerialName("com.juren233.hyperlyricsenhanced.ui.navigation.Route.SuperIslandTouchSettings")
    data object HyperIslandTouchSettings : Route
    // Preserve the saved route ID verified in the original 220007 compiled serializer.
    @Serializable
    @SerialName("com.juren233.hyperlyricsenhanced.ui.navigation.Route.SuperIslandSettings")
    data object HyperIslandSettings : Route
    // Preserve the saved route ID verified in the original 220007 compiled serializer.
    @Serializable
    @SerialName("com.juren233.hyperlyricsenhanced.ui.navigation.Route.SuperIslandAlbumCoverWhitelist")
    data object HyperIslandAlbumCoverWhitelist : Route
    @Serializable
    data object HyperIslandPureMusicKeywords : Route
    @Serializable
    data object HyperIslandRealRhythmCustom : Route
    @Serializable
    data object MediaSessionOnlineFallbackPlayers : Route
    @Serializable
    data object MediaCardSettings : Route
    @Serializable
    data object LockScreenAodSettings : Route
    @Serializable
    data object ClassicAodSettings : Route
    @Serializable
    data object Licenses : Route
    @Serializable
    data object Poetry : Route
    @Serializable
    data object Help : Route
    @Serializable
    data object Changelog : Route
    @Serializable
    data object Contributors : Route
}
