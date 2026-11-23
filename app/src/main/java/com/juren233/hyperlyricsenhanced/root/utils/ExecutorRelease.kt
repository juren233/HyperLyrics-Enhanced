/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.utils

import java.util.concurrent.ExecutorService

/**
 * Stops the executor and drops queued tasks, then also calls [ExecutorService.shutdown].
 *
 * Android libcore's `Executors.newSingleThreadExecutor` wrapper registers a Cleaner action that
 * references the pool and therefore its thread factory. Only `shutdown()` unregisters that action
 * (`shutdownNow()` does not, unlike OpenJDK 21). The Cleaner list is a GC root, so a pool stopped
 * with `shutdownNow()` alone keeps the defining module ClassLoader reachable after hot reload.
 */
internal fun ExecutorService.shutdownNowAndRelease(): List<Runnable> {
    val dropped = shutdownNow()
    shutdown()
    return dropped
}
