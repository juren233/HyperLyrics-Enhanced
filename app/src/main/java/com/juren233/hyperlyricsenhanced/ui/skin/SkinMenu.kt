/*
 * Copyright 2026 juren233
 */
package com.juren233.hyperlyricsenhanced.ui.skin

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.window.WindowCascadingListPopup

/** 「更多」菜单项。[children] 非空时为二级菜单。 */
data class SkinMenuItem(
    val text: String,
    val selected: Boolean = false,
    val destructive: Boolean = false,
    val children: List<SkinMenuItem> = emptyList(),
    val onClick: () -> Unit = {},
)

/**
 * 顶栏「更多」菜单。Miuix = 级联弹出菜单；Glasmio = [GlasmioMenu]（该组件没有二级菜单，
 * 二级项展开为「父项：子项」的一级项，已选项右侧带对勾）。[groups] 之间在 Miuix 中以分组分隔。
 */
@Composable
fun SkinMoreMenu(groups: List<List<SkinMenuItem>>, contentDescription: String) {
    var expanded by remember { mutableStateOf(false) }
    if (LocalUiStyle.current.isGlasmio) {
        val flat = groups.flatten().flatMap { item ->
            if (item.children.isEmpty()) listOf(item)
            else item.children.map { it.copy(text = "${item.text}：${it.text}") }
        }
        GlasmioMenu(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            itemCount = flat.size,
            anchor = { open ->
                GlasmioIconButton(onClick = open) { Icon(icon = SkinIcons.More, contentDescription = contentDescription) }
            },
        ) { backdrop, close ->
            flat.forEach { item ->
                GlasmioMenuItem(
                    title = item.text,
                    onClick = { item.onClick(); close() },
                    destructive = item.destructive,
                    backdrop = backdrop,
                    trailing = if (item.selected) {
                        { SfIcon(systemName = "checkmark", contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    } else {
        val entries = groups.map { group -> DropdownEntry(items = group.map { it.toMiuix() }) }
        Box {
            IconButton(onClick = { expanded = true }, holdDownState = expanded) {
                Icon(icon = SkinIcons.More, contentDescription = contentDescription)
            }
            WindowCascadingListPopup(
                show = expanded,
                entries = entries,
                alignment = PopupPositionProvider.Align.TopEnd,
                popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
                onDismissRequest = { expanded = false },
            )
        }
    }
}

private fun SkinMenuItem.toMiuix(): DropdownItem = DropdownItem(
    text = text,
    selected = selected,
    children = children.map { it.toMiuix() },
    onClick = onClick,
)
