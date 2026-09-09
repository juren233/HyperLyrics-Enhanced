/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One invalidation boundary for the worker, race results and deferred sentence commit. */
internal class OnlineTranslationRequest<P> {
    private var generation = 0
    private var attempt: String? = null
    private var job: Job? = null
    private var firstPublished: Int? = null
    private var firstAccepted: Int? = null
    private var pending: P? = null

    data class Snapshot<P>(
        val generation: Int,
        val attempt: String?,
        val running: Boolean,
        val firstPublished: Int?,
        val firstAccepted: Int?,
        val pending: P?,
    )

    @Synchronized fun snapshot() = Snapshot(
        generation, attempt, job?.isActive == true, firstPublished, firstAccepted, pending,
    )

    @Synchronized fun begin(key: String): Int? {
        if (attempt == key) return null
        invalidate()
        attempt = key
        return generation
    }

    /** Register before starting, so synchronous completion cannot overwrite a newer job. */
    @Synchronized fun launch(scope: CoroutineScope, token: Int, work: suspend CoroutineScope.() -> Unit) {
        if (token != generation) return
        val launched = scope.launch(start = CoroutineStart.LAZY, block = work)
        job = launched
        launched.invokeOnCompletion {
            synchronized(this) { if (job === launched) job = null }
        }
        launched.start()
    }

    /** Cancellation and delivery are serialized, including already-dequeued results. */
    @Synchronized fun deliver(token: Int, apply: () -> Unit): Boolean {
        if (token != generation) return false
        apply()
        return true
    }

    @Synchronized fun markFirstPublished(token: Int) {
        if (token == generation) firstPublished = token
    }

    @Synchronized fun markFirstAccepted(token: Int) {
        if (token == generation) firstAccepted = token
    }

    @Synchronized fun defer(token: Int, result: P) {
        if (token == generation) pending = result
    }

    @Synchronized fun takePending(ready: (P) -> Boolean): P? {
        val result = pending ?: return null
        if (!ready(result)) return null
        pending = null
        return result
    }

    @Synchronized fun clearPending(token: Int) {
        if (token == generation) pending = null
    }

    @Synchronized fun cancel(clearAttempt: Boolean) {
        invalidate()
        if (clearAttempt) attempt = null
    }

    private fun invalidate() {
        generation += 1
        val previous = job
        job = null
        firstPublished = null
        firstAccepted = null
        pending = null
        previous?.cancel()
    }
}
