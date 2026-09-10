/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.source

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleFallbackRequestTest {
    /** A started coroutine that never completes, so isActive stays true. */
    private fun runningJob() = GlobalScope.launch(start = CoroutineStart.LAZY) {
        awaitCancellation()
    }.also { it.start() }

    @Test
    fun `invalidate advances the generation and detaches the pending delay`() {
        val request = AppleFallbackRequest()
        assertEquals(0, request.generation())

        val removed = mutableListOf<Runnable>()
        request.registerDelay(Runnable { })
        val generation = request.invalidate(removed::add)

        assertEquals(1, generation)
        // Detaching hands the callback back so the caller can remove the main-thread post.
        assertEquals(1, removed.size)
        assertEquals(1, request.snapshot().generation)
        assertFalse(request.snapshot().delayPending)
        assertFalse(request.snapshot().pending)
    }

    @Test
    fun `only the newest generation is current`() {
        val request = AppleFallbackRequest()
        val first = request.invalidate {}
        assertTrue(request.isCurrent(first))
        val second = request.invalidate {}
        assertFalse(request.isCurrent(first))
        assertTrue(request.isCurrent(second))
    }

    @Test
    fun `clearDelayIfCurrent leaves a newer delay slot untouched`() {
        val request = AppleFallbackRequest()
        val old = Runnable { }
        val newer = Runnable { }
        request.registerDelay(old)
        request.registerDelay(newer)
        // A stale worker finishing must not clear the newer registration.
        request.clearDelayIfCurrent(old)
        assertTrue(request.snapshot().delayPending)
        request.clearDelayIfCurrent(newer)
        assertFalse(request.snapshot().delayPending)
    }

    @Test
    fun `job activity is reported and job is cleared`() {
        val request = AppleFallbackRequest()
        assertFalse(request.snapshot().jobActive)

        val job = runningJob()
        request.attachJob(job)
        assertTrue(request.snapshot().jobActive)

        request.clearJob()
        assertFalse(request.snapshot().jobActive)
        job.cancel()
    }

    @Test
    fun `invalidate cancels the active job and clears pending`() {
        val request = AppleFallbackRequest()
        val job = runningJob()
        request.attachJob(job)
        assertTrue(request.snapshot().pending)

        request.invalidate {}
        assertFalse(request.snapshot().jobActive)
        assertFalse(request.snapshot().pending)
        assertTrue(job.isCancelled)
    }
}
