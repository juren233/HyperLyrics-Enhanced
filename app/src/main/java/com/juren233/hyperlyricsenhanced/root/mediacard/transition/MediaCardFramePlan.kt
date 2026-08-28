/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.transition

import com.juren233.hyperlyricsenhanced.root.mediacard.MediaCardFullAodTransitionMode
import kotlin.math.roundToInt

/** A complete same-frame render transaction. No field is driven by a second Animator. */
internal data class MediaCardFramePlan(
    val fraction: Float,
    val targetFullAod: Boolean,
    val mode: MediaCardFullAodTransitionMode,
    val rootAlpha: Float,
    val rootTranslationY: Float,
    val rootScaleY: Float,
    val lyricVisible: Boolean,
    val headerAlpha: Float,
    val headerTranslationY: Float,
    val groupAlphas: List<Float>,
    val progressAlpha: Float,
    val elapsedAlpha: Float,
    val totalAlpha: Float,
    val actionsAlpha: Float,
    val targetCardHeight: Int?,
    val secondaryTextSizeSp: Float?,
    val secondaryTopOffsetPx: Int?,
    val secondaryAlpha: Int?,
    val secondaryVisible: Boolean,
    val stableAfterCommit: Boolean,
) {
    init {
        require(groupAlphas.size == 3) { "Unified media lyric root requires 3 fixed groups" }
    }

    companion object {
        fun stable(
            targetFullAod: Boolean,
            mode: MediaCardFullAodTransitionMode,
            cardHeight: Int?,
            keepSecondLyric: Boolean,
        ): MediaCardFramePlan = if (targetFullAod) {
            val keep = if (mode == MediaCardFullAodTransitionMode.PAUSED_RESTORE_NATIVE) 0f else 1f
            MediaCardFramePlan(
                fraction = 1f,
                targetFullAod = true,
                mode = mode,
                rootAlpha = keep,
                rootTranslationY = 0f,
                rootScaleY = 1f,
                lyricVisible = keep > 0f,
                headerAlpha = 1f,
                headerTranslationY = 0f,
                groupAlphas = listOf(keep, if (keepSecondLyric) keep else 0f, 0f),
                progressAlpha = 0f,
                elapsedAlpha = 0f,
                totalAlpha = 0f,
                actionsAlpha = if (mode == MediaCardFullAodTransitionMode.PAUSED_RESTORE_NATIVE) 1f else 0f,
                targetCardHeight = cardHeight,
                secondaryTextSizeSp = null,
                secondaryTopOffsetPx = null,
                secondaryAlpha = null,
                secondaryVisible = keepSecondLyric && keep > 0f,
                stableAfterCommit = true,
            )
        } else {
            MediaCardFramePlan(
                fraction = 1f,
                targetFullAod = false,
                mode = mode,
                rootAlpha = 1f,
                rootTranslationY = 0f,
                rootScaleY = 1f,
                lyricVisible = true,
                headerAlpha = 1f,
                headerTranslationY = 0f,
                groupAlphas = listOf(1f, 1f, 1f),
                progressAlpha = 1f,
                elapsedAlpha = 1f,
                totalAlpha = 1f,
                actionsAlpha = 1f,
                targetCardHeight = cardHeight,
                secondaryTextSizeSp = null,
                secondaryTopOffsetPx = null,
                secondaryAlpha = null,
                secondaryVisible = true,
                stableAfterCommit = true,
            )
        }

        fun interpolate(
            fraction: Float,
            targetFullAod: Boolean,
            mode: MediaCardFullAodTransitionMode,
            startCardHeight: Int?,
            targetCardHeight: Int?,
            keepSecondLyric: Boolean,
            secondaryTextSizeSp: Float?,
            secondaryTopOffsetPx: Int?,
            secondaryAlpha: Int?,
            secondaryVisible: Boolean,
        ): MediaCardFramePlan {
            val p = fraction.coerceIn(0f, 1f)
            val a = if (targetFullAod) 1f - p else p
            val rootAlpha = if (mode == MediaCardFullAodTransitionMode.PAUSED_RESTORE_NATIVE) a else 1f
            val second = if (keepSecondLyric) rootAlpha else if (targetFullAod) 1f - p else p
            val third = if (targetFullAod) 1f - p else p
            val actionAlpha = if (mode == MediaCardFullAodTransitionMode.PAUSED_RESTORE_NATIVE) 1f else a
            return MediaCardFramePlan(
                fraction = p,
                targetFullAod = targetFullAod,
                mode = mode,
                rootAlpha = rootAlpha,
                rootTranslationY = 0f,
                rootScaleY = 1f,
                lyricVisible = rootAlpha > 0.001f || second > 0.001f || third > 0.001f,
                headerAlpha = 1f,
                headerTranslationY = 0f,
                groupAlphas = listOf(rootAlpha, second, third),
                progressAlpha = a,
                elapsedAlpha = a,
                totalAlpha = a,
                actionsAlpha = actionAlpha,
                targetCardHeight = lerp(startCardHeight, targetCardHeight, p),
                secondaryTextSizeSp = secondaryTextSizeSp,
                secondaryTopOffsetPx = secondaryTopOffsetPx,
                secondaryAlpha = secondaryAlpha,
                secondaryVisible = secondaryVisible && second > 0.001f,
                stableAfterCommit = false,
            )
        }

        private fun lerp(start: Int?, end: Int?, p: Float): Int? {
            if (start == null && end == null) return null
            if (start == null) return end
            if (end == null) return start
            return (start + (end - start) * p).roundToInt().coerceAtLeast(0)
        }
    }
}
