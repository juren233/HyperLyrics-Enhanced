/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.common.coroutines

import android.os.HandlerThread
import android.os.Looper

/**
 * The module's only way to create a thread (ModuleDispatchersUsageTest enforces it).
 *
 * A thread inherits its creator's context ClassLoader, and so does every platform thread created
 * lazily on it. kotlinx-coroutines sets its own threads' context loader to its own loader, which in
 * SystemUI is one module generation's: Conscrypt's process-lifetime `ConscryptStatsLog` thread,
 * started by the first TLS handshake on such a thread, kept that generation alive (250043 device
 * heap). Module threads therefore get the main thread's context loader, the value an ordinary thread
 * of the host process has; nothing in the APK reads a thread's context loader (250043 bytecode scan),
 * so only platform code sees it, exactly as on the host's own threads.
 */
internal object ModuleThreads {
    fun newThread(name: String, daemon: Boolean = true, task: Runnable): Thread =
        Thread(task, name).apply {
            isDaemon = daemon
            contextClassLoader = hostContextClassLoader()
        }

    fun handlerThread(name: String): HandlerThread =
        HandlerThread(name).apply { contextClassLoader = hostContextClassLoader() }

    fun hostContextClassLoader(): ClassLoader? = Looper.getMainLooper()?.thread?.contextClassLoader
}
