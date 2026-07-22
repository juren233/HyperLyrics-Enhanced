// Copyright 2026, HyperLyrics Enhanced contributors
// SPDX-License-Identifier: Apache-2.0

package com.juren233.hyperlyricsenhanced.ui.component

import android.graphics.Color as AndroidColor
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    val textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.toArgb()
    val linkColor = MiuixTheme.colorScheme.primary.toArgb()
    val textSize = MiuixTheme.textStyles.body2.fontSize.value

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            TextView(viewContext).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                setPadding(0, 0, 0, 0)
                includeFontPadding = false
                linksClickable = true
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
                setLineSpacing(0f, 1.12f)
            }
        },
        update = { textView ->
            textView.setTextColor(textColor)
            textView.setLinkTextColor(linkColor)
            textView.textSize = textSize
            if (textView.tag != markdown) {
                markwon.setMarkdown(textView, markdown)
                textView.tag = markdown
            }
        },
    )
}
