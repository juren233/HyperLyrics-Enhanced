/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

import android.media.session.PlaybackState
import io.github.proify.lyricon.provider.PlaybackStateActivityPolicy

/**
 * Keeps playback activity separate from timeline advancement.
 *
 * A buffering player still owns the active media session and should keep its lyric surface, but
 * its position must remain anchored until the player publishes a new PLAYING state. Treating both
 * concepts as the same boolean makes the Central ticker advance lyrics through a buffer stall.
 */
internal object PlaybackStatePositionPolicy {
    fun keepsSessionActive(state: Int): Boolean =
        PlaybackStateActivityPolicy.keepsSessionActive(state)

    fun advancesTimeline(state: Int): Boolean = state == PlaybackState.STATE_PLAYING

    fun positionAt(
        state: Int,
        basePosition: Long,
        lastUpdateTime: Long,
        playbackSpeed: Float,
        now: Long,
    ): Long {
        val safeBase = basePosition.coerceAtLeast(0L)
        if (!advancesTimeline(state) || lastUpdateTime <= 0L || playbackSpeed <= 0f) {
            return safeBase
        }
        val delta = (now - lastUpdateTime).coerceAtLeast(0L)
        return if (playbackSpeed == 1.0f) {
            safeBase + delta
        } else {
            safeBase + (delta * playbackSpeed).toLong()
        }
    }
}
