/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.utils

import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 把目标 App 的 Java 崩溃调用栈写进模块日志。
 *
 * 崩溃栈默认只进 logcat 的 crash 缓冲区，「导出全部日志」看不到，用户只能另外抓 logcat
 * （issue #66：Apple Music 启动即闪退，导出日志里没有任何线索）。这里在崩溃分发入口
 * 先记一条 HookLogger.e，再原样交给原处理流程，不吞异常、不改变崩溃行为。
 *
 * 优先 Hook `Thread.dispatchUncaughtException`：所有未捕获异常都经由它分发，
 * 不受目标 App 自己替换默认处理器（且不回链）的影响；Hook 失败时退回包装默认处理器。
 */
internal object TargetCrashRecorder {
    private const val TAG = "CrashRecorder"

    private val installed = AtomicBoolean(false)
    private val recorded = AtomicBoolean(false)

    fun install(module: XposedModule, packageName: String, processName: String) {
        if (!installed.compareAndSet(false, true)) return
        val context = CrashContext(packageName, processName)
        runCatching {
            val dispatch = HiddenApiBypass.getDeclaredMethod(
                Thread::class.java,
                "dispatchUncaughtException",
                Throwable::class.java,
            )
            module.hook(dispatch).intercept(DispatchHook(context))
        }.onFailure { error ->
            HookLogger.w(TAG, "崩溃分发 Hook 安装失败，改为包装默认处理器: process=$processName", error)
            runCatching { wrapDefaultHandler(context) }.onFailure {
                HookLogger.e(TAG, "崩溃记录安装失败: process=$processName", it)
            }
        }
    }

    internal data class CrashContext(val packageName: String, val processName: String)

    private class DispatchHook(private val context: CrashContext) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val error = chain.args.firstOrNull() as? Throwable
            if (error != null) record(context, chain.thisObject as? Thread, error)
            return chain.proceed()
        }
    }

    private fun wrapDefaultHandler(context: CrashContext) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(context, thread, error)
            previous?.uncaughtException(thread, error)
        }
    }

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
