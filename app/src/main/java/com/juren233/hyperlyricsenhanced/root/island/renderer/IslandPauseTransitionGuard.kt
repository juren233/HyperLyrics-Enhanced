/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island.renderer

internal class IslandPauseTransitionGuard {
    @Volatile
    var nativeRestorePending: Boolean = false
        private set

    private var nativeRestoreCommitted: Boolean = false

    @Synchronized
    fun onPlaybackStateChanged(isPlaying: Boolean, pauseBehavior: Int): Transition = when {
        isPlaying -> {
            nativeRestorePending = false
            nativeRestoreCommitted = false
            Transition.RESUME
        }

        pauseBehavior == RESTORE_NATIVE_BEHAVIOR && nativeRestoreCommitted -> {
            Transition.NATIVE_RESTORE_ALREADY_COMMITTED
        }

        pauseBehavior == RESTORE_NATIVE_BEHAVIOR && !nativeRestorePending -> {
            nativeRestorePending = true
            Transition.DEFER_NATIVE_RESTORE
        }

        pauseBehavior == RESTORE_NATIVE_BEHAVIOR -> Transition.NATIVE_RESTORE_ALREADY_PENDING

        else -> {
            nativeRestorePending = false
            nativeRestoreCommitted = false
            Transition.KEEP_LYRICS
        }
    }

    @Synchronized
    fun consumeNativeRestore(playbackActive: Boolean): Boolean {
        if (!nativeRestorePending || playbackActive) return false
        nativeRestorePending = false
        nativeRestoreCommitted = true
        return true
    }

    @Synchronized
    fun reset() {
        nativeRestorePending = false
        nativeRestoreCommitted = false
    }

    enum class Transition {
        RESUME,
        DEFER_NATIVE_RESTORE,
        NATIVE_RESTORE_ALREADY_PENDING,
        NATIVE_RESTORE_ALREADY_COMMITTED,
        KEEP_LYRICS,
    }

    companion object {
        const val NATIVE_RESTORE_DELAY_MS = 800L
        private const val RESTORE_NATIVE_BEHAVIOR = 0
    }
}
