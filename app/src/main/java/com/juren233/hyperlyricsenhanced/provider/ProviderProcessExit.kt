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
import org.lsposed.hiddenapibypass.HiddenApiBypass

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
        // 先走系统服务直连：不构建任何 Context，onPackageLoaded 阶段也不会触发 LoadedApk 新建与框架重入。
        val exits = queryViaActivityService(packageName, marker.pid)
            ?: queryViaContext(packageName, marker.pid)
            ?: return null
        // 记录必须发生在写入标记之后，避免 pid 复用时误取更早的进程。
        return exits.firstOrNull { it.timestamp >= marker.lastAttemptAtMs }?.reason
    }

    /**
     * 反射调用 IActivityManager.getHistoricalProcessExitReasons(String, int, int, int)
     * （AOSP android11-release 与 main 的 AIDL 签名一致）。失败返回 null，由调用方回退。
     */
    private fun queryViaActivityService(packageName: String, pid: Int): List<ApplicationExitInfo>? =
        runCatching {
            // 目标进程按普通 App 受 hidden API 限制；HiddenApiBypass 只对这几次反射取得的 Method 解除限制，
            // 不改动进程级豁免名单，不会覆盖同进程其它模块的设置。
            val getService = HiddenApiBypass.getDeclaredMethod(ActivityManager::class.java, "getService")
            val service = getService.invoke(null)!!
            val query = HiddenApiBypass.getDeclaredMethod(
                Class.forName("android.app.IActivityManager"),
                "getHistoricalProcessExitReasons",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            val userId = android.os.Process.myUid() / PER_USER_RANGE
            val slice = query.invoke(service, packageName, pid, 1, userId) ?: return@runCatching emptyList()
            // getList 在新版本里声明于父类 BaseParceledListSlice，沿继承链查找声明处。
            val getList = generateSequence<Class<*>>(slice.javaClass) { it.superclass }
                .firstNotNullOf { type ->
                    @Suppress("UNCHECKED_CAST")
                    (HiddenApiBypass.getDeclaredMethods(type) as List<java.lang.reflect.Method>).firstOrNull {
                        it.name == "getList" && it.parameterCount == 0
                    }
                }
            @Suppress("UNCHECKED_CAST")
            getList.invoke(slice) as List<ApplicationExitInfo>
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
