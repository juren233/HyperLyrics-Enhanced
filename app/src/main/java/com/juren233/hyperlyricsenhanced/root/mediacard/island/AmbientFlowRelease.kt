/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.island

/**
 * Invalidate work immediately, but retain state until its views have been restored/removed.
 * The dispatcher must run cleanup on the main thread (inline when already there).
 * Callbacks must not acquire a second lifecycle lock or wait for the worker to finish.
 * This orders a terminal release; it does not provide reinitialization or reject new binds.
 */
internal fun releaseAmbientFlowResources(
    dispatchCleanup: (Runnable) -> Unit,
    invalidateRequests: () -> Unit,
    cleanupViews: () -> Unit,
    clearStates: () -> Unit,
    shutdownWorker: () -> Unit,
) {
    invalidateRequests()
    dispatchCleanup(Runnable {
        cleanupViews()
        clearStates()
    })
    shutdownWorker()
}
