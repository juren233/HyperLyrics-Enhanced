package com.lidesheng.hyperlyric.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Setup : Route
    @Serializable
    data object Main : Route
    @Serializable
    data object Settings : Route
    @Serializable
    data object HookSettings : Route
    @Serializable
    data object DynamicIslandNotification : Route
    @Serializable
    data object Log : Route
    @Serializable
    data object LyricProvider : Route
    @Serializable
    data object LyricAnimation : Route
    @Serializable
    data object LyricSettings : Route
    @Serializable
    data object LyricDisplay : Route
    @Serializable
    data object LyricScroll : Route
    @Serializable
    data object VerbatimLyric : Route
    @Serializable
    data object LyricTranslation : Route
    @Serializable
    data object SuperIslandSettings : Route
    @Serializable
    data object MediaCardSettings : Route
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
