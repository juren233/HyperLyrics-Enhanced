/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

/** Viewport interpolation is geometry; only check classification once that morph is released. */
internal class IslandShortLyricWidthRefresh {
    private var pending = false

    fun onWidthChanged(morphRunning: Boolean): Boolean {
        pending = morphRunning
        return !morphRunning
    }

    fun onMorphFinished(): Boolean = pending.also { pending = false }

    fun clear() { pending = false }
}
