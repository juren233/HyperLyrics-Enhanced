package com.juren233.hyperlyricsenhanced.ui.page.main

import com.juren233.hyperlyricsenhanced.ui.skin.SkinListPage
import com.juren233.hyperlyricsenhanced.ui.skin.Icon
import com.juren233.hyperlyricsenhanced.ui.skin.IconButton
import com.juren233.hyperlyricsenhanced.ui.skin.SkinIcons
import com.juren233.hyperlyricsenhanced.ui.skin.SkinTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.ui.component.AppSettingsIconButton
import com.juren233.hyperlyricsenhanced.ui.component.CompactBarTitle
import com.juren233.hyperlyricsenhanced.ui.component.LyricHookSwitchController
import com.juren233.hyperlyricsenhanced.ui.utils.pageContentPadding

@Composable
fun HomePage(
    outerPadding: PaddingValues,
    availableUpdateVersion: String?,
    showHyperIslandEntry: Boolean,
    showAodLyricsEntry: Boolean,
    showDynamicIslandEntry: Boolean,
    lyricHookSwitches: LyricHookSwitchController,
    enableHyperIsland: Boolean,
    onHyperIslandToggle: (Boolean) -> Unit,
    enableAodLyrics: Boolean,
    onAodLyricsToggle: (Boolean) -> Unit,
    onHyperIslandConfigClick: () -> Unit,
    onHyperIslandTouchConfigClick: () -> Unit,
    onMediaCardConfigClick: () -> Unit,
    onLyricSettingsClick: () -> Unit,
    onDynamicIslandConfigClick: () -> Unit,
    onLockScreenAodConfigClick: () -> Unit,
    onClassicAodConfigClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onAppSettingsClick: () -> Unit,
    sectionLimit: Int = Int.MAX_VALUE,
) {
    val lazyListState = rememberLazyListState()

    SkinListPage(
        title = "HyperLyrics Enhanced",
        outerPadding = outerPadding,
        leadingContent = {
                            AppSettingsIconButton(onClick = onAppSettingsClick)
                        },
        trailingContent = {
                            IconButton(onClick = onRefreshClick) {
                                Icon(
                                    icon = SkinIcons.Refresh,
                                    contentDescription = stringResource(R.string.title_one_tap_refresh),
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                        },
        listState = lazyListState,
    ) {
                homePageSections(
                    availableUpdateVersion = availableUpdateVersion,
                    showHyperIslandEntry = showHyperIslandEntry,
                    showAodLyricsEntry = showAodLyricsEntry,
                    showDynamicIslandEntry = showDynamicIslandEntry,
                    lyricHookSwitches = lyricHookSwitches,
                    enableHyperIsland = enableHyperIsland,
                    onHyperIslandToggle = onHyperIslandToggle,
                    enableAodLyrics = enableAodLyrics,
                    onAodLyricsToggle = onAodLyricsToggle,
                    onHyperIslandConfigClick = onHyperIslandConfigClick,
                    onHyperIslandTouchConfigClick = onHyperIslandTouchConfigClick,
                    onMediaCardConfigClick = onMediaCardConfigClick,
                    onLyricSettingsClick = onLyricSettingsClick,
                    onDynamicIslandConfigClick = onDynamicIslandConfigClick,
                    onLockScreenAodConfigClick = onLockScreenAodConfigClick,
                    onClassicAodConfigClick = onClassicAodConfigClick,
                    sectionLimit = sectionLimit,
                )
    }
}
