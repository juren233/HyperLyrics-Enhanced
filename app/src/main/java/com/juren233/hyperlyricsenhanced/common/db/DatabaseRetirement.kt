/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.common.db

import android.util.Log

/**
 * Closes one module generation's database once that generation is retired and no operation on it
 * is still running.
 *
 * After a SystemUI hot reload, every generation still alive keeps its own SQLite connection pool on
 * the shared database file (`dumpsys dbinfo` listed four, 2026-11-23). Work of a retired generation
 * that was already running finishes on its own dispatchers, so the pool cannot simply be closed at
 * retirement. Every database operation runs inside [operation], which counts it; [retire] closes the
 * database at once when the count is zero, otherwise the operation that brings it to zero does. The
 * decision is made under one lock with the count, so no operation can be inside when it closes.
 *
 * Once closed, a call that still arrives never runs: it returns its fallback, so it neither throws
 * into the host nor opens the database again ([android.database.sqlite.SQLiteOpenHelper] would
 * reopen on its next access). Outside SystemUI nothing retires, and nothing changes.
 */
internal class DatabaseRetirement(
    private val name: String,
    private val close: () -> Unit,
) {
    private val lock = Any()
    private var running = 0
    private var retiring = false
    private var closed = false
    private var lateCallReported = false

    val isClosed: Boolean get() = synchronized(lock) { closed }

    /** Runs [block] as one counted operation, or returns [fallback] once the database is closed. */
    fun <T> operation(fallback: T, block: () -> T): T {
        if (!enter()) return fallback
        try {
            return block()
        } finally {
            exit()
        }
    }

    /**
     * Counts an operation that spans several calls (a request answered later); every true result
     * must be paired with one [exit]. False once the database is closed.
     */
    fun enter(): Boolean {
        val report = synchronized(lock) {
            if (!closed) {
                running++
                return true
            }
            !lateCallReported.also { lateCallReported = true }
        }
        if (report) Log.i(TAG, "热重载：退役一代的数据库已关闭，迟到的调用不再执行：$name")
        return false
    }

    fun exit() {
        val closeNow = synchronized(lock) {
            check(running > 0) { "exit() without enter(): $name" }
            running--
            closeIfIdleLocked()
        }
        if (closeNow) closeDatabase()
    }

    /** The generation is retired: close now if idle, else when the last running operation ends. */
    fun retire() {
        val closeNow = synchronized(lock) {
            retiring = true
            closeIfIdleLocked()
        }
        if (closeNow) closeDatabase()
    }

    private fun closeIfIdleLocked(): Boolean {
        if (!retiring || closed || running > 0) return false
        closed = true
        return true
    }

    private fun closeDatabase() {
        runCatching(close)
            .onSuccess { Log.i(TAG, "热重载：退役一代已没有进行中的数据库操作，已关闭：$name") }
            .onFailure { Log.w(TAG, "热重载：退役一代的数据库关闭失败：$name", it) }
    }

    private companion object {
        const val TAG = "HyperLyricsEnhanced"
    }
}
