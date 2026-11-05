/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.main

import com.juren233.hyperlyricsenhanced.ui.skin.SkinSnackbarHostState
import com.juren233.hyperlyricsenhanced.ui.skin.BasicComponent
import com.juren233.hyperlyricsenhanced.ui.skin.SkinSnackbarHost
import com.juren233.hyperlyricsenhanced.ui.skin.TextButton
import com.juren233.hyperlyricsenhanced.ui.skin.rememberSkinSnackbarHostState
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog
import com.juren233.hyperlyricsenhanced.root.utils.ShellUtils
import com.juren233.hyperlyricsenhanced.ui.component.AppWindowDialog
import com.juren233.hyperlyricsenhanced.ui.utils.LocaleUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import com.juren233.hyperlyricsenhanced.ui.skin.Checkbox
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import com.juren233.hyperlyricsenhanced.ui.skin.LocalUiStyle
import com.juren233.hyperlyricsenhanced.ui.skin.SkinTheme
import com.juren233.hyperlyricsenhanced.ui.skin.Text
import com.juren233.hyperlyricsenhanced.ui.skin.isGlasmio
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import kotlinx.coroutines.delay

internal data class OneTapRefreshMusicApp(
    val packageName: String,
    val displayName: String,
)

/** 一键刷新的可选目标。 */
internal data class OneTapRefreshTargets(
    val musicApps: List<OneTapRefreshMusicApp>,
    /** 是否提供“全部音乐应用”聚合项；只保留 Apple Music 时该项与单项重复，不再列出。 */
    val showAllMusicAppsOption: Boolean,
)

internal object OneTapRefreshCatalog {
    private val knownMusicPackages = buildSet {
        add(OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME)
        OfficialProviderCatalog.definitions.forEach { definition ->
            addAll(definition.targetPackages)
        }
    }

    fun installedMusicApps(packageManager: PackageManager): List<OneTapRefreshMusicApp> =
        installedMusicApps { packageName ->
            val info = runCatching {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L),
                )
            }.getOrNull() ?: return@installedMusicApps null
            runCatching { info.loadLabel(packageManager).toString() }.getOrDefault(packageName)
        }

    internal fun installedMusicApps(
        installedLabel: (String) -> String?,
    ): List<OneTapRefreshMusicApp> = knownMusicPackages.mapNotNull { packageName ->
        val label = installedLabel(packageName) ?: return@mapNotNull null
        OneTapRefreshMusicApp(packageName, label.ifBlank { packageName })
    }

    /**
     * 当前语境下的刷新目标。
     *
     * 主页隐藏时 Apple Music 体验优化页即首页，刷新面板只保留“系统界面”与“Apple Music”两个选项；
     * 主页仍保留时维持原有列表。
     */
    fun refreshTargets(
        packageManager: PackageManager,
        appleMusicOnly: Boolean,
    ): OneTapRefreshTargets = refreshTargets(installedMusicApps(packageManager), appleMusicOnly)

    internal fun refreshTargets(
        installedMusicApps: List<OneTapRefreshMusicApp>,
        appleMusicOnly: Boolean,
    ): OneTapRefreshTargets =
        if (appleMusicOnly) {
            OneTapRefreshTargets(
                musicApps = installedMusicApps.filter {
                    it.packageName == OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME
                },
                showAllMusicAppsOption = false,
            )
        } else {
            OneTapRefreshTargets(
                musicApps = installedMusicApps,
                showAllMusicAppsOption = true,
            )
        }
}

internal object OneTapRefreshSelectionPolicy {
    const val SYSTEM_UI_ID = "__system_ui__"
    const val ALL_MUSIC_APPS_ID = "__all_music_apps__"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /** 打开面板时默认勾选系统界面与全部音乐App；无聚合项时逐个勾选列出的音乐App。 */
    fun defaultSelection(targets: OneTapRefreshTargets): Set<String> = buildSet {
        add(SYSTEM_UI_ID)
        if (targets.musicApps.isEmpty()) return@buildSet
        if (targets.showAllMusicAppsOption) {
            add(ALL_MUSIC_APPS_ID)
        } else {
            targets.musicApps.mapTo(this, OneTapRefreshMusicApp::packageName)
        }
    }

    fun toggle(
        selectedIds: Set<String>,
        targetId: String,
        musicAppIds: Set<String>,
    ): Set<String> {
        val updated = selectedIds.toMutableSet()
        when (targetId) {
            SYSTEM_UI_ID -> updated.toggle(SYSTEM_UI_ID)
            ALL_MUSIC_APPS_ID -> {
                if (ALL_MUSIC_APPS_ID in updated) {
                    updated.remove(ALL_MUSIC_APPS_ID)
                } else {
                    updated.removeAll(musicAppIds)
                    updated.add(ALL_MUSIC_APPS_ID)
                }
            }
            in musicAppIds -> {
                updated.remove(ALL_MUSIC_APPS_ID)
                updated.toggle(targetId)
            }
            else -> return selectedIds
        }
        return updated
    }

    fun selectedPackages(
        selectedIds: Set<String>,
        musicApps: List<OneTapRefreshMusicApp>,
    ): List<String> = buildList {
        if (SYSTEM_UI_ID in selectedIds) add(SYSTEM_UI_PACKAGE)
        if (ALL_MUSIC_APPS_ID in selectedIds) {
            addAll(musicApps.map(OneTapRefreshMusicApp::packageName))
        } else {
            musicApps.forEach { app ->
                if (app.packageName in selectedIds) add(app.packageName)
            }
        }
    }.distinct()

    private fun MutableSet<String>.toggle(value: String) {
        if (!add(value)) remove(value)
    }
}

/** Glasmio Sheet 收起动画的等待时长，之后再执行刷新。 */
private const val SHEET_DISMISS_SETTLE_MILLIS = 450L

@Composable
internal fun OneTapRefreshDialog(
    show: Boolean,
    hasRootAccess: Boolean?,
    musicApps: List<OneTapRefreshMusicApp>,
    showAllMusicAppsOption: Boolean,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (LocalUiStyle.current.isGlasmio) {
        // Glasmio 的 Sheet 不提供“退场结束”回调：面板关闭后等一个 Sheet 收起时长再结束进程。
        LaunchedEffect(show) {
            if (!show) {
                delay(SHEET_DISMISS_SETTLE_MILLIS)
                onDismissFinished()
            }
        }
        GlasmioBottomSheet(
            onDismissRequest = onDismiss,
            visible = show,
            fitContent = true,
            title = stringResource(R.string.title_one_tap_refresh),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                Text(
                    text = stringResource(R.string.title_one_tap_refresh),
                    color = SkinTheme.colors.onBackground,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                if (hasRootAccess == false) {
                    Text(
                        text = stringResource(R.string.summary_one_tap_refresh_root_required),
                        color = SkinTheme.colors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    GlasmioGroupedList {
                        GlasmioListRow(
                            title = stringResource(R.string.option_refresh_system_ui),
                            onClick = { onToggle(OneTapRefreshSelectionPolicy.SYSTEM_UI_ID) },
                            trailing = {
                                Checkbox(
                                    checked = OneTapRefreshSelectionPolicy.SYSTEM_UI_ID in selectedIds,
                                    onClick = { onToggle(OneTapRefreshSelectionPolicy.SYSTEM_UI_ID) },
                                )
                            },
                        )
                        if (musicApps.isNotEmpty()) {
                            if (showAllMusicAppsOption) {
                                GlasmioListRow(
                                    title = stringResource(R.string.option_refresh_all_music_apps),
                                    onClick = { onToggle(OneTapRefreshSelectionPolicy.ALL_MUSIC_APPS_ID) },
                                    trailing = {
                                        Checkbox(
                                            checked = OneTapRefreshSelectionPolicy.ALL_MUSIC_APPS_ID in selectedIds,
                                            onClick = { onToggle(OneTapRefreshSelectionPolicy.ALL_MUSIC_APPS_ID) },
                                        )
                                    },
                                )
                            }
                            musicApps.forEach { app ->
                                GlasmioListRow(
                                    title = app.displayName,
                                    onClick = { onToggle(app.packageName) },
                                    trailing = {
                                        Checkbox(
                                            checked = app.packageName in selectedIds,
                                            onClick = { onToggle(app.packageName) },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.confirm),
                        primary = true,
                        enabled = selectedIds.isNotEmpty(),
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        return
    }
    AppWindowDialog(
        title = stringResource(R.string.title_one_tap_refresh),
        // 仅在确认无 root 时提示；null 表示检查进行中，保持上一次结果避免副标题闪现
        summary = if (hasRootAccess == false) {
            stringResource(R.string.summary_one_tap_refresh_root_required)
        } else {
            null
        },
        show = show,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OneTapRefreshOption(
                    title = stringResource(R.string.option_refresh_system_ui),
                    selected = OneTapRefreshSelectionPolicy.SYSTEM_UI_ID in selectedIds,
                    onClick = { onToggle(OneTapRefreshSelectionPolicy.SYSTEM_UI_ID) },
                )
                if (musicApps.isNotEmpty()) {
                    if (showAllMusicAppsOption) {
                        OneTapRefreshOption(
                            title = stringResource(R.string.option_refresh_all_music_apps),
                            selected = OneTapRefreshSelectionPolicy.ALL_MUSIC_APPS_ID in selectedIds,
                            onClick = { onToggle(OneTapRefreshSelectionPolicy.ALL_MUSIC_APPS_ID) },
                        )
                    }
                    musicApps.forEach { app ->
                        OneTapRefreshOption(
                            title = app.displayName,
                            selected = app.packageName in selectedIds,
                            onClick = { onToggle(app.packageName) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.confirm),
                    primary = true,
                    enabled = selectedIds.isNotEmpty(),
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun OneTapRefreshOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        onClick = onClick,
        endActions = {
            Checkbox(
 checked = selected,
 onClick = onClick,
            )
        },
    )
}

/**
 * 一键刷新的状态与副作用。
 *
 * 主页顶栏与 Lyricon 配置页的悬浮按钮共用同一份逻辑：选中目标后关闭面板，
 * 面板退场动画结束再按选中结果结束对应进程，与主页原有行为保持一致。
 */
@Stable
internal class OneTapRefreshController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val snackbarHostState: SkinSnackbarHostState,
    private val noRootMessage: String,
) {
    var showDialog by mutableStateOf(false)
        private set

    var hasRootAccess by mutableStateOf<Boolean?>(null)
        private set

    var targets by mutableStateOf(OneTapRefreshTargets(emptyList(), showAllMusicAppsOption = true))
        private set

    var selectedIds by mutableStateOf(emptySet<String>())
        private set

    private var pendingPackages = emptyList<String>()
    private var rootCheckSequence = 0L

    private val musicAppIds: Set<String>
        get() = targets.musicApps.mapTo(linkedSetOf(), OneTapRefreshMusicApp::packageName)

    /**
     * 打开刷新面板。
     *
     * [appleMusicOnly] 为 true 时只保留系统界面与 Apple Music 两个选项。
     * [preselectedPackages] 非空时只预先勾选其中列出的音乐 App（如插件更新通知指向的 App），
     * 列表里没有任何一个时退回默认勾选。
     */
    fun open(appleMusicOnly: Boolean = false, preselectedPackages: Collection<String>? = null) {
        targets = OneTapRefreshCatalog.refreshTargets(
            packageManager = LocaleUtils.systemLanguageContext(context).packageManager,
            appleMusicOnly = appleMusicOnly,
        )
        val preselected = preselectedPackages
            ?.filterTo(linkedSetOf()) { it in musicAppIds }
            .orEmpty()
        selectedIds = preselected.ifEmpty { OneTapRefreshSelectionPolicy.defaultSelection(targets) }
        showDialog = true
        rootCheckSequence += 1L
        val checkSequence = rootCheckSequence
        scope.launch {
            val rootAccess = ShellUtils.hasRootAccess()
            // 只接受本次打开对应的检查结果，避免连续打开面板时旧结果覆盖新结果。
            if (rootCheckSequence == checkSequence) {
                hasRootAccess = rootAccess
            }
        }
    }

    fun toggle(targetId: String) {
        selectedIds = OneTapRefreshSelectionPolicy.toggle(
            selectedIds = selectedIds,
            targetId = targetId,
            musicAppIds = musicAppIds,
        )
    }

    fun dismiss() {
        showDialog = false
    }

    fun confirm() {
        val selectedPackages = OneTapRefreshSelectionPolicy.selectedPackages(
            selectedIds = selectedIds,
            musicApps = targets.musicApps,
        )
        if (selectedPackages.isNotEmpty()) {
            pendingPackages = selectedPackages
            showDialog = false
        }
    }

    /** 面板退场动画结束后再结束进程，避免动画期间界面直接消失。 */
    fun onDismissFinished() {
        val selectedPackages = pendingPackages
        pendingPackages = emptyList()
        if (selectedPackages.isEmpty()) {
            return
        }
        scope.launch {
            val success = ShellUtils.killAppProcesses(selectedPackages)
            if (!success) {
                snackbarHostState.showSnackbar(
                    message = noRootMessage,
                    durationMillis = 2000L,
                )
            }
        }
    }
}

@Composable
internal fun rememberOneTapRefreshController(
    snackbarHostState: SkinSnackbarHostState,
): OneTapRefreshController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noRootMessage = stringResource(R.string.toast_one_tap_refresh_no_root)
    return remember(context, snackbarHostState, noRootMessage) {
        OneTapRefreshController(
            context = context,
            scope = scope,
            snackbarHostState = snackbarHostState,
            noRootMessage = noRootMessage,
        )
    }
}

/** 一键刷新面板，主页与 Lyricon 配置页共用。 */
@Composable
internal fun OneTapRefreshHost(controller: OneTapRefreshController) {
    OneTapRefreshDialog(
        show = controller.showDialog,
        hasRootAccess = controller.hasRootAccess,
        musicApps = controller.targets.musicApps,
        showAllMusicAppsOption = controller.targets.showAllMusicAppsOption,
        selectedIds = controller.selectedIds,
        onToggle = controller::toggle,
        onDismiss = controller::dismiss,
        onDismissFinished = controller::onDismissFinished,
        onConfirm = controller::confirm,
    )
}
