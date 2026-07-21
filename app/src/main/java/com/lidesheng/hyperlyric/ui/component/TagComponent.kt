// Copyright 2026, HyperLyric contributors
// SPDX-License-Identifier: Apache-2.0

package com.lidesheng.hyperlyric.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * A small tag component used to display status or categories.
 * Encapsulates a Miuix Card with optional icon and text.
 */
@Composable
fun TagComponent(
    text: String,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    imageVector: ImageVector? = null,
    isRainbow: Boolean = false,
    containerColor: Color = MiuixTheme.colorScheme.surface,
    contentColor: Color = if (isRainbow) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = containerColor),
        onClick = onClick,
        pressFeedbackType = if (onClick != null) PressFeedbackType.Sink else PressFeedbackType.None
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = contentColor
                )
                Spacer(modifier = Modifier.width(4.dp))
            } else if (imageVector != null) {
                Icon(
                    imageVector = imageVector,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (isRainbow) Color.Unspecified else contentColor
                )
                Spacer(modifier = Modifier.width(4.dp))
            }

            Text(
                text = text,
                style = MiuixTheme.textStyles.body2,
                color = contentColor,
                fontWeight = if (isRainbow) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}
