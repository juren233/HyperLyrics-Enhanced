/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.reload

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether this module generation is still the one serving its process.
 *
 * A SystemUI hot reload retires the generation once its teardown has finished. Work still in flight
 * then runs to its end on the generation's own dispatchers, which are never shut down; its results
 * must not leave the generation. Every exit through which results leave it asks [mayPublish]:
 * the lyric sink (SystemUI's island, AOD and media card), the Apple Music direct bridge, the Lyricon
 * central's deliveries and registration callbacks, the module's broadcasts and the app-process
 * content provider call. Outside SystemUI no generation is ever retired.
 */
internal object ModuleGeneration {
    private const val TAG = "HyperLyricsEnhanced"

    @Volatile
    private var retired = false
    private val reported = ConcurrentHashMap.newKeySet<String>()

    val isRetired: Boolean get() = retired

    /** End of the hot reload teardown, before anything of this generation can resume. */
    fun retire() {
        retired = true
    }

    /** Unit tests share one JVM, so a test that retires the generation must restore it. */
    internal fun resetForTest() {
        retired = false
        reported.clear()
    }

    /** False once retired; each [exit] that refuses a result is logged once. */
    fun mayPublish(exit: String): Boolean {
        if (!retired) return true
        if (reported.add(exit)) Log.i(TAG, "热重载：已退役的一代不再对外发布，已拦截：$exit")
        return false
    }
}
