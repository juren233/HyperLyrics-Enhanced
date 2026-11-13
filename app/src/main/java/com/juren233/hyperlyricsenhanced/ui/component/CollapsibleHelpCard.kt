/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.component

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juren233.hyperlyricsenhanced.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 可展开的说明卡片：点标题行展开/收起，内容为若干「小标题 + 正文」。
 * 由超级岛触控页的「使用说明」抽出，各说明页共用这一份样式与动画。
 */
@Composable
fun CollapsibleHelpCard(
    @StringRes title: Int,
    sections: List<Pair<Int, Int>>,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val helpState = stringResource(if (expanded) R.string.help_card_collapse else R.string.help_card_expand)
    Card(modifier = modifier.fillMaxWidth()) {
        Column {
            BasicComponent(
                title = stringResource(title),
                modifier = Modifier.semantics { stateDescription = helpState },
                onClick = { expanded = !expanded },
                endActions = {
                    val iconRotation by animateFloatAsState(
                        targetValue = if (expanded) 90f else -90f,
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                        label = "helpIconRotation",
                    )
                    Icon(MiuixIcons.ChevronBackward, contentDescription = null, modifier = Modifier.rotate(iconRotation))
                },
            )
            AnimatedVisibility(
                visible = expanded,
                enter = SectionEnter,
                exit = SectionExit,
            ) {
                // Same horizontal inset as the card's own title row.
                val inset = BasicComponentDefaults.InsideMargin.calculateStartPadding(LocalLayoutDirection.current)
                Column(
                    modifier = Modifier.padding(horizontal = inset).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    for ((sectionTitle, body) in sections) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(sectionTitle), fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                stringResource(body), fontSize = 14.sp, lineHeight = 21.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        }
                    }
                }
            }
        }
    }
}
