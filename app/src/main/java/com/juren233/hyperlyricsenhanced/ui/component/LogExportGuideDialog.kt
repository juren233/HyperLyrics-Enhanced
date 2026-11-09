/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.component

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.LogLevelPolicy
import com.juren233.hyperlyricsenhanced.common.LogLevelPreference
import com.juren233.hyperlyricsenhanced.common.ProjectLinks
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.utils.LogExportGuidePolicy
import com.juren233.hyperlyricsenhanced.utils.LogExportPrompt
import com.juren233.hyperlyricsenhanced.utils.LogExportSource
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton

/** 导出日志前的反馈引导弹窗状态。 */
@Stable
class LogExportGuideState internal constructor() {
    internal var prompt by mutableStateOf<LogExportPrompt?>(null)
        private set
    internal var visible by mutableStateOf(false)
        private set
    private var pendingExport: (() -> Unit)? = null

    /** 按构建类型与日志输出等级决定直接导出，还是先弹窗提示。 */
    fun request(context: Context, source: LogExportSource, export: () -> Unit) {
        val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        val prompt = LogExportGuidePolicy.promptFor(
            source = source,
            debugBuild = BuildConfig.DEBUG,
            logLevel = LogLevelPreference.read(prefs),
        )
        if (prompt == null) {
            export()
            return
        }
        this.prompt = prompt
        pendingExport = export
        visible = true
    }

    internal fun dismiss() {
        visible = false
        pendingExport = null
    }

    internal fun continueExport() {
        val export = pendingExport
        dismiss()
        export?.invoke()
    }

    internal fun clear() {
        if (!visible) prompt = null
    }
}

@Composable
fun rememberLogExportGuideState(): LogExportGuideState = remember { LogExportGuideState() }

/**
 * @param onExportAll 「导出全部日志」；仅日志页的 [LogExportPrompt.USE_EXPORT_ALL] 会用到。
 */
@Composable
fun LogExportGuideDialog(
    state: LogExportGuideState,
    onExportAll: () -> Unit = {},
) {
    val context = LocalContext.current
    val prompt = state.prompt
    val title = when (prompt) {
        LogExportPrompt.RELEASE_BUILD -> stringResource(R.string.log_export_guide_release_title)
        LogExportPrompt.ENABLE_DEBUG_LEVEL,
        LogExportPrompt.USE_EXPORT_ALL -> stringResource(R.string.log_export_guide_feedback_title)
        null -> null
    }
    val summary = when (prompt) {
        LogExportPrompt.RELEASE_BUILD -> stringResource(R.string.log_export_guide_release_summary)
        LogExportPrompt.ENABLE_DEBUG_LEVEL -> stringResource(R.string.log_export_guide_enable_debug_summary)
        LogExportPrompt.USE_EXPORT_ALL -> stringResource(R.string.log_export_guide_use_export_all_summary)
        null -> null
    }
    val primaryText = when (prompt) {
        LogExportPrompt.RELEASE_BUILD -> stringResource(R.string.log_export_guide_release_download)
        LogExportPrompt.ENABLE_DEBUG_LEVEL -> stringResource(R.string.log_export_guide_enable_debug_action)
        LogExportPrompt.USE_EXPORT_ALL -> stringResource(R.string.export_all_logs)
        null -> ""
    }

    AppWindowDialog(
        show = state.visible,
        title = title,
        summary = summary,
        onDismissRequest = state::dismiss,
        onDismissFinished = state::clear,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                text = primaryText,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    state.dismiss()
                    when (prompt) {
                        LogExportPrompt.RELEASE_BUILD -> runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, ProjectLinks.RELEASES.toUri()))
                        }
                        LogExportPrompt.ENABLE_DEBUG_LEVEL ->
                            LogLevelPreference.write(LogLevelPolicy.LEVEL_DEBUG)
                        LogExportPrompt.USE_EXPORT_ALL -> onExportAll()
                        null -> Unit
                    }
                },
            )
            TextButton(
                text = stringResource(R.string.log_export_guide_continue),
                modifier = Modifier.fillMaxWidth(),
                onClick = state::continueExport,
            )
            TextButton(
                text = stringResource(R.string.cancel),
                modifier = Modifier.fillMaxWidth(),
                onClick = state::dismiss,
            )
        }
    }
}
