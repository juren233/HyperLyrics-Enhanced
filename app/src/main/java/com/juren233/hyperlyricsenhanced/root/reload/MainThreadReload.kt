/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.reload

import android.os.Handler
import android.os.Looper

internal object MainThreadReload {
    fun <T> run(action: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val handler = Handler(Looper.getMainLooper())
        val task = ReloadTask(action)
        if (!handler.post(task)) return null
        return try {
            task.await(5_000L)
        } finally {
            task.cancelBeforeStart()
            handler.removeCallbacks(task)
        }
    }
}
