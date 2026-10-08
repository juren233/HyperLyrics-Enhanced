/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaController.PlaybackInfo
import android.media.VolumeProvider
import kotlin.math.abs

/**
 * One live drag. The level is derived from the total horizontal travel since the press,
 * never accumulated per move, so dragging back before release returns to the start level.
 * [tick] receives how many 10% boundaries of the range an update crossed, in either direction.
 */
internal class IslandTouchContinuousVolume(
    private val base: Int,
    private val min: Int,
    private val max: Int,
    private val stepPx: Float,
    private val tick: (Int) -> Unit = {},
    private val apply: (Int) -> Unit,
) {
    private var current = base

    fun update(dx: Float) {
        val level = level(base, min, max, dx, stepPx)
        if (level == current) return
        val crossed = abs(decile(level, min, max) - decile(current, min, max))
        current = level
        apply(level)
        if (crossed > 0) tick(crossed)
    }

    companion object {
        /** A full sweep of the range spans this distance; finer ranges keep a usable minimum step. */
        private const val FULL_RANGE_DP = 200f
        private const val MIN_STEP_DP = 2f

        fun level(base: Int, min: Int, max: Int, dx: Float, stepPx: Float): Int =
            (base + (dx / stepPx).toInt()).coerceIn(min, max)

        /** Index of the 10% band containing [level]; changes exactly when a boundary is crossed. */
        fun decile(level: Int, min: Int, max: Int): Int =
            ((level - min) * 10L / (max - min).coerceAtLeast(1)).toInt()

        fun stepPx(min: Int, max: Int, density: Float): Float =
            maxOf(FULL_RANGE_DP * density / (max - min).coerceAtLeast(1), MIN_STEP_DP * density)

        /** Follows the session's own volume, like [IslandTouchMediaActions] volume steps. */
        fun start(context: Context, controller: MediaController?, density: Float,
            tick: (Int) -> Unit): IslandTouchContinuousVolume? {
            val info = controller?.playbackInfo
            if (info?.playbackType == PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
                if (info.volumeControl == VolumeProvider.VOLUME_CONTROL_FIXED || info.maxVolume <= 0) return null
                return IslandTouchContinuousVolume(info.currentVolume, 0, info.maxVolume,
                    stepPx(0, info.maxVolume, density), tick) { controller.setVolumeTo(it, AudioManager.FLAG_SHOW_UI) }
            }
            val audio = context.getSystemService(AudioManager::class.java) ?: return null
            val stream = AudioManager.STREAM_MUSIC
            val min = audio.getStreamMinVolume(stream)
            val max = audio.getStreamMaxVolume(stream)
            if (max <= min) return null
            return IslandTouchContinuousVolume(audio.getStreamVolume(stream).coerceIn(min, max), min, max,
                stepPx(min, max, density), tick) { audio.setStreamVolume(stream, it, AudioManager.FLAG_SHOW_UI) }
        }
    }
}
