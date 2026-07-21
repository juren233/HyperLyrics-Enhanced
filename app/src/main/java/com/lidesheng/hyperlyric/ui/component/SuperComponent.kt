// Copyright 2026, HyperLyric contributors
// SPDX-License-Identifier: Apache-2.0

package com.lidesheng.hyperlyric.ui.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * A "Super" Component that supports expandable summary and custom styling.
 *
 * @param title The title of the component.
 * @param summary The summary/content text.
 * @param modifier The modifier to be applied to the component.
 * @param titleMaxLines The maximum number of lines to show for title when collapsed.
 * @param summaryMaxLines The maximum number of lines to show for summary when collapsed.
 * @param onClick Optional additional click callback.
 * @param insideMargin The margin inside the component.
 * @param pressFeedbackType The press feedback type.
 * @param enabled Whether the component is enabled.
 */
@Composable
fun SuperComponent(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    titleMaxLines: Int = 1,
    summaryMaxLines: Int = 2,
    onClick: (() -> Unit)? = null,
    insideMargin: PaddingValues = PaddingValues(16.dp),
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = {
            if (enabled) {
                expanded = !expanded
                onClick?.invoke()
            }
        },
        pressFeedbackType = pressFeedbackType,
        insideMargin = insideMargin
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            verticalArrangement = Arrangement.Center
        ) {
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else titleMaxLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (summary.isNotBlank()) {
                if (title.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = summary,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else summaryMaxLines,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis
                )
            }
        }
    }
}
