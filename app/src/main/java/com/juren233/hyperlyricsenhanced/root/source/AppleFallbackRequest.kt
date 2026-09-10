/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.source

import kotlinx.coroutines.Job

/**
 * Owns the Apple Music online-fallback request: its delay runnable, worker job and generation.
 *
 * Deliberately not the shared [DelayedFallbackRequest]: the Apple path posts its apply step
 * through the main handler while holding the shared request mutex's critical section, and it
 * reports a staged diagnostic trail. Reusing the generic owner would change that timing.
 *
 * The fields stay plain and main-thread confined, exactly like the inline declarations this
 * replaces: [invalidate] and every mutator run on the main thread, while [isCurrent] is also
 * read from the worker coroutine. No monitor is introduced, so no new lock ordering appears
 * around [LyriconSource.fallbackRequestMutex].
 */
internal class AppleFallbackRequest {
    private var generation = 0
    private var delayRunnable: Runnable? = null
    private var job: Job? = null

    data class Snapshot(
        val generation: Int,
        val jobActive: Boolean,
        val delayPending: Boolean,
    ) {
        val pending: Boolean get() = jobActive || delayPending
    }

    fun snapshot(): Snapshot = Snapshot(
        generation = generation,
        jobActive = job?.isActive == true,
        delayPending = delayRunnable != null,
    )

    fun generation(): Int = generation

    /**
     * Rejects any in-flight result and clears the current request, returning the new
     * generation. [remove] detaches the pending delay callback.
     */
    fun invalidate(remove: (Runnable) -> Unit): Int {
        generation += 1
        delayRunnable?.let(remove)
        delayRunnable = null
        job?.cancel()
        job = null
        return generation
    }

    /** True while [expected] is still the newest generation. */
    fun isCurrent(expected: Int): Boolean = expected == generation

    fun registerDelay(runnable: Runnable) {
        delayRunnable = runnable
    }

    /** Clears the delay slot only if [runnable] still owns it. */
    fun clearDelayIfCurrent(runnable: Runnable) {
        if (delayRunnable === runnable) delayRunnable = null
    }

    fun attachJob(newJob: Job) {
        job = newJob
    }

    fun clearJob() {
        job = null
    }
}
