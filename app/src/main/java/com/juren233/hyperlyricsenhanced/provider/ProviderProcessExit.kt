/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger

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
    fun previousExitReason(packageName: String, marker: ProviderLoadMarker.MarkerState?): Int? =
        previousExit(packageName, marker)?.reason

    /** 标记所属进程的退出记录；查不到为 null。 */
    fun previousExit(packageName: String, marker: ProviderLoadMarker.MarkerState?): ApplicationExitInfo? {
        if (marker == null || marker.pid <= 0) return null
        // 先走系统服务直连：不构建任何 Context，onPackageLoaded 阶段也不会触发 LoadedApk 新建与框架重入。
        val exits = queryViaActivityService(packageName, marker.pid)
            ?: queryViaContext(packageName, marker.pid)
            ?: return null
        // 记录必须发生在写入标记之后，避免 pid 复用时误取更早的进程。
        return exits.firstOrNull { it.timestamp >= marker.lastAttemptAtMs }
    }

    /**
     * 值得写进模块日志的退出：崩溃与 ANR。native 崩溃与 ANR 不经过 Java 未捕获异常分发，
     * 进程内抓不到，只能在下次启动时从系统退出记录里补记。
     */
    fun isAbnormalReason(reason: Int): Boolean =
        isCrashReason(reason) || reason == ApplicationExitInfo.REASON_ANR

    fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        ApplicationExitInfo.REASON_ANR -> "anr"
        else -> "reason_$reason"
    }

    /** native 崩溃时 status 为信号编号（如 11 = SIGSEGV），其余情况为退出码。 */
    fun describeExit(reason: Int, status: Int, description: String?, timestamp: Long, pid: Int): String =
        "reason=${reasonLabel(reason)} status=$status pid=$pid timestamp=$timestamp " +
            "description=${description?.trim()?.takeIf(String::isNotEmpty) ?: "none"}"

    /**
     * 反射调用 IActivityManager.getHistoricalProcessExitReasons(String, int, int, int)
     * （AOSP android11-release 与 main 的 AIDL 签名一致）。失败返回 null，由调用方回退。
     */
    private fun queryViaActivityService(packageName: String, pid: Int): List<ApplicationExitInfo>? =
        runCatching {
            // 只用普通反射，不走 HiddenApiBypass：4.3 的 getDeclaredMethod(s) 依赖 Unsafe 改写 ART 内部字段，
            // 在 Android 17 上疑似直接 native 崩溃（issue #66 canary.8/9）。被 hidden API 限制挡住时
            // 这里抛异常返回 null，回退到建系统 Context 的路径，那条路径的重入已由两层拦截兜住。
            val service = ActivityManager::class.java.getDeclaredMethod("getService").invoke(null)!!
            val query = service.javaClass.methods.first {
                it.name == "getHistoricalProcessExitReasons" && it.parameterTypes.size == 4
            }
            val userId = android.os.Process.myUid() / PER_USER_RANGE
            val slice = query.invoke(service, packageName, pid, 1, userId) ?: return@runCatching emptyList()
            // getList 是 BaseParceledListSlice 的 public 方法，getMethod 会沿继承链找到。
            @Suppress("UNCHECKED_CAST")
            slice.javaClass.getMethod("getList").invoke(slice) as List<ApplicationExitInfo>
        }.onFailure {
            // 仅 debug 构建记录，走 HookLogger 以便出现在导出的模块日志里；
            // 直连失败会回退到建系统 Context 的旧路径。
            if (BuildConfig.DEBUG) {
                HookLogger.w(TAG, "直连 ActivityManager 查询退出原因失败，回退 Context 路径", it)
            }
        }.getOrNull()

    private fun queryViaContext(packageName: String, pid: Int): List<ApplicationExitInfo>? {
        val context = targetContext() ?: return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching {
            manager.getHistoricalProcessExitReasons(packageName, pid, 1)
        }.getOrNull().orEmpty()
    }

    private const val TAG = "ProviderProcessExit"
    private const val PER_USER_RANGE = 100000

    private fun targetContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        (activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Context)
            ?: activityThread.getDeclaredMethod("currentActivityThread").invoke(null)
                ?.let { thread -> thread.javaClass.getDeclaredMethod("getSystemContext").invoke(thread) as? Context }
    }.getOrNull()
}
