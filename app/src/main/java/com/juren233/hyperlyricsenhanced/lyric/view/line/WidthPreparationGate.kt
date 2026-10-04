/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

/** Motion is observed on the UI thread; workers only read this permit between rows. */
internal class WidthPreparationGate {
    @Volatile var isOpen = false
        private set
    var needsObservation = true
        private set
    private var quietFrames = 0
    private val listeners = mutableSetOf<() -> Unit>()

    @Synchronized fun subscribe(listener: () -> Unit) { listeners.add(listener) }
    @Synchronized fun unsubscribe(listener: () -> Unit) { listeners.remove(listener) }

    fun requestObservation() {
        if (!isOpen && !needsObservation) onTransitionStarted()
    }

    fun onTransitionStarted() {
        quietFrames = 0
        isOpen = false
        needsObservation = true
    }

    /** Unknown state does not authorize optional background work. Shape itself is irrelevant. */
    fun onFrame(moving: Boolean?) {
        if (moving != false) {
            onTransitionStarted()
            if (moving == null) needsObservation = false
            return
        }
        if (isOpen) return
        quietFrames++
        if (quietFrames < 2) return
        isOpen = true
        needsObservation = false
        val ready = synchronized(this) { listeners.toList() }
        ready.forEach { it() }
    }
}
