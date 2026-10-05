@file:Suppress("UNUSED_PARAMETER")

package com.juren233.hyperlyricsenhanced.lyric.view.line

/** No collector, Android logging, clock, model registry, or retained data in Release. */
internal object GeometryDiagnostics {
    const val sampling = false
    fun setHostLogger(logger: ((String) -> Unit)?) = Unit
    fun begin(model: Any, reason: Int, words: Int, preparedWhole: Boolean): Boolean = false
    fun end(failed: Boolean) = Unit
    fun whole(elapsed: Long) = Unit
    fun reuse(status: Int) = Unit
    fun word(utf16: Int, measurementNs: Long, positionNs: Long) = Unit
    fun paintCall(mixed: Boolean) = Unit
}
