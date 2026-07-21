// Copyright 2026, HyperLyric contributors
// SPDX-License-Identifier: Apache-2.0

package com.lidesheng.hyperlyric.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixIndication

/**
 * A Pro version of Miuix BasicComponent.
 * Adds support for showIndication.
 */
@Composable
fun ProComponent(
    modifier: Modifier = Modifier,
    title: String? = null,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: @Composable (() -> Unit)? = null,
    endActions: @Composable (RowScope.() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    showIndication: Boolean = true,
    enabled: Boolean = true,
    role: Role? = null,
) {
    ProComponent(
        modifier = modifier,
        startAction = startAction,
        endActions = endActions,
        bottomAction = bottomAction,
        onClick = onClick,
        insideMargin = insideMargin,
        showIndication = showIndication,
        enabled = enabled,
        role = role,
    ) {
        if (title != null) {
            Text(
                text = title,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = if (enabled) titleColor.color else titleColor.disabledColor,
            )
        }
        if (summary != null) {
            Text(
                text = summary,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = if (enabled) summaryColor.color else summaryColor.disabledColor,
            )
        }
    }
}

@Composable
fun ProComponent(
    modifier: Modifier = Modifier,
    startAction: @Composable (() -> Unit)? = null,
    endActions: @Composable (RowScope.() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    showIndication: Boolean = true,
    enabled: Boolean = true,
    role: Role? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    val clickableModifier = if (enabled && onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = if (showIndication) MiuixIndication() else null,
            onClick = onClick,
            role = role
        )
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .then(clickableModifier)
            .heightIn(min = 10.dp)
            .fillMaxWidth()
            .padding(insideMargin),
        verticalArrangement = Arrangement.Center,
    ) {
        if (startAction == null && endActions == null) {
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start,
                content = content,
            )
        } else {
            Layout(
                content = {
                    startAction?.let {
                        Column(
                            modifier = Modifier.layoutId("start"),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.Start,
                        ) { it() }
                    }
                    Column(
                        modifier = Modifier.layoutId("center"),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.Start,
                        content = content,
                    )
                    endActions?.let {
                        Column(
                            modifier = Modifier.layoutId("end"),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.End,
                        ) {
                            Row { it() }
                        }
                    }
                },
            ) { measurables, constraints ->
                val spacerPx = 16.dp.roundToPx()

                val startMeasurable = measurables.firstOrNull { it.layoutId == "start" }
                val centerMeasurable = measurables.first { it.layoutId == "center" }
                val endMeasurable = measurables.firstOrNull { it.layoutId == "end" }

                val maxWidth = constraints.maxWidth
                val maxHeight = constraints.maxHeight

                val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)

                val startPlaceable = startMeasurable?.measure(looseConstraints)
                val startWidth = startPlaceable?.width ?: 0
                val startSpacerWidth = if (startWidth > 0) spacerPx else 0
                val widthAfterStart = (maxWidth - startWidth - startSpacerWidth).coerceAtLeast(0)

                val endIntrinsicWidth = endMeasurable?.maxIntrinsicWidth(maxHeight) ?: 0
                val endHardCap = (widthAfterStart - spacerPx).coerceAtLeast(0) * 6 / 10
                val endTargetWidth = endIntrinsicWidth.coerceAtMost(endHardCap)
                val endPlaceable = endMeasurable?.measure(
                    looseConstraints.copy(maxWidth = endTargetWidth),
                )
                val endActualWidth = endPlaceable?.width ?: 0
                val endSpacerWidth = if (endActualWidth > 0) spacerPx else 0

                val widthForCenter = (widthAfterStart - endActualWidth - endSpacerWidth).coerceAtLeast(0)
                val centerPlaceable = centerMeasurable.measure(
                    looseConstraints.copy(maxWidth = widthForCenter),
                )

                val startHeight = startPlaceable?.height ?: 0
                val endHeight = endPlaceable?.height ?: 0
                val rowHeight = maxOf(startHeight, centerPlaceable.height, endHeight)
                val layoutHeight = rowHeight
                    .coerceIn(constraints.minHeight, maxHeight.takeIf { it != Constraints.Infinity } ?: rowHeight)

                layout(width = maxWidth, height = layoutHeight) {
                    val startTop = (rowHeight - startHeight).coerceAtLeast(0) / 2
                    val centerTop = (rowHeight - centerPlaceable.height) / 2
                    val endTop = (rowHeight - endHeight).coerceAtLeast(0) / 2

                    startPlaceable?.placeRelative(0, startTop)

                    val centerX = startWidth + startSpacerWidth
                    centerPlaceable.placeRelative(centerX, centerTop)

                    endPlaceable?.let {
                        val endX = maxWidth - it.width
                        it.placeRelative(endX, endTop)
                    }
                }
            }
        }

        if (bottomAction != null) {
            Spacer(modifier = Modifier.height(8.dp))
            bottomAction()
        }
    }
}
