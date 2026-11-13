package com.juren233.hyperlyricsenhanced.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.R
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Update

@Composable
fun EnhancedVersionNotice(
    updateAvailable: Boolean = false,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    NoticeCard(
        text = stringResource(
            if (updateAvailable) {
                R.string.module_update_available_notice
            } else {
                R.string.enhanced_version_notice
            }
        ) + if (BuildConfig.DEBUG) {
            "\n" + stringResource(R.string.debug_build_notice)
        } else {
            ""
        },
        icon = if (updateAvailable) MiuixIcons.Update else MiuixIcons.Info,
        modifier = modifier,
        emphasized = emphasized,
    )
}
