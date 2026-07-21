package com.lidesheng.hyperlyric.ui.page.lyricnotification

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lidesheng.hyperlyric.ui.utils.pageScrollModifiers
import top.yukonga.miuix.kmp.basic.ScrollBehavior

@Composable
fun LyricNotificationConfigTab(
    lazyListState: LazyListState,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues,
    notificationType: Int,
    onNotificationTypeChange: (Int) -> Unit,
    islandLeftIconStyle: Int,
    onIslandLeftIconStyleChange: (Int) -> Unit,
    disableLyricSplitEnabled: Boolean,
    onDisableLyricSplitToggle: (Boolean) -> Unit,
    limitWidthEnabled: Boolean,
    onLimitWidthToggle: (Boolean) -> Unit,
    maxWidth: Int,
    onMaxWidthChange: (Int) -> Unit,
    highlightColorEnabled: Boolean,
    onHighlightColorToggle: (Boolean) -> Unit,
    songInfoHighlightColorEnabled: Boolean,
    onSongInfoHighlightColorToggle: (Boolean) -> Unit,
    notificationClickAction: Int,
    onNotificationClickActionChange: (Int) -> Unit,
    showProgressEnabled: Boolean,
    onShowProgressToggle: (Boolean) -> Unit,
    progressColorEnabled: Boolean,
    onProgressColorToggle: (Boolean) -> Unit,
    showAlbumArtEnabled: Boolean,
    onShowAlbumArtToggle: (Boolean) -> Unit,
    focusNotificationType: Int,
    onFocusNotificationTypeChange: (Int) -> Unit,
    focusShowNotificationEnabled: Boolean,
    onFocusShowNotificationToggle: (Boolean) -> Unit,
    normalNotificationTitleStyle: Int,
    onNormalNotificationTitleStyleChange: (Int) -> Unit,
    onAutostartClick: () -> Unit,
    onBatteryOptimizationClick: () -> Unit,
    lyricSource: Int,
    onLyricSourceChange: (Int) -> Unit,
    bypassFocusLimitEnabled: Boolean,
    onBypassFocusLimitToggle: (Boolean) -> Unit
) {
    LazyColumn(
        state = lazyListState,
        modifier = Modifier.pageScrollModifiers(
            enableScrollEndHaptic = true,
            showTopAppBar = true,
            topAppBarScrollBehavior = scrollBehavior
        ),
        contentPadding = contentPadding
    ) {
        configSections(
            notificationType = notificationType,
            onNotificationTypeChange = onNotificationTypeChange,
            islandLeftIconStyle = islandLeftIconStyle,
            onIslandLeftIconStyleChange = onIslandLeftIconStyleChange,
            disableLyricSplitEnabled = disableLyricSplitEnabled,
            onDisableLyricSplitToggle = onDisableLyricSplitToggle,
            limitWidthEnabled = limitWidthEnabled,
            onLimitWidthToggle = onLimitWidthToggle,
            maxWidth = maxWidth,
            onMaxWidthChange = onMaxWidthChange,
            highlightColorEnabled = highlightColorEnabled,
            onHighlightColorToggle = onHighlightColorToggle,
            songInfoHighlightColorEnabled = songInfoHighlightColorEnabled,
            onSongInfoHighlightColorToggle = onSongInfoHighlightColorToggle,
            notificationClickAction = notificationClickAction,
            onNotificationClickActionChange = onNotificationClickActionChange,
            showProgressEnabled = showProgressEnabled,
            onShowProgressToggle = onShowProgressToggle,
            progressColorEnabled = progressColorEnabled,
            onProgressColorToggle = onProgressColorToggle,
            showAlbumArtEnabled = showAlbumArtEnabled,
            onShowAlbumArtToggle = onShowAlbumArtToggle,
            focusNotificationType = focusNotificationType,
            onFocusNotificationTypeChange = onFocusNotificationTypeChange,
            focusShowNotificationEnabled = focusShowNotificationEnabled,
            onFocusShowNotificationToggle = onFocusShowNotificationToggle,
            normalNotificationTitleStyle = normalNotificationTitleStyle,
            onNormalNotificationTitleStyleChange = onNormalNotificationTitleStyleChange,
            onAutostartClick = onAutostartClick,
            onBatteryOptimizationClick = onBatteryOptimizationClick,
            lyricSource = lyricSource,
            onLyricSourceChange = onLyricSourceChange,
            bypassFocusLimitEnabled = bypassFocusLimitEnabled,
            onBypassFocusLimitToggle = onBypassFocusLimitToggle
        )
    }
}
