/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.IslandPureMusicPolicy
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.ui.component.SimpleDialog
import com.juren233.hyperlyricsenhanced.ui.component.TextInputDialog
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookConfigSaver
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookPrefs
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 垃圾桶图标自带内边距，右移后让它的右侧留白与左侧文字边距视觉对齐；编辑按钮位置不变。 */
private val DELETE_BUTTON_END_SHIFT = 4.dp

/** 纯音乐判定关键词：整首歌歌词不超过 5 行且任一行包含其中一项时，判为纯音乐。 */
@Composable
fun HyperIslandPureMusicKeywordsPage() {
    val prefs = rememberHookPrefs()
    val saveConfig = rememberHookConfigSaver(prefs)
    var keywords by remember {
        mutableStateOf(
            IslandPureMusicPolicy.effectiveKeywords(
                prefs.getString(RootConstants.KEY_HOOK_ISLAND_PURE_MUSIC_KEYWORDS, null),
                prefs.getString(RootConstants.KEY_HOOK_ISLAND_PURE_MUSIC_KEYWORDS_SEEN_DEFAULTS, null),
            ),
        )
    }
    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }

    fun update(next: List<String>) {
        val cleaned = IslandPureMusicPolicy.parseKeywords(IslandPureMusicPolicy.serializeKeywords(next))
        keywords = cleaned
        saveConfig(
            RootConstants.KEY_HOOK_ISLAND_PURE_MUSIC_KEYWORDS_SEEN_DEFAULTS,
            IslandPureMusicPolicy.DEFAULT_KEYWORDS_RAW,
        )
        saveConfig(
            RootConstants.KEY_HOOK_ISLAND_PURE_MUSIC_KEYWORDS,
            IslandPureMusicPolicy.serializeKeywords(cleaned),
        )
    }

    XposedLyricSettingPage(
        title = stringResource(R.string.title_island_pure_music_keywords),
        trailingContent = {
            IconButton(onClick = { showResetDialog = true }) {
                Icon(
                    imageVector = MiuixIcons.Reset,
                    contentDescription = stringResource(R.string.action_island_pure_music_keywords_reset),
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = stringResource(R.string.action_island_pure_music_keyword_add),
                    tint = MiuixTheme.colorScheme.onPrimary,
                )
            }
        },
    ) {
        item(key = "pure_music_keywords_title") {
            SmallTitle(text = stringResource(R.string.summary_island_pure_music_keywords))
        }
        item(key = "pure_music_keywords_list") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                keywords.forEach { keyword ->
                    BasicComponent(
                        title = keyword,
                        endActions = {
                            Row {
                                IconButton(onClick = { editTarget = keyword }) {
                                    Icon(
                                        imageVector = MiuixIcons.Edit,
                                        contentDescription = stringResource(R.string.action_island_pure_music_keyword_edit),
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                    )
                                }
                                IconButton(
                                    onClick = { deleteTarget = keyword },
                                    modifier = Modifier.offset(x = DELETE_BUTTON_END_SHIFT),
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Delete,
                                        contentDescription = stringResource(R.string.delete),
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    TextInputDialog(
        show = showAddDialog,
        title = stringResource(R.string.action_island_pure_music_keyword_add),
        initialValue = "",
        label = stringResource(R.string.title_island_pure_music_keywords),
        onDismiss = { showAddDialog = false },
        onConfirm = { input -> update(keywords + input.trim()) },
    )
    TextInputDialog(
        show = editTarget != null,
        title = stringResource(R.string.action_island_pure_music_keyword_edit),
        initialValue = editTarget.orEmpty(),
        label = stringResource(R.string.title_island_pure_music_keywords),
        onDismiss = { editTarget = null },
        onConfirm = { input ->
            val original = editTarget
            val edited = input.trim()
            // 改成空白等同没改，避免误把条目清掉；要删除请用垃圾桶。
            if (original != null && edited.isNotEmpty()) {
                update(keywords.map { if (it == original) edited else it })
            }
        },
    )
    SimpleDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.action_island_pure_music_keyword_delete),
        summary = stringResource(R.string.summary_island_pure_music_keyword_delete, deleteTarget.orEmpty()),
        onDismiss = { deleteTarget = null },
        onConfirm = { deleteTarget?.let { target -> update(keywords - target) } },
    )
    SimpleDialog(
        show = showResetDialog,
        title = stringResource(R.string.action_island_pure_music_keywords_reset),
        summary = stringResource(R.string.summary_island_pure_music_keywords_reset),
        onDismiss = { showResetDialog = false },
        onConfirm = { update(IslandPureMusicPolicy.DEFAULT_KEYWORDS) },
    )
}
