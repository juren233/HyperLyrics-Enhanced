/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.utils

import com.juren233.hyperlyricsenhanced.common.LogLevelPolicy

/** 发起导出的入口。 */
enum class LogExportSource {
    /** 查看日志页右上角的「导出日志」，只导出当前标签页。 */
    LOG_PAGE,

    /** 「导出全部日志」。 */
    EXPORT_ALL,
}

/** 导出前提示用户如何导出可用于反馈的日志。 */
enum class LogExportPrompt {
    /** Release 包：引导下载 Debug 包。 */
    RELEASE_BUILD,

    /** Debug 包但日志输出等级为一般日志：引导设为调试日志。 */
    ENABLE_DEBUG_LEVEL,

    /** Debug 包且为调试日志，但从日志页单独导出：引导改用「导出全部日志」。 */
    USE_EXPORT_ALL,
}

object LogExportGuidePolicy {
    /** 返回 null 表示直接导出，不弹提示。 */
    fun promptFor(source: LogExportSource, debugBuild: Boolean, logLevel: Int): LogExportPrompt? =
        when {
            !debugBuild -> LogExportPrompt.RELEASE_BUILD
            logLevel != LogLevelPolicy.LEVEL_DEBUG -> LogExportPrompt.ENABLE_DEBUG_LEVEL
            source == LogExportSource.LOG_PAGE -> LogExportPrompt.USE_EXPORT_ALL
            else -> null
        }
}
