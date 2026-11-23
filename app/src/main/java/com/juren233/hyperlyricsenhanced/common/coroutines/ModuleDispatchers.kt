/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.common.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The module's only background dispatchers; `Dispatchers.Default` and `Dispatchers.IO` are never
 * used (ModuleDispatchersUsageTest enforces it).
 *
 * In SystemUI the module's kotlinx-coroutines belongs to one module generation. The global
 * dispatchers' core workers never exit, so they kept a retired generation alive, and shutting them
 * down made every dispatch still in flight fail: a cancelled coroutine must still dispatch to finish,
 * and that `RejectedExecutionException` crashed SystemUI (250042, 2026-11-23). These pools are never
 * shut down, so no dispatch is ever rejected; every thread, core threads included, exits after
 * [IDLE_THREAD_KEEP_ALIVE_SECONDS] without work, so a retired generation's threads are gone once its
 * last work has finished. Sizes follow the user decision of 2026-11-23: Default has one thread per
 * CPU core, IO up to 64.
 */
internal object ModuleDispatchers {
    const val IDLE_THREAD_KEEP_ALIVE_SECONDS = 60L
    const val IO_MAX_THREADS = 64
    val DEFAULT_THREADS: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private val defaultPool = Pool("Default", DEFAULT_THREADS)
    private val ioPool = Pool("IO", IO_MAX_THREADS)

    /**
     * CPU-bound work. Static, because the build rewrites the Lyricon libraries'
     * `Dispatchers.getDefault()` calls to this getter (LyriconDispatcherRewrite in app/build.gradle.kts).
     */
    @JvmStatic
    val Default: CoroutineDispatcher = defaultPool.asCoroutineDispatcher()

    /** Blocking work: disk, network, IPC. */
    val IO: CoroutineDispatcher = ioPool.asCoroutineDispatcher()

    /**
     * Retiring generation: runs [action] after every task either pool completes from now on. Work
     * still in flight keeps running to its end; this lets the reload release what that work leaves
     * behind (an HTTP connection returned to its pool) without waiting for it.
     */
    fun afterEveryTask(action: Runnable) {
        defaultPool.afterTask = action
        ioPool.afterTask = action
    }

    private class Pool(name: String, threads: Int) : ThreadPoolExecutor(
        threads,
        threads,
        IDLE_THREAD_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        daemonThreads("HyperLyrics Enhanced-$name"),
    ) {
        @Volatile
        var afterTask: Runnable? = null

        init {
            allowCoreThreadTimeOut(true)
        }

        override fun afterExecute(r: Runnable?, t: Throwable?) {
            super.afterExecute(r, t)
            afterTask?.let { runCatching { it.run() } }
        }
    }

    private fun daemonThreads(prefix: String): ThreadFactory {
        val next = AtomicInteger(1)
        return ThreadFactory { task ->
            ModuleThreads.newThread("$prefix-${next.getAndIncrement()}", task = task)
        }
    }
}
