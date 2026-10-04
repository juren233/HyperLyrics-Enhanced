/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import java.lang.reflect.Method

/** Aggregate pending-dispatch accounting is deliberately not an input: it can be stale. */
internal fun isIslandContentMotionRunning(selfAnimating: Boolean, windowAnimating: Boolean): Boolean =
    selfAnimating || windowAnimating

internal class IslandContentMotionReader(
    private val delegateGetter: Method,
    private val selfRunning: Method,
    private val windowRunning: Method,
) {
    fun isAnimating(realHost: Any): Boolean? = runCatching {
        val delegate = delegateGetter.invoke(realHost) ?: return false
        isIslandContentMotionRunning(
            selfAnimating = selfRunning.invoke(delegate) as Boolean,
            windowAnimating = windowRunning.invoke(delegate) as Boolean,
        )
    }.getOrNull()
}

/** Pending presentation work, never a cached lyric, timestamp, or native width result. */
internal class IslandDeferredContentState<K> {
    var content = false
        private set
    var seek = false
        private set
    var width = false
        private set
    var protectLyricLottie = true
        private set
    private var quietFrames = 0
    private val actions = LinkedHashMap<K, () -> Unit>()

    fun merge(content: Boolean = false, width: Boolean = false, protectLyricLottie: Boolean = true, seek: Boolean = false) {
        this.content = this.content || content
        this.seek = this.seek || seek
        this.width = this.width || width
        if (width) this.protectLyricLottie = this.protectLyricLottie && protectLyricLottie
    }

    fun replaceAction(key: K, action: () -> Unit) { actions[key] = action }

    /** Cancellation can be followed by a new animation before the next frame. */
    fun onFrame(animating: Boolean): Boolean {
        quietFrames = if (animating) 0 else quietFrames + 1
        return quietFrames >= 2
    }

    fun takeActions(): List<() -> Unit> = actions.values.toList().also { actions.clear() }
}
