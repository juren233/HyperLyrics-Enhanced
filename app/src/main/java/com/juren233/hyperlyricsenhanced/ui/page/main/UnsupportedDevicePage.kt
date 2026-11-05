/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.main

import com.juren233.hyperlyricsenhanced.ui.skin.Icon
import com.juren233.hyperlyricsenhanced.ui.skin.SkinIcons
import com.juren233.hyperlyricsenhanced.ui.skin.SkinTheme
import com.juren233.hyperlyricsenhanced.ui.skin.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.ui.skin.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults

/**
 * 功能入口全部关闭时的引导页。
 *
 * 应用设置中“功能开关”全部关闭（且因此主页被隐藏）时进入该页；
 * 用户仍可点击按钮进入应用设置并自动定位到“功能开关”分组手动开启入口。
 */
@Composable
fun UnsupportedDevicePage(
    onEnterAppSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SkinTheme.colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                icon = SkinIcons.Help,
                contentDescription = null,
                tint = SkinTheme.colors.onSurfaceVariantSummary,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = stringResource(R.string.title_unsupported_device),
                style = SkinTheme.textStyles.title3,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.summary_unsupported_device),
                style = SkinTheme.textStyles.body2,
                color = SkinTheme.colors.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
            TextButton(
                text = stringResource(R.string.action_enter_app_settings),
                onClick = onEnterAppSettings,
                modifier = Modifier.padding(top = 12.dp),
                primary = true,
            )
        }
    }
}
