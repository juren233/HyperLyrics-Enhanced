/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import java.util.concurrent.Executor

/** Bounded natural-text widths. Measurement never holds the lock or blocks a cache miss. */
internal class PreparedWidthCache<K>(
    private val executor: Executor,
    private val capacity: Int = 512,
    recordOrigins: Boolean = false,
) {
    data class Lookup(val width: Float?, val backgroundPrepared: Boolean)
    private class Work<K>(val generation: Long, val keys: List<K>, val gate: WidthPreparationGate?, val measure: (K) -> Float) {
        var next = 0
    }
    private val lock = Any()
    private val values = LinkedHashMap<K, Float>(16, 0.75f, true)
    // Allocated only by diagnostic builds; kept under the same lock as the actual value.
    private val preparedKeys = if (recordOrigins) HashSet<K>() else null
    private var generation = 0L
    private var pending: Work<K>? = null
    private var scheduled = false
    private var gate: WidthPreparationGate? = null
    private val resumeListener: () -> Unit = { resume() }

    fun get(key: K): Float? = synchronized(lock) { values[key] }

    fun inspect(key: K): Lookup = synchronized(lock) {
        Lookup(values[key], preparedKeys?.contains(key) == true)
    }

    fun put(key: K, width: Float) = synchronized(lock) { store(key, width) }

    private fun store(key: K, width: Float, backgroundPrepared: Boolean = false) {
        values[key] = width
        if (backgroundPrepared) preparedKeys?.add(key) else preparedKeys?.remove(key)
        while (values.size > capacity) {
            val oldest = values.keys.first()
            values.remove(oldest)
            preparedKeys?.remove(oldest)
        }
    }

    fun clear() = synchronized(lock) {
        generation++
        pending = null
        values.clear()
        preparedKeys?.clear()
        gate?.unsubscribe(resumeListener)
        gate = null
    }

    fun prepare(keys: List<K>, gate: WidthPreparationGate? = null, measure: (K) -> Float) {
        synchronized(lock) {
            if (this.gate !== gate) {
                this.gate?.unsubscribe(resumeListener)
                this.gate = gate
                gate?.subscribe(resumeListener)
            }
            pending = Work(generation, keys.distinct().take(capacity), gate, measure)
        }
        resume()
    }

    private fun resume() {
        val launch = synchronized(lock) {
            val work = pending
            if (scheduled || work == null || work.gate?.isOpen == false) false
            else { scheduled = true; true }
        }
        if (launch) {
            try { executor.execute(::drain) }
            catch (_: java.util.concurrent.RejectedExecutionException) {
                synchronized(lock) { scheduled = false }
            }
        }
    }

    private fun drain() {
        while (true) {
            val work = synchronized(lock) {
                val next = pending
                pending = null
                if (next == null) scheduled = false
                next
            } ?: return
            while (work.next < work.keys.size) {
                if (synchronized(lock) { work.generation != generation || pending != null }) break
                if (work.gate?.isOpen == false) {
                    synchronized(lock) {
                        if (work.generation == generation && pending == null) pending = work
                        scheduled = false
                    }
                    // Covers reopening between the worker's permit check and parking.
                    resume()
                    return
                }
                val key = work.keys[work.next++]
                if (get(key) != null) continue
                val width = runCatching { work.measure(key) }.getOrNull() ?: continue
                synchronized(lock) {
                    if (work.generation == generation) store(key, width, backgroundPrepared = true)
                }
            }
        }
    }
}
