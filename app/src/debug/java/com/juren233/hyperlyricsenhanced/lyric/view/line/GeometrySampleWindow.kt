package com.juren233.hyperlyricsenhanced.lyric.view.line

import java.lang.ref.WeakReference

/** Numeric-only S0 samples. Never retain a model, token, content hash, Paint, or View. */
internal class GeometrySampleWindow(private val startedNs: Long) {
    companion object {
        const val MAX_QUERIES = 1024
        const val SAMPLE_STRIDE = 16
        const val MAX_SAMPLES = MAX_QUERIES / SAMPLE_STRIDE
        const val WINDOW_NS = 120_000_000_000L
    }

    data class Sample(
        val generation: Int, val reason: Int, val words: Int, val preparedWhole: Boolean,
        var utf16: Int = 0, var plainCalls: Int = 0, var mixedCalls: Int = 0,
        var wholeNs: Long = 0, var measurementNs: Long = 0, var positionNs: Long = 0,
        var totalNs: Long = 0, var setupNs: Long = 0,
        var failed: Boolean = false, var reentrant: Boolean = false,
    )

    private val models = ArrayList<WeakReference<Any>>(MAX_SAMPLES)
    private val rows = ArrayList<Sample>(MAX_SAMPLES)
    private var activeStart = 0L
    private var current: Sample? = null
    private var depth = 0
    val inProgress: Boolean get() = depth > 0
    val active: Sample? get() = if (depth == 1) current else null
    val reasonCounts = IntArray(8)
    var queries: Int = 0
        private set
    var closed: Boolean = false
        private set

    fun expired(now: Long): Boolean = now - startedNs >= WINDOW_NS || queries >= MAX_QUERIES

    fun begin(model: Any, reason: Int, words: Int, now: Long, preparedWhole: Boolean = false): Boolean {
        if (closed) return false
        depth++
        if (depth != 1) {
            current?.reentrant = true
            return false
        }
        if (expired(now)) return false
        val query = queries++
        reasonCounts[reason.coerceIn(0, reasonCounts.lastIndex)]++
        // Sample the first call, then rotate offsets; fixed every-16th aliases reset/bind pairs.
        val offset = ((query / SAMPLE_STRIDE) * 7) % SAMPLE_STRIDE
        if (query % SAMPLE_STRIDE != offset) return false
        var index = models.indexOfFirst { it.get() === model }
        if (index < 0) {
            index = models.size
            models.add(WeakReference(model))
        }
        current = Sample(index + 1, reason, words, preparedWhole)
        activeStart = now
        return true
    }

    fun startMeasurement(now: Long) {
        active?.setupNs = (now - activeStart).coerceAtLeast(0)
        activeStart = now
    }

    fun end(now: Long, failed: Boolean): Sample? {
        if (depth == 0) return null
        var completed: Sample? = null
        if (depth == 1) {
            current?.let { row ->
                row.totalNs = (now - activeStart).coerceAtLeast(0)
                row.failed = failed
                rows.add(row)
                completed = row
            }
            current = null
        }
        depth--
        return completed
    }

    /** Single-use drain releases every weak reference and stored sample. */
    fun close(): List<Sample> {
        closed = true
        current = null
        depth = 0
        models.clear()
        return rows.toList().also { rows.clear() }
    }
}
