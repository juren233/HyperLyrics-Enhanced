/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.juren233.hyperlyricsenhanced.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** 「特殊功能」分组内容：警告横幅（适用于下面全部开关）+ 开关卡片。主页与设置页（主页隐藏时）共用。 */
@Composable
fun SpecialFeaturesContent(lyricHookSwitches: LyricHookSwitchController) {
    Column {
        WarningNoticeCard(
            text = stringResource(R.string.notice_special_features_conflict),
            modifier = Modifier.settingsCard(),
        )
        Card(modifier = Modifier.settingsCard()) {
            SwitchPreference(
                title = stringResource(R.string.title_unlock_island_length),
                checked = lyricHookSwitches.unlockIslandLength,
                onCheckedChange = lyricHookSwitches::onUnlockIslandLengthToggle,
            )
            SwitchPreference(
                title = stringResource(R.string.title_remove_focus_whitelist),
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
