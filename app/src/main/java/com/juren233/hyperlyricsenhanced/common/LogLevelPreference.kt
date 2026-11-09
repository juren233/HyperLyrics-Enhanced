/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.BuildConfig

/** 应用进程内读写「日志输出等级」。读取口径与日志写入一致（按 [LogLevelPolicy.effectiveLevel]）。 */
object LogLevelPreference {
    fun read(prefs: SharedPreferences, debugBuild: Boolean = BuildConfig.DEBUG): Int {
        val storedLevel = prefs.takeIf { it.contains(UIConstants.KEY_LOG_LEVEL) }
            ?.getInt(UIConstants.KEY_LOG_LEVEL, UIConstants.DEFAULT_LOG_LEVEL)
        val storedBuildKind = prefs.getString(UIConstants.KEY_LOG_LEVEL_BUILD_KIND, null)
        return LogLevelPolicy.effectiveLevel(
            storedLevel = storedLevel,
            storedBuildKind = storedBuildKind,
            debugBuild = debugBuild,
        )
    }

    /** 写入并同步到 SystemUI 等宿主进程。 */
    fun write(level: Int) {
        PrefsBridge.putInt(UIConstants.KEY_LOG_LEVEL, level)
    }
}
