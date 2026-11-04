/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.utils

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把目标 App 的 Java 崩溃调用栈写进模块日志。
 *
 * 崩溃栈默认只进 logcat 的 crash 缓冲区，「导出全部日志」看不到，用户只能另外抓 logcat
 * （issue #66：Apple Music 启动即闪退，导出日志里没有任何线索）。这里包装进程的默认未捕获异常
 * 处理器：先记一条 HookLogger.e，再原样交给原处理器，不吞异常、不改变崩溃行为。
 *
 * 只用公开 API：目标进程里不 Hook 系统核心方法，也不走 HiddenApiBypass。
 * HiddenApiBypass 4.3 的 getDeclaredMethod(s) 依赖 Unsafe 改写 ART 内部字段，
 * 在 Android 17 上疑似直接 native 崩溃（issue #66 canary.9 进程死在这里的旧实现里）。
 * 代价是目标 App 之后自行替换默认处理器且不回链时记不到，主流崩溃统计 SDK 都会回链。
 */
internal object TargetCrashRecorder {
    private const val TAG = "CrashRecorder"

    private val installed = AtomicBoolean(false)
    private val recorded = AtomicBoolean(false)

    fun install(packageName: String, processName: String) {
        if (!installed.compareAndSet(false, true)) return
        val context = CrashContext(packageName, processName)
        runCatching {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                record(context, thread, error)
                previous?.uncaughtException(thread, error)
            }
        }.onFailure {
            HookLogger.e(TAG, "崩溃记录安装失败: process=$processName", it)
        }
    }

    internal data class CrashContext(val packageName: String, val processName: String)

    /** 每个进程只记第一次：崩溃处理过程中再抛出的异常不重复刷栈。 */
    internal fun record(context: CrashContext, thread: Thread?, error: Throwable) {
        if (!recorded.compareAndSet(false, true)) return
        val pid = runCatching { android.os.Process.myPid() }.getOrDefault(-1)
        runCatching { HookLogger.e(TAG, describe(context, thread, pid), error) }
    }

    internal fun describe(context: CrashContext, thread: Thread?, pid: Int): String =
        "目标进程未捕获异常，即将崩溃: package=${context.packageName} " +
            "process=${context.processName} pid=$pid thread=${thread?.name ?: "unknown"}"

    /** 仅供单测重置进程级状态。 */
    internal fun resetForTest() {
        installed.set(false)
        recorded.set(false)
    }
}
