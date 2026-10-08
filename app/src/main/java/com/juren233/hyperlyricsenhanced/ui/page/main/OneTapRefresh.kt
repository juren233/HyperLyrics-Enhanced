/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.main

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
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TextButton

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
                    colors = ButtonDefaults.textButtonColorsPrimary(),
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
                state = ToggleableState(selected),
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
    private val snackbarHostState: SnackbarHostState,
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
     */
    fun open(appleMusicOnly: Boolean = false) {
        targets = OneTapRefreshCatalog.refreshTargets(
            packageManager = LocaleUtils.systemLanguageContext(context).packageManager,
            appleMusicOnly = appleMusicOnly,
        )
        selectedIds = OneTapRefreshSelectionPolicy.defaultSelection(targets)
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
                    duration = SnackbarDuration.Custom(2000L),
                )
            }
        }
    }
}

@Composable
internal fun rememberOneTapRefreshController(
    snackbarHostState: SnackbarHostState,
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
