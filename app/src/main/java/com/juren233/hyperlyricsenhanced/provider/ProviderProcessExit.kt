/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context

/**
 * 判定留存崩溃标记的那次进程退出是否为应用自身崩溃（Java 未捕获异常或 native 崩溃）。
 * 用户划掉、强制停止、系统回收等退出不算插件导致的异常。
 */
object ProviderProcessExit {

    fun isCrashReason(reason: Int): Boolean =
        reason == ApplicationExitInfo.REASON_CRASH || reason == ApplicationExitInfo.REASON_CRASH_NATIVE

    /**
     * 查不到退出记录（旧格式标记没有 pid、系统已清理记录、取不到上下文）一律按「非崩溃」处理，
     * 宁可少计也不能因为用户操作停用插件。
     */
    fun previousExitWasCrash(packageName: String, marker: ProviderLoadMarker.MarkerState?): Boolean =
        previousExitReason(packageName, marker)?.let(::isCrashReason) ?: false

    /** 标记所属进程的退出原因（[ApplicationExitInfo] 的 REASON_*）；查不到为 null。 */
    fun previousExitReason(packageName: String, marker: ProviderLoadMarker.MarkerState?): Int? {
        if (marker == null || marker.pid <= 0) return null
        val context = targetContext() ?: return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val exits = runCatching {
            manager.getHistoricalProcessExitReasons(packageName, marker.pid, 1)
        }.getOrNull().orEmpty()
        // 记录必须发生在写入标记之后，避免 pid 复用时误取更早的进程。
        return exits.firstOrNull { it.timestamp >= marker.lastAttemptAtMs }?.reason
    }

    private fun targetContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        (activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Context)
            ?: activityThread.getDeclaredMethod("currentActivityThread").invoke(null)
                ?.let { thread -> thread.javaClass.getDeclaredMethod("getSystemContext").invoke(thread) as? Context }
    }.getOrNull()
}
