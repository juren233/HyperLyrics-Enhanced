/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.component

import androidx.compose.runtime.Composable
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
    WindowDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        content = LocaleUtils.localizedWindowContent(content),
    )
}
