/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.main

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

internal data class OneTapRefreshMusicApp(
    val packageName: String,
    val displayName: String,
)

internal object OneTapRefreshCatalog {
    private val knownMusicApps = buildList {
        val addedPackages = linkedSetOf<String>()

        fun addKnownApp(packageName: String, displayName: String) {
            if (addedPackages.add(packageName)) {
                add(OneTapRefreshMusicApp(packageName, displayName))
            }
        }

        addKnownApp(OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME, "Apple Music")
        OfficialProviderCatalog.definitions.forEach { definition ->
            definition.targetPackages.forEach { packageName ->
                addKnownApp(packageName, definition.displayNameForPackage(packageName))
            }
        }
    }

    fun installedMusicApps(packageManager: PackageManager): List<OneTapRefreshMusicApp> =
        installedMusicApps(
            knownMusicApps.mapNotNullTo(linkedSetOf()) { app ->
                runCatching {
                    packageManager.getApplicationInfo(
                        app.packageName,
                        PackageManager.ApplicationInfoFlags.of(0L),
                    )
                    app.packageName
                }.getOrNull()
            },
        )

    internal fun installedMusicApps(
        installedPackages: Set<String>,
    ): List<OneTapRefreshMusicApp> = knownMusicApps.filter { app ->
        app.packageName in installedPackages
    }
}

internal object OneTapRefreshSelectionPolicy {
    const val SYSTEM_UI_ID = "__system_ui__"
    const val ALL_MUSIC_APPS_ID = "__all_music_apps__"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

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
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        title = stringResource(R.string.title_one_tap_refresh),
        summary = if (hasRootAccess == true) {
            null
        } else {
            stringResource(R.string.summary_one_tap_refresh_root_required)
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
                    OneTapRefreshOption(
                        title = stringResource(R.string.option_refresh_all_music_apps),
                        selected = OneTapRefreshSelectionPolicy.ALL_MUSIC_APPS_ID in selectedIds,
                        onClick = { onToggle(OneTapRefreshSelectionPolicy.ALL_MUSIC_APPS_ID) },
                    )
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
