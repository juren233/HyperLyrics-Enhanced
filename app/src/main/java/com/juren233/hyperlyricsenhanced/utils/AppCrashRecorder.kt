/*
 * Copyright 2026 juren233
 */
package com.juren233.hyperlyricsenhanced.utils

import android.content.Context
import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.UIConstants

/**
 * 仅 Debug 构建：把本进程未捕获异常的完整堆栈写入应用日志（随「导出日志」导出），
 * 然后交还给原有处理器，不改变崩溃行为。Release 构建不安装。
 */
object AppCrashRecorder {
    private const val TAG = "AppCrash"

    fun install(context: Context) {
        if (!BuildConfig.DEBUG) return
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val prefs = appContext.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
                val style = prefs.getInt(UIConstants.KEY_UI_STYLE, UIConstants.DEFAULT_UI_STYLE)
                LogManager.e(
                    TAG,
                    "未捕获异常 thread=${thread.name} uiStyle=$style\n" + Log.getStackTraceString(throwable),
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
