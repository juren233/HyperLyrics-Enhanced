/*
 * Copyright 2026 juren233
 */
package com.juren233.hyperlyricsenhanced.ui.skin

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.kyant.capsule.ContinuousRoundedRectangle
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioPopUpButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.preference.GlasmioListPreference
import io.github.neoruaa.glasmio.preference.GlasmioPreferenceEntry
import io.github.neoruaa.glasmio.preference.GlasmioSwitchPreference
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioSectionTitle
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.preference.ArrowPreference as MiuixArrowPreference
import top.yukonga.miuix.kmp.basic.BasicComponent as MiuixBasicComponent
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.SmallTitle as MiuixSmallTitle
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference as MiuixOverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference as MiuixSwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference as MiuixWindowDropdownPreference

/**
 * 风格分发层：页面只引用本文件的同名组件，由 [LocalUiStyle] 决定走 Miuix 还是 Glasmio。
 * 参数是 Miuix 同名组件在本项目中实际用到的子集；需要 Miuix 专有参数（自定义颜色、按压反馈等）的
 * 个别位置继续直接使用 Miuix 组件，不在此处假设两种风格都有对应物。
 */
/** 卡片按压反馈。Miuix 有对应动效；Glasmio 的组件自带按压高光，不叠加此项。 */
enum class SkinPress { None, Sink, Tilt }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    containerColor: Color? = null,
    insideMargin: PaddingValues? = null,
    press: SkinPress = SkinPress.None,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalUiStyle.current.isGlasmio) {
        val clickable = if (onClick != null || onLongPress != null) {
            modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongPress)
        } else {
            modifier
        }
        val padded: @Composable ColumnScope.() -> Unit = {
            Column(if (insideMargin != null) Modifier.padding(insideMargin) else Modifier) { content() }
        }
        if (containerColor == null) {
            GlasmioGroupedList(modifier = clickable, content = if (insideMargin != null) padded else content)
        } else {
            Column(
                modifier = clickable
                    .clip(ContinuousRoundedRectangle(GlasmioTheme.dimensions.regularCornerRadius))
                    .background(containerColor),
                content = if (insideMargin != null) padded else content,
            )
        }
        return
    }
    val colors = if (containerColor != null) CardDefaults.defaultColors(color = containerColor) else CardDefaults.defaultColors()
    val margin = insideMargin ?: CardDefaults.InsideMargin
    if (onClick == null && onLongPress == null) {
        MiuixCard(modifier = modifier, insideMargin = margin, colors = colors, content = content)
    } else {
        MiuixCard(
            modifier = modifier,
            insideMargin = margin,
            colors = colors,
            pressFeedbackType = when (press) {
                SkinPress.None -> PressFeedbackType.None
                SkinPress.Sink -> PressFeedbackType.Sink
                SkinPress.Tilt -> PressFeedbackType.Tilt
            },
            onClick = onClick,
            onLongPress = onLongPress,
            content = content,
        )
    }
}

@Composable
fun SmallTitle(text: String, modifier: Modifier = Modifier) {
    if (LocalUiStyle.current.isGlasmio) {
        GlasmioSectionTitle(title = text, modifier = modifier)
    } else {
        MiuixSmallTitle(text = text, modifier = modifier)
    }
}

@Composable
fun ArrowPreference(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: @Composable (() -> Unit)? = null,
    endActions: @Composable (RowScope.() -> Unit)? = null,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
) {
    if (LocalUiStyle.current.isGlasmio) {
        GlasmioPreferenceEntry(
            modifier = modifier,
            title = { Text(title) },
            description = summary,
            icon = startAction,
            trailingContent = {
                Row {
                    endActions?.invoke(this)
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = null,
                        tint = GlasmioTheme.colors.secondaryContent,
                    )
                }
            },
            onClick = onClick,
            isEnabled = enabled,
        )
    } else {
        MiuixArrowPreference(
            title = title,
            modifier = modifier,
            summary = summary,
            startAction = startAction,
            endActions = endActions ?: {},
            onClick = onClick,
            enabled = enabled,
            holdDownState = holdDownState,
        )
    }
}

@Composable
fun SwitchPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    if (LocalUiStyle.current.isGlasmio) {
        GlasmioSwitchPreference(
            modifier = modifier,
            title = { Text(title) },
            description = summary,
            icon = startAction,
            checked = checked,
            onCheckedChange = onCheckedChange,
            isEnabled = enabled,
        )
    } else {
        MiuixSwitchPreference(
            checked = checked,
            onCheckedChange = onCheckedChange,
            title = title,
            modifier = modifier,
            summary = summary,
            startAction = startAction,
            enabled = enabled,
        )
    }
}

@Composable
fun BasicComponent(
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
    endActions: @Composable (RowScope.() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    holdDownState: Boolean = false,
) {
    if (LocalUiStyle.current.isGlasmio) {
        GlasmioPreferenceEntry(
            modifier = modifier,
            title = { if (title != null) Text(title) },
            description = summary,
            content = bottomAction,
            trailingContent = endActions?.let { actions -> { Row { actions(this) } } },
            onClick = onClick,
        )
    } else {
        MiuixBasicComponent(
            modifier = modifier,
            title = title,
            summary = summary,
            endActions = endActions,
            bottomAction = bottomAction,
            onClick = onClick,
            holdDownState = holdDownState,
        )
    }
}

@Composable
fun OverlayDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    if (LocalUiStyle.current.isGlasmio) {
        GlasmioDropdown(title, items, selectedIndex, onSelectedIndexChange, modifier, summary, enabled)
    } else {
        MiuixOverlayDropdownPreference(
            title = title,
            items = items,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = onSelectedIndexChange,
            modifier = modifier,
            summary = summary,
            enabled = enabled,
        )
    }
}

@Composable
fun WindowDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    if (LocalUiStyle.current.isGlasmio) {
        GlasmioDropdown(title, items, selectedIndex, onSelectedIndexChange, modifier, summary, enabled)
    } else {
        MiuixWindowDropdownPreference(
            title = title,
            items = items,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = onSelectedIndexChange,
            modifier = modifier,
            summary = summary,
            enabled = enabled,
        )
    }
}

@Composable
private fun GlasmioDropdown(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier,
    summary: String?,
    enabled: Boolean,
) {
    GlasmioPreferenceEntry(
        modifier = modifier,
        title = { Text(title) },
        description = summary,
        trailingContent = {
            GlasmioPopUpButton(
                selected = selectedIndex,
                items = items.indices.toList(),
                onSelected = onSelectedIndexChange,
                label = { items.getOrElse(it) { "" } },
                enabled = enabled,
            )
        },
    )
}

/** 单选行。Miuix = RadioButtonPreference；Glasmio = 分组列表行，已选项右侧带对勾（iOS 单选列表的惯例）。 */
@Composable
fun RadioButtonPreference(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalUiStyle.current.isGlasmio) {
        GlasmioListRow(
            title = title,
            modifier = modifier,
            onClick = onClick,
            trailing = if (selected) {
                { SfIcon(systemName = "checkmark", contentDescription = null) }
            } else {
                { Spacer(Modifier.size(1.dp)) }
            },
        )
    } else {
        top.yukonga.miuix.kmp.preference.RadioButtonPreference(
            title = title,
            selected = selected,
            onClick = onClick,
            modifier = modifier,
        )
    }
}
