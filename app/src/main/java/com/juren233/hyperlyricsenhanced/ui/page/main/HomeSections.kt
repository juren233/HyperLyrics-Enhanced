package com.juren233.hyperlyricsenhanced.ui.page.main

import com.juren233.hyperlyricsenhanced.ui.skin.ArrowPreference
import com.juren233.hyperlyricsenhanced.ui.skin.Card
import com.juren233.hyperlyricsenhanced.ui.skin.SmallTitle
import com.juren233.hyperlyricsenhanced.ui.skin.SwitchPreference
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.ui.component.EnhancedVersionNotice
import com.juren233.hyperlyricsenhanced.ui.component.LyricHookSwitchController
import com.juren233.hyperlyricsenhanced.ui.page.setup.setupOutroItemReveal

/** 首页最多的列表项数，分帧组合时按此分阶段。 */
const val HOME_SECTION_MAX = 8

/**
 * @param sectionLimit 只组合前几项（基础设置完成转场中逐帧放开，每项组合出来时逐项浮现），默认全部。
 */
fun LazyListScope.homePageSections(
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
    sectionLimit: Int = Int.MAX_VALUE,
) {
    var sections = 0
    fun allow(): Boolean = sections++ < sectionLimit

    if (allow()) item(key = "enhanced_version_notice") {
        Box(Modifier.setupOutroItemReveal()) {
            EnhancedVersionNotice(
                updateAvailable = availableUpdateVersion != null,
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()
            )
        }
    }

    if (allow()) item(key = "basic_features_title") {
        Box(Modifier.setupOutroItemReveal()) {
            SmallTitle(
                text = stringResource(R.string.title_basic_features)
            )
        }
    }

    if (allow()) item(key = "basic_features_content_hyper_island") {
        Box(Modifier.setupOutroItemReveal()) {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                ArrowPreference(
                    title = stringResource(R.string.title_lyric_settings),
                    onClick = onLyricSettingsClick,
                )
            }
        }
    }

    if (showHyperIslandEntry && allow()) item(key = "basic_features_content_system_ui") {
        Box(Modifier.setupOutroItemReveal()) {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = stringResource(R.string.title_miui_systemui_enhancement),
                        summary = stringResource(R.string.summary_miui_systemui_enhancement),
                        checked = enableHyperIsland,
                        onCheckedChange = onHyperIslandToggle,
                    )
                    AnimatedVisibility(visible = enableHyperIsland) {
                        Column {
                            ArrowPreference(
                                title = stringResource(R.string.title_hyper_island_lyrics_config),
                                onClick = onHyperIslandConfigClick,
                            )
                            ArrowPreference(
                                title = stringResource(R.string.title_island_touch_config),
                                onClick = onHyperIslandTouchConfigClick,
                            )
                            ArrowPreference(
                                title = stringResource(R.string.title_media_cards),
                                onClick = onMediaCardConfigClick,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAodLyricsEntry && allow()) item(key = "basic_features_content_aod_lyrics") {
        Box(Modifier.setupOutroItemReveal()) {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = stringResource(R.string.title_aod_lyrics),
                        summary = stringResource(R.string.summary_aod_lyrics),
                        checked = enableAodLyrics,
                        onCheckedChange = onAodLyricsToggle,
                    )
                    AnimatedVisibility(visible = enableAodLyrics) {
                        Column {
                            ArrowPreference(
                                title = stringResource(R.string.title_lock_screen_aod),
                                onClick = onLockScreenAodConfigClick,
                            )
                            ArrowPreference(
                                title = stringResource(R.string.title_classic_aod),
                                onClick = onClassicAodConfigClick,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDynamicIslandEntry && allow()) item(key = "basic_features_content_dynamic_island") {
        Box(Modifier.setupOutroItemReveal()) {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = stringResource(R.string.title_dynamic_island_lyrics),
                        summary = stringResource(R.string.summary_dynamic_island_lyrics),
                        checked = lyricHookSwitches.enableDynamicIsland,
                        onCheckedChange = lyricHookSwitches::onDynamicIslandToggle,
                    )
                    AnimatedVisibility(visible = lyricHookSwitches.enableDynamicIsland) {
                        ArrowPreference(
                            title = stringResource(R.string.title_dynamic_island_config),
                            onClick = onDynamicIslandConfigClick,
                        )
                    }
                }
            }
        }
    }

    if (allow()) item(key = "special_features_title") {
        Box(Modifier.setupOutroItemReveal()) {
            SmallTitle(
                text = stringResource(R.string.title_special_features)
            )
        }
    }

    if (allow()) item(key = "special_features_content") {
        Box(Modifier.setupOutroItemReveal()) {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = stringResource(R.string.title_unlock_island_length),
                        checked = lyricHookSwitches.unlockIslandLength,
                        onCheckedChange = lyricHookSwitches::onUnlockIslandLengthToggle,
                    )
                    SwitchPreference(
                        title = stringResource(R.string.title_remove_focus_whitelist),
                        summary = stringResource(R.string.summary_remove_focus_whitelist),
                        checked = lyricHookSwitches.removeFocusWhitelist,
                        onCheckedChange = lyricHookSwitches::onRemoveFocusWhitelistToggle,
                    )
                    SwitchPreference(
                        title = stringResource(R.string.title_remove_island_whitelist),
                        checked = lyricHookSwitches.removeIslandWhitelist,
                        onCheckedChange = lyricHookSwitches::onRemoveIslandWhitelistToggle,
                    )
                }
            }
        }
    }
}
