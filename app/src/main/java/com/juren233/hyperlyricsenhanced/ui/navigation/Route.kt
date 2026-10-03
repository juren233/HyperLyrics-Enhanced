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

    /**
     * 应用设置页。
     *
     * @param scrollToFeatureSwitches 打开时自动定位到“功能开关”分组（不支持设备页的入口按钮使用）。
     */
    @Serializable
    data class Settings(val scrollToFeatureSwitches: Boolean = false) : Route
    @Serializable
    data object HookSettings : Route
    @Serializable
    data object AppleMusicOptimization : Route
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
