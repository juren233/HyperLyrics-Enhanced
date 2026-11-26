/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.page.log

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogAnalysisAutoRun
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogAnalysisRunner
import com.juren233.hyperlyricsenhanced.utils.loganalysis.LogAnalysisReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * [report] 为 null 表示还没分析完过一次；[running] 时卡片保留上一次结果，只在右上角转圈。
 */
internal data class LogAnalysisUiState(
    val report: LogAnalysisReport? = null,
    val running: Boolean = false,
)

/**
 * 分析结果放在进程级，滑出屏幕、进出子页面都不丢；进设置页时在后台自动刷新，
 * 读日志和查状态都在 IO 线程，不阻塞页面。
 */
internal object LogAnalysisStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastCompletedAtMillis: Long? = null

    var state by mutableStateOf(LogAnalysisUiState())
        private set

    fun refresh(context: Context, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!LogAnalysisAutoRun.shouldStart(state.running, lastCompletedAtMillis, now, force)) return
        state = state.copy(running = true)
        val appContext = context.applicationContext
        scope.launch {
            val result = runCatching { LogAnalysisRunner.run(appContext) }
            lastCompletedAtMillis = SystemClock.elapsedRealtime()
            state = LogAnalysisUiState(report = LogAnalysisAutoRun.reportOf(result), running = false)
        }
    }
}
