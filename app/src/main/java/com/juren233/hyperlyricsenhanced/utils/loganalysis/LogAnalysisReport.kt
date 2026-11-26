/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.utils.loganalysis

import kotlin.coroutines.cancellation.CancellationException

/** 状态板上的检查项，按显示顺序排列。 */
internal enum class LogCheck { MODULE, SCOPE, VERSION, RUNTIME, PLUGIN, FEATURES, NOTIFICATION, LOGS }

internal enum class LogCheckStatus { ERROR, WARNING, PASS, SKIPPED }

/** 某一项没法检查或不需要检查的原因，状态板上显示成灰色的说明。 */
internal enum class LogCheckSkipReason {
    MODULE_INACTIVE,
    SCOPE_UNKNOWN,
    LOGS_UNREADABLE,
    NOT_PLAYING,
    PLAYER_HAS_NO_PLUGIN,
    FEATURES_OFF,
    ANALYSIS_FAILED,
}

internal data class LogCheckResult(
    val check: LogCheck,
    val status: LogCheckStatus,
    val findings: List<LogFinding> = emptyList(),
    val skipReason: LogCheckSkipReason? = null,
)

/** 卡片头部的总体状态：决定大图标颜色和状态语。 */
internal enum class LogAnalysisHealth { CLEAN, WARNINGS_ONLY, HAS_ERRORS }

/** 一次分析的完整结果：每个检查项一行，报错 / 警告 / 通过按检查项计数。 */
internal data class LogAnalysisReport(val checks: List<LogCheckResult>) {
    val errorCount: Int get() = checks.count { it.status == LogCheckStatus.ERROR }
    val warningCount: Int get() = checks.count { it.status == LogCheckStatus.WARNING }
    val passCount: Int get() = checks.count { it.status == LogCheckStatus.PASS }
    val checkedCount: Int get() = checks.count { it.status != LogCheckStatus.SKIPPED }
    val findings: List<LogFinding> get() = checks.flatMap { it.findings }

    val health: LogAnalysisHealth
        get() = when {
            errorCount > 0 -> LogAnalysisHealth.HAS_ERRORS
            warningCount > 0 -> LogAnalysisHealth.WARNINGS_ONLY
            else -> LogAnalysisHealth.CLEAN
        }

    operator fun get(check: LogCheck): LogCheckResult = checks.first { it.check == check }

    companion object {
        /** 分析过程本身出错：不能显示成「一切正常」，日志一项记警告，其余标成没检查。 */
        fun failed(message: String): LogAnalysisReport {
            val status = ModuleLogSourceStatus.ReadFailed(message)
            val finding = LogFinding.LogsUnreadable(status, listOf(LogEvidence.SourceError(status)))
            return LogAnalysisReport(
                LogCheck.entries.map { check ->
                    if (check == LogCheck.LOGS) {
                        LogCheckResult(check, LogCheckStatus.WARNING, listOf(finding))
                    } else {
                        LogCheckResult(check, LogCheckStatus.SKIPPED, skipReason = LogCheckSkipReason.ANALYSIS_FAILED)
                    }
                },
            )
        }
    }
}

/** 卡片常显后什么时候自动重新分析。 */
internal object LogAnalysisAutoRun {
    /** 上次分析完不到这么久就不重复跑，避免来回进出设置页时反复读日志。 */
    const val MIN_INTERVAL_MILLIS = 10_000L

    fun shouldStart(running: Boolean, lastCompletedAtMillis: Long?, nowMillis: Long, force: Boolean): Boolean {
        if (running) return false
        if (force || lastCompletedAtMillis == null) return true
        return nowMillis - lastCompletedAtMillis >= MIN_INTERVAL_MILLIS
    }

    fun reportOf(result: Result<LogAnalysisReport>): LogAnalysisReport = result.getOrElse { error ->
        if (error is CancellationException) throw error
        LogAnalysisReport.failed(error.message ?: error::class.java.simpleName)
    }
}
