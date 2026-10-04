/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.yoyo

/** One animation transaction; a bound line can resize while its entrance keeps playing. */
internal class ContentAnimationState(awaitingContent: Boolean) {
    var awaitingContent = awaitingContent
        private set
    var running = true
        private set

    fun claimContent(): Boolean {
        if (!running || !awaitingContent) return false
        awaitingContent = false
        return true
    }

    fun cancel(): Boolean {
        val mustApply = claimContent()
        finish()
        return mustApply
    }

    fun finish() {
        awaitingContent = false
        running = false
    }
}
