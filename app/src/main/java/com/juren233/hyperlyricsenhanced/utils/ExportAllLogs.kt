/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.utils

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.UIConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 「导出全部日志」：环境信息、模块设置、应用日志与模块日志合并成一个文件。
 * 设置页和日志页共用这一份实现，返回值用于发起导出（先让用户选择保存位置）。
 */
@Composable
fun rememberExportAllLogs(snackbarHostState: SnackbarHostState): () -> Unit {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val exportHeader = stringResource(R.string.export_header)
    val exportEnvironmentTitle = stringResource(R.string.export_environment_title)
    val exportModuleVersionFormat = stringResource(R.string.format_export_module_version)
    val exportEnabledPluginsTitle = stringResource(R.string.export_enabled_plugins_title)
    val exportEnabledPluginFormat = stringResource(R.string.format_export_enabled_plugin)
    val exportNone = stringResource(R.string.export_none)
    val exportDeviceModelFormat = stringResource(R.string.format_export_device_model)
    val exportSystemVersionFormat = stringResource(R.string.format_export_system_version)
    val exportAndroidVersionFormat = stringResource(R.string.format_export_android_version)
    val exportCurrentMusicAppFormat = stringResource(R.string.format_export_current_music_app)
    val exportMusicAppVersionFormat = stringResource(R.string.format_export_music_app_version)
    val exportMusicAppInstallSourceFormat = stringResource(R.string.format_export_music_app_install_source)
    val exportNotDetected = stringResource(R.string.export_not_detected)
    val exportUnknown = stringResource(R.string.export_unknown)
    val exportTimeFormat = stringResource(R.string.format_export_time)
    val exportSettingsTitle = stringResource(R.string.export_settings_title)
    val exportSettingsNote = stringResource(R.string.export_settings_note)
    val exportSettingLabels = LogExportSettingLabels(
        secretSet = stringResource(R.string.export_setting_secret_set),
        secretEmpty = stringResource(R.string.export_setting_secret_empty),
        defaultValue = stringResource(R.string.export_setting_default),
        hostOnlyFormat = stringResource(R.string.format_export_setting_host_only),
        customLengthFormat = stringResource(R.string.format_export_setting_custom_length),
        customValue = stringResource(R.string.export_setting_custom),
    )
    val appLogsTitle = stringResource(R.string.title_app_logs)
    val moduleLogsTitle = stringResource(R.string.title_module_logs)
    val noLogsFoundMsg = stringResource(R.string.no_logs_found)
    val exportSuccessMsg = stringResource(R.string.export_success)
    val exportFailedMsg = stringResource(R.string.format_export_failed)
    val exportAllLogsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Unable to open the selected file")
                    output.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.appendLine(exportHeader)
                        writer.appendLine(
                            String.format(
                                exportTimeFormat,
                                LocalDateTime.now().format(
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                )
                            )
                        )
                        writer.appendLine()

                        val environment = LogExportEnvironmentCollector.collect(context)
                        val musicApp = environment.musicApp
                        val musicAppIdentity = musicApp?.let { "${it.label} (${it.packageName})" }
                            ?: exportNotDetected
                        val musicAppVersion = musicApp?.let { app ->
                            val versionName = app.versionName.ifBlank { exportUnknown }
                            app.versionCode?.let { "$versionName ($it)" } ?: versionName
                        } ?: exportNotDetected
                        val installSource = when {
                            musicApp == null -> exportNotDetected
                            musicApp.installSourcePackageName == null -> exportUnknown
                            else -> {
                                val packageName = musicApp.installSourcePackageName
                                val label = musicApp.installSourceLabel
                                    ?.ifBlank { packageName }
                                    ?: packageName
                                "$label ($packageName)"
                            }
                        }

                        writer.appendLine("========== $exportEnvironmentTitle ==========")
                        writer.appendLine(
                            String.format(
                                exportModuleVersionFormat,
                                formatPluginVersion(environment.moduleVersionName, environment.moduleVersionCode),
                            )
                        )
                        if (environment.enabledPlugins.isEmpty()) {
                            writer.appendLine("$exportEnabledPluginsTitle: $exportNone")
                        } else {
                            writer.appendLine("$exportEnabledPluginsTitle:")
                            environment.enabledPlugins.forEach { plugin ->
                                writer.appendLine(
                                    String.format(
                                        exportEnabledPluginFormat,
                                        plugin.displayName,
                                        plugin.id,
                                        formatPluginVersion(plugin.versionName, plugin.versionCode),
                                    )
                                )
                            }
                        }
                        writer.appendLine(String.format(exportDeviceModelFormat, environment.deviceModel))
                        writer.appendLine(String.format(exportSystemVersionFormat, environment.systemVersion))
                        writer.appendLine(String.format(exportAndroidVersionFormat, environment.androidVersion))
                        writer.appendLine(String.format(exportCurrentMusicAppFormat, musicAppIdentity))
                        writer.appendLine(String.format(exportMusicAppVersionFormat, musicAppVersion))
                        writer.appendLine(String.format(exportMusicAppInstallSourceFormat, installSource))
                        writer.appendLine()

                        writer.appendLine("========== $exportSettingsTitle ==========")
                        writer.appendLine(exportSettingsNote)
                        val settingLines = LogExportSettings.lines(
                            context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE).all,
                            exportSettingLabels,
                        )
                        if (settingLines.isEmpty()) {
                            writer.appendLine(exportNone)
                        } else {
                            settingLines.forEach(writer::appendLine)
                        }
                        writer.appendLine()

                        writer.appendLine("========== $appLogsTitle ==========")
                        val appEntries = LogManager.exportLogs(
                            context = context,
                            isAppLog = true,
                            selectedLevel = LOG_EXPORT_LEVEL_DEBUG,
                            writer = writer,
                        )
                        if (appEntries == 0) writer.appendLine(noLogsFoundMsg)
                        writer.appendLine()

                        writer.appendLine("========== $moduleLogsTitle ==========")
                        val moduleEntries = LogManager.exportLogs(
                            context = context,
                            isAppLog = false,
                            selectedLevel = LOG_EXPORT_LEVEL_DEBUG,
                            writer = writer,
                        )
                        if (moduleEntries == 0) writer.appendLine(noLogsFoundMsg)
                    }
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(
                            message = exportSuccessMsg,
                            duration = SnackbarDuration.Custom(2000L),
                        )
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(
                            message = String.format(exportFailedMsg, e.message),
                            duration = SnackbarDuration.Custom(2000L),
                        )
                    }
                }
            }
        }
    )

    return remember(exportAllLogsLauncher) {
        {
            val dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
            exportAllLogsLauncher.launch("hyperlyricsenhanced_all_logs_$dateTime.txt")
        }
    }
}
