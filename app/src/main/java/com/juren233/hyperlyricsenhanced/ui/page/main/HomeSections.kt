package com.juren233.hyperlyricsenhanced.ui.page.main

import androidx.compose.animation.AnimatedVisibility
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

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
) {
    item(key = "enhanced_version_notice") {
        EnhancedVersionNotice(
            updateAvailable = availableUpdateVersion != null,
            modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()
        )
    }

    item(key = "basic_features_title") {
        SmallTitle(
            text = stringResource(R.string.title_basic_features)
        )
    }

    item(key = "basic_features_content_hyper_island") {
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            ArrowPreference(
                title = stringResource(R.string.title_lyric_settings),
                onClick = onLyricSettingsClick,
            )
        }
    }

    if (showHyperIslandEntry) item(key = "basic_features_content_system_ui") {
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

    if (showAodLyricsEntry) item(key = "basic_features_content_aod_lyrics") {
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

    if (showDynamicIslandEntry) item(key = "basic_features_content_dynamic_island") {
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

    item(key = "special_features_title") {
        SmallTitle(
            text = stringResource(R.string.title_special_features)
        )
    }

    item(key = "special_features_content") {
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
