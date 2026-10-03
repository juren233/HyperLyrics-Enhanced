/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.reload

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A timed-out queued reload must never start clearing the still-running generation later. */
internal class ReloadTask<T>(private val action: () -> T) : Runnable {
    private val phase = AtomicInteger(0)
    private val finished = CountDownLatch(1)
    private var result: Result<T>? = null

    override fun run() {
        if (!phase.compareAndSet(0, 1)) return
        try {
            result = runCatching(action)
        } finally {
            phase.set(2)
            finished.countDown()
        }
    }

    fun cancelBeforeStart(): Boolean = phase.compareAndSet(0, 3)

    fun await(timeoutMs: Long): T? {
        var interrupted = false
        val completed = try {
            finished.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            interrupted = true
            false
        }
        if (!completed) {
            if (cancelBeforeStart() || phase.get() == 3) {
                if (interrupted) Thread.currentThread().interrupt()
                return null
            }
            // Once teardown starts, its completion is the commit boundary. Returning early
            // would let the framework detach the API while old UI cleanup is still running.
            while (true) {
                try {
                    finished.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return result!!.getOrThrow()
    }
}
