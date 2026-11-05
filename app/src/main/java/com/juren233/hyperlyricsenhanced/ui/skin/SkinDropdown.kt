/*
 * Copyright 2026 juren233
 */
package com.juren233.hyperlyricsenhanced.ui.skin

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.preference.GlasmioPreferenceEntry
import io.github.neoruaa.glasmio.ui.GlasmioPopUpButton
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.popup.OverlayDropdownPopup
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference as MiuixOverlayDropdownPreference

/** 带摘要的下拉条目，两种风格共用。 */
class SkinDropdownItem(
    val text: String,
    val selected: Boolean,
    val summary: String? = null,
    val onClick: () -> Unit,
)

private fun List<SkinDropdownItem>.toMiuixEntry() = DropdownEntry(
    map { DropdownItem(text = it.text, selected = it.selected, summary = it.summary, onClick = it.onClick) },
)

/** 条目自带点击回调的下拉偏好（点击条目即触发，不通过 selectedIndex）。 */
@Composable
fun OverlayDropdownPreference(
    title: String,
    entries: List<SkinDropdownItem>,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    if (LocalUiStyle.current.isGlasmio) {
        GlasmioPreferenceEntry(
            modifier = modifier,
            title = { Text(title) },
            description = summary,
            trailingContent = {
                val current = entries.firstOrNull { it.selected } ?: entries.firstOrNull()
                if (current != null) {
                    GlasmioPopUpButton(
                        selected = current,
                        items = entries,
                        onSelected = { it.onClick() },
                        label = { it.text },
                    )
                }
            },
        )
    } else {
        MiuixOverlayDropdownPreference(
            entry = entries.toMiuixEntry(),
            title = title,
            modifier = modifier,
            summary = summary,
        )
    }
}

/** Miuix 条目弹层；Glasmio 风格改用 [GlasmioPopUpButton]，不走此弹层。 */
@Composable
fun SkinDropdownPopup(entries: List<SkinDropdownItem>, show: Boolean, onDismiss: () -> Unit) {
    OverlayDropdownPopup(
        entry = entries.toMiuixEntry(),
        show = show,
        onDismiss = onDismiss,
        onDismissFinished = {},
        maxHeight = null,
        dropdownColors = DropdownDefaults.dropdownColors(),
        renderInRootScaffold = true,
        collapseOnSelection = true,
    )
}

/** 下拉触发处右侧的上下箭头。 */
@Composable
fun RowScope.SkinDropdownArrow(color: Color) {
    if (LocalUiStyle.current.isGlasmio) {
        SfIcon(systemName = "chevron.up.chevron.down", contentDescription = null, tint = color)
    } else {
        DropdownArrowEndAction(actionColor = color)
    }
}
