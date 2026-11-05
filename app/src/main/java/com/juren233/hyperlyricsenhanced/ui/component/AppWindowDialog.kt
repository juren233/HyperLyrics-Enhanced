/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.juren233.hyperlyricsenhanced.ui.skin.LocalUiStyle
import com.juren233.hyperlyricsenhanced.ui.skin.isGlasmio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.ui.skin.SkinTheme
import com.juren233.hyperlyricsenhanced.ui.skin.Text
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import com.juren233.hyperlyricsenhanced.ui.utils.LocaleUtils
import top.yukonga.miuix.kmp.window.WindowDialog

/** Keeps the caller's display language across the native dialog window boundary. */
@Composable
fun AppWindowDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (LocalUiStyle.current.isGlasmio) {
        // Glasmio：自由内容的面板按规范用 Sheet（警告框只放短文字）；内容里的按钮由调用方放置。
        val localized = LocaleUtils.localizedWindowContent(content)
        LaunchedEffect(show) { if (!show) onDismissFinished?.invoke() }
        // 调用方未提供关闭回调 = 必须在面板内操作；下滑收起时重新呈现。
        var representNonce by remember { mutableIntStateOf(0) }
        key(representNonce) {
            GlasmioBottomSheet(
                onDismissRequest = { if (onDismissRequest != null) onDismissRequest() else representNonce++ },
                visible = show,
                fitContent = true,
                dismissOnClickOutside = onDismissRequest != null,
                dismissOnBackPress = onDismissRequest != null,
                title = title.orEmpty(),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                    if (!title.isNullOrEmpty()) {
                        Text(
                            text = title,
                            color = SkinTheme.colors.onBackground,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    if (!summary.isNullOrEmpty()) {
                        Text(
                            text = summary,
                            color = SkinTheme.colors.onSurfaceVariantSummary,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    Column(Modifier.verticalScroll(rememberScrollState())) { localized() }
                }
            }
        }
        return
    }
    WindowDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        content = LocaleUtils.localizedWindowContent(content),
    )
}
