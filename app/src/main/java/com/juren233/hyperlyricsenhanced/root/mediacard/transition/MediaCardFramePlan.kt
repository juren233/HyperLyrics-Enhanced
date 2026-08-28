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
    val groupAlphas: List<Float>,
    val progressAlpha: Float,
    val elapsedAlpha: Float,
    val totalAlpha: Float,
    val actionsAlpha: Float,
    val targetCardHeight: Int?,
    /** Resolved same-frame size, not an unconsumed target placeholder. */
    val secondaryTextSizeSp: Float?,
    /** Target offset retained for diagnostics and transition contracts. */
    val secondaryTopOffsetPx: Int?,
    /** Resolved same-frame translation applied by UnifiedMediaLyricRoot. */
    val secondaryTranslationY: Float,
    /** Target alpha retained in source color units for compatibility. */
    val secondaryAlpha: Int?,
    /** Resolved same-frame alpha in [0, 1]. */
    val resolvedSecondaryAlpha: Float?,
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
                groupAlphas = listOf(keep, if (keepSecondLyric) keep else 0f, 0f),
                progressAlpha = 0f,
                elapsedAlpha = 0f,
                totalAlpha = 0f,
                actionsAlpha = if (mode == MediaCardFullAodTransitionMode.PAUSED_RESTORE_NATIVE) 1f else 0f,
                targetCardHeight = cardHeight,
                secondaryTextSizeSp = null,
                secondaryTopOffsetPx = null,
                secondaryTranslationY = 0f,
                secondaryAlpha = null,
                resolvedSecondaryAlpha = if (keepSecondLyric) keep else 0f,
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
                groupAlphas = listOf(1f, 1f, 1f),
                progressAlpha = 1f,
                elapsedAlpha = 1f,
                totalAlpha = 1f,
                actionsAlpha = 1f,
                targetCardHeight = cardHeight,
                secondaryTextSizeSp = null,
                secondaryTopOffsetPx = null,
                secondaryTranslationY = 0f,
                secondaryAlpha = null,
                resolvedSecondaryAlpha = 1f,
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
            startSecondaryTextSizeSp: Float? = null,
            startSecondaryAlpha: Float = 1f,
            startSecondaryTranslationY: Float = 0f,
        ): MediaCardFramePlan {
            val p = fraction.coerceIn(0f, 1f)
            val a = if (targetFullAod) 1f - p else p
            val rootAlpha = if (mode == MediaCardFullAodTransitionMode.PAUSED_RESTORE_NATIVE) a else 1f
            val second = if (keepSecondLyric) rootAlpha else if (targetFullAod) 1f - p else p
            val third = if (targetFullAod) 1f - p else p
            val actionAlpha = if (mode == MediaCardFullAodTransitionMode.PAUSED_RESTORE_NATIVE) 1f else a
            val targetAlpha = secondaryAlpha?.let { (it / 255f).coerceIn(0f, 1f) }
            val resolvedAlpha = if (targetFullAod) {
                lerp(startSecondaryAlpha.coerceIn(0f, 1f), targetAlpha ?: startSecondaryAlpha, p)
            } else {
                lerp(startSecondaryAlpha.coerceIn(0f, 1f), 1f, p)
            }
            val resolvedSize = when {
                secondaryTextSizeSp == null -> startSecondaryTextSizeSp
                startSecondaryTextSizeSp == null -> secondaryTextSizeSp
                else -> lerp(startSecondaryTextSizeSp, secondaryTextSizeSp, if (targetFullAod) p else 1f - p)
            }
            val targetTranslation = if (targetFullAod) {
                secondaryTopOffsetPx?.toFloat() ?: 0f
            } else {
                0f
            }
            return MediaCardFramePlan(
                fraction = p,
                targetFullAod = targetFullAod,
                mode = mode,
                rootAlpha = rootAlpha,
                rootTranslationY = 0f,
                rootScaleY = 1f,
                lyricVisible = rootAlpha > 0.001f || second > 0.001f || third > 0.001f,
                groupAlphas = listOf(rootAlpha, second, third),
                progressAlpha = a,
                elapsedAlpha = a,
                totalAlpha = a,
                actionsAlpha = actionAlpha,
                targetCardHeight = lerp(startCardHeight, targetCardHeight, p)?.coerceAtLeast(0),
                secondaryTextSizeSp = resolvedSize,
                secondaryTopOffsetPx = secondaryTopOffsetPx,
                secondaryTranslationY = lerp(startSecondaryTranslationY, targetTranslation, p),
                secondaryAlpha = secondaryAlpha,
                resolvedSecondaryAlpha = resolvedAlpha,
                secondaryVisible = secondaryVisible && second > 0.001f,
                stableAfterCommit = false,
            )
        }

        /** Interpolates from the actually rendered frame, used for fast reversal. */
        fun interpolateFrom(
            current: MediaCardFramePlan,
            target: MediaCardFramePlan,
            fraction: Float,
        ): MediaCardFramePlan {
            val p = fraction.coerceIn(0f, 1f)
            return MediaCardFramePlan(
                fraction = p,
                targetFullAod = target.targetFullAod,
                mode = target.mode,
                rootAlpha = lerp(current.rootAlpha, target.rootAlpha, p),
                rootTranslationY = lerp(current.rootTranslationY, target.rootTranslationY, p),
                rootScaleY = lerp(current.rootScaleY, target.rootScaleY, p),
                lyricVisible = if (p >= 1f && target.stableAfterCommit) target.lyricVisible else
                    current.lyricVisible || target.lyricVisible,
                groupAlphas = current.groupAlphas.zip(target.groupAlphas) { from, to -> lerp(from, to, p) },
                progressAlpha = lerp(current.progressAlpha, target.progressAlpha, p),
                elapsedAlpha = lerp(current.elapsedAlpha, target.elapsedAlpha, p),
                totalAlpha = lerp(current.totalAlpha, target.totalAlpha, p),
                actionsAlpha = lerp(current.actionsAlpha, target.actionsAlpha, p),
                targetCardHeight = lerp(current.targetCardHeight, target.targetCardHeight, p),
                secondaryTextSizeSp = lerpNullable(current.secondaryTextSizeSp, target.secondaryTextSizeSp, p),
                secondaryTopOffsetPx = target.secondaryTopOffsetPx,
                secondaryTranslationY = lerp(current.secondaryTranslationY, target.secondaryTranslationY, p),
                secondaryAlpha = target.secondaryAlpha,
                resolvedSecondaryAlpha = lerpNullable(
                    current.resolvedSecondaryAlpha,
                    target.resolvedSecondaryAlpha,
                    p,
                ),
                secondaryVisible = if (p >= 1f && target.stableAfterCommit) target.secondaryVisible else
                    current.secondaryVisible || target.secondaryVisible,
                stableAfterCommit = p >= 1f && target.stableAfterCommit,
            )
        }

        private fun lerp(start: Float, end: Float, p: Float): Float = start + (end - start) * p

        private fun lerp(start: Int?, end: Int?, p: Float): Int? {
            if (start == null && end == null) return null
            if (start == null) return end
            if (end == null) return start
            return (start + (end - start) * p).roundToInt().coerceAtLeast(0)
        }

        private fun lerpNullable(start: Float?, end: Float?, p: Float): Float? = when {
            start == null && end == null -> null
            start == null -> end
            end == null -> start
            else -> lerp(start, end, p)
        }
    }
}
