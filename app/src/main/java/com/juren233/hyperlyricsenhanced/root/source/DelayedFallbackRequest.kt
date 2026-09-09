/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns one delayed fallback query through delivery. The shared request mutex remains in
 * the query adapter; this monitor never waits for that mutex or for a job to finish.
 *
 * Posting is asynchronous. Delivery and invalidation share this monitor, so cancellation
 * also rejects a result whose coroutine finished before its main-thread delivery ran.
 * Callbacks must not block or wait for another thread to call this owner.
 */
internal class DelayedFallbackRequest<T>(
    private val scope: CoroutineScope,
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
) {
    private var generation = 0
    private var delayed: Runnable? = null
    private var delivery: Runnable? = null
    private var job: Job? = null

    data class Snapshot(
        val generation: Int,
        val delayed: Boolean,
        val running: Boolean,
        val awaitingDelivery: Boolean,
    ) {
        // Query completion is not publication: duplicate track events must preserve a
        // result already queued for delivery, subject to the consumer's source policy.
        val pending: Boolean get() = delayed || running || awaitingDelivery
    }

    @Synchronized
    fun snapshot() = Snapshot(
        generation = generation,
        delayed = delayed != null,
        running = job?.isActive == true,
        awaitingDelivery = delivery != null,
    )

    @Synchronized
    fun cancel() {
        generation++
        delayed?.let(remove)
        delayed = null
        delivery?.let(remove)
        delivery = null
        job?.cancel()
        job = null
    }

    @Synchronized
    fun schedule(
        delayMs: Long,
        query: suspend () -> T,
        apply: (Int, T) -> Unit,
        failed: (Exception) -> Unit,
    ): Int {
        cancel()
        val requestGeneration = generation
        val start = Runnable {
            synchronized(this) {
                if (requestGeneration != generation) return@Runnable
                delayed = null
                // Register before launch, including with an immediate test dispatcher.
                val pendingJob = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val result = query()
                        synchronized(this@DelayedFallbackRequest) {
                            if (requestGeneration != generation) return@launch
                            val publish = Runnable publish@{
                                synchronized(this@DelayedFallbackRequest) {
                                    if (requestGeneration != generation || delivery == null) return@publish
                                    delivery = null
                                    job = null
                                    apply(requestGeneration, result)
                                }
                            }
                            delivery = publish
                            post(publish, 0L)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failed(error)
                    }
                }
                job = pendingJob
                pendingJob.invokeOnCompletion {
                    synchronized(this) {
                        if (job === pendingJob) job = null
                    }
                }
                pendingJob.start()
            }
        }
        delayed = start
        post(start, delayMs.coerceAtLeast(0L))
        return requestGeneration
    }
}
