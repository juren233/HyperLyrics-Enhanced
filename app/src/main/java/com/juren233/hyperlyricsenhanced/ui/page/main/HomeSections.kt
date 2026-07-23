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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

fun LazyListScope.homePageSections(
    enableSuperIsland: Boolean,
    onSuperIslandToggle: (Boolean) -> Unit,
    enableDynamicIsland: Boolean,
    onDynamicIslandToggle: (Boolean) -> Unit,
    onSuperIslandConfigClick: () -> Unit,
    onMediaCardConfigClick: () -> Unit,
    onDynamicIslandConfigClick: () -> Unit,
    onRestartClick: () -> Unit,
    removeFocusWhitelist: Boolean,
    onRemoveFocusWhitelistToggle: (Boolean) -> Unit,
    removeIslandWhitelist: Boolean,
    onRemoveIslandWhitelistToggle: (Boolean) -> Unit,
    appleMusicContentUiLanguage: Int,
    appleMusicContentUiLanguageOptions: List<String>,
    onAppleMusicContentUiLanguageChange: (Int) -> Unit,
    onAppSettingsClick: () -> Unit,
) {
    item(key = "enhanced_version_notice") {
        EnhancedVersionNotice(
            modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()
        )
    }

    item(key = "basic_features_title") {
        SmallTitle(
            text = stringResource(R.string.title_basic_features)
        )
    }

    item(key = "basic_features_content_super_island") {
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            Column {
                SwitchPreference(
                    title = stringResource(R.string.title_miui_systemui_enhancement),
                    summary = stringResource(R.string.summary_miui_systemui_enhancement),
                    checked = enableSuperIsland,
                    onCheckedChange = onSuperIslandToggle,
                )
                AnimatedVisibility(visible = enableSuperIsland) {
                    Column {
                        ArrowPreference(
                            title = stringResource(R.string.title_super_island_lyrics),
                            onClick = onSuperIslandConfigClick,
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

    item(key = "basic_features_content_dynamic_island") {
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            Column {
                SwitchPreference(
                    title = stringResource(R.string.title_dynamic_island_lyrics),
                    summary = stringResource(R.string.summary_dynamic_island_lyrics),
                    checked = enableDynamicIsland,
                    onCheckedChange = onDynamicIslandToggle,
                )
                AnimatedVisibility(visible = enableDynamicIsland) {
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
                ArrowPreference(
                    title = stringResource(R.string.title_restart_ui),
                    onClick = onRestartClick,
                )
                SwitchPreference(
                    title = stringResource(R.string.title_remove_focus_whitelist),
                    summary = stringResource(R.string.summary_remove_focus_whitelist),
                    checked = removeFocusWhitelist,
                    onCheckedChange = onRemoveFocusWhitelistToggle,
                )
                SwitchPreference(
                    title = stringResource(R.string.title_remove_island_whitelist),
                    checked = removeIslandWhitelist,
                    onCheckedChange = onRemoveIslandWhitelistToggle,
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.title_apple_music_content_ui_language),
                    summary = stringResource(R.string.summary_apple_music_content_ui_language),
                    items = appleMusicContentUiLanguageOptions,
                    selectedIndex = appleMusicContentUiLanguage.coerceIn(0, appleMusicContentUiLanguageOptions.lastIndex),
                    onSelectedIndexChange = onAppleMusicContentUiLanguageChange,
                )
            }
        }
    }

    item(key = "app_settings") {
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            ArrowPreference(
                title = stringResource(R.string.title_app_settings),
                summary = stringResource(R.string.summary_app_settings),
                onClick = onAppSettingsClick,
            )
        }
    }
}
