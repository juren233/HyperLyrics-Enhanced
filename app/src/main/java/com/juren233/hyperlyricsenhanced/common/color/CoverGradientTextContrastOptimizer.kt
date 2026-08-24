/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.color

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Applies one small OKLCH lightness shift to cover-derived text only when it would otherwise
 * merge with the real right-edge artwork transition used by the gradient-cover style.
 */
internal object CoverGradientTextContrastOptimizer {
    private const val SIMILARITY_DISTANCE = 0.18
    private const val CONFLICT_CONTRAST = 1.75
    private const val TARGET_CONTRAST = 1.90
    private const val MAX_LIGHTNESS_DELTA = 0.10
    private const val LIGHTNESS_STEP = 0.005
    private const val MIN_MEANINGFUL_IMPROVEMENT = 0.05
    private const val MIDDLE_BLACK_MIX = 0.55
    private const val DARK_TAIL_LUMINANCE_MAX = 0.04
    private const val UNIFORMLY_LIGHT_BACKGROUND_MIN_LUMINANCE = 0.28

    fun shouldOptimize(
        useCustomColor: Boolean,
        useMonetColor: Boolean,
        useCoverColor: Boolean,
        gradientCoverBackgroundActive: Boolean,
        hasArtwork: Boolean,
    ): Boolean =
        !useCustomColor &&
            !useMonetColor &&
            useCoverColor &&
            gradientCoverBackgroundActive &&
            hasArtwork

    enum class DirectionPolicy {
        NONE,
        BRIGHTEN_FOR_DARK_TAIL,
        BRIGHTEN_FOR_MIXED_OR_DARK_BACKGROUND,
        DARKEN_FOR_UNIFORMLY_LIGHT_BACKGROUND,
    }

    data class Adjustment(
        val colors: IntArray,
        val backgroundAnchors: IntArray,
        val applied: Boolean,
        val lightnessDelta: Double,
        val minimumContrastBefore: Double,
        val minimumContrastAfter: Double,
        val minimumDistanceBefore: Double,
        val directionPolicy: DirectionPolicy,
        val minimumBackgroundLuminance: Double,
        val maximumBackgroundLuminance: Double,
    )

    fun optimize(textColors: IntArray, backgroundAnchors: IntArray): Adjustment {
        if (textColors.isEmpty() || backgroundAnchors.isEmpty()) {
            return Adjustment(
                colors = textColors,
                backgroundAnchors = backgroundAnchors,
                applied = false,
                lightnessDelta = 0.0,
                minimumContrastBefore = Double.POSITIVE_INFINITY,
                minimumContrastAfter = Double.POSITIVE_INFINITY,
                minimumDistanceBefore = Double.POSITIVE_INFINITY,
                directionPolicy = DirectionPolicy.NONE,
                minimumBackgroundLuminance = Double.NaN,
                maximumBackgroundLuminance = Double.NaN,
            )
        }

        val backgroundProfile = profileBackground(backgroundAnchors)
        val before = evaluate(textColors, backgroundAnchors)
        val conflict = before.minimumContrast < CONFLICT_CONTRAST &&
            before.minimumDistance < SIMILARITY_DISTANCE
        if (!conflict) {
            return Adjustment(
                colors = textColors,
                backgroundAnchors = backgroundAnchors,
                applied = false,
                lightnessDelta = 0.0,
                minimumContrastBefore = before.minimumContrast,
                minimumContrastAfter = before.minimumContrast,
                minimumDistanceBefore = before.minimumDistance,
                directionPolicy = backgroundProfile.directionPolicy,
                minimumBackgroundLuminance = backgroundProfile.minimumLuminance,
                maximumBackgroundLuminance = backgroundProfile.maximumLuminance,
            )
        }

        val candidates = ArrayList<Candidate>()
        val steps = (MAX_LIGHTNESS_DELTA / LIGHTNESS_STEP).roundToInt()
        for (step in 1..steps) {
            val amount = step * LIGHTNESS_STEP
            val delta = when (backgroundProfile.directionPolicy) {
                DirectionPolicy.DARKEN_FOR_UNIFORMLY_LIGHT_BACKGROUND -> -amount
                DirectionPolicy.BRIGHTEN_FOR_DARK_TAIL,
                DirectionPolicy.BRIGHTEN_FOR_MIXED_OR_DARK_BACKGROUND,
                -> amount
                DirectionPolicy.NONE -> continue
            }
            candidates += candidate(textColors, backgroundAnchors, delta)
        }
        val reachingTarget = candidates
            .filter { it.evaluation.minimumContrast >= TARGET_CONTRAST }
            .minWithOrNull(
                compareBy<Candidate> { abs(it.delta) }
                    .thenByDescending { it.evaluation.minimumContrast },
            )
        val selected = reachingTarget ?: candidates.maxByOrNull { it.evaluation.minimumContrast }
        if (selected == null ||
            selected.evaluation.minimumContrast <
            before.minimumContrast + MIN_MEANINGFUL_IMPROVEMENT
        ) {
            return Adjustment(
                colors = textColors,
                backgroundAnchors = backgroundAnchors,
                applied = false,
                lightnessDelta = 0.0,
                minimumContrastBefore = before.minimumContrast,
                minimumContrastAfter = before.minimumContrast,
                minimumDistanceBefore = before.minimumDistance,
                directionPolicy = backgroundProfile.directionPolicy,
                minimumBackgroundLuminance = backgroundProfile.minimumLuminance,
                maximumBackgroundLuminance = backgroundProfile.maximumLuminance,
            )
        }
        return Adjustment(
            colors = selected.colors,
            backgroundAnchors = backgroundAnchors,
            applied = true,
            lightnessDelta = selected.delta,
            minimumContrastBefore = before.minimumContrast,
            minimumContrastAfter = selected.evaluation.minimumContrast,
            minimumDistanceBefore = before.minimumDistance,
            directionPolicy = backgroundProfile.directionPolicy,
            minimumBackgroundLuminance = backgroundProfile.minimumLuminance,
            maximumBackgroundLuminance = backgroundProfile.maximumLuminance,
        )
    }

    fun sampleBackgroundAnchors(bitmap: Bitmap): IntArray = runCatching {
        sampleBackgroundAnchors(bitmap.width, bitmap.height, bitmap::getPixel)
    }.getOrElse {
        val readable = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return intArrayOf()
        try {
            sampleBackgroundAnchors(readable.width, readable.height, readable::getPixel)
        } finally {
            readable.recycle()
        }
    }

    internal fun sampleBackgroundAnchors(
        width: Int,
        height: Int,
        pixelAt: (x: Int, y: Int) -> Int,
    ): IntArray {
        if (width <= 0 || height <= 0) return intArrayOf()
        val cropSize = minOf(width, height)
        val cropLeft = (width - cropSize) / 2
        val cropTop = (height - cropSize) / 2
        val cropRight = cropLeft + cropSize
        val sampleTop = cropTop + cropSize / 6
        val sampleBottom = cropTop + cropSize - cropSize / 6
        val sampleRows = 7
        val rowColors = IntArray(sampleRows) { row ->
            val y = if (sampleRows == 1) {
                (sampleTop + sampleBottom) / 2
            } else {
                sampleTop + row * (sampleBottom - sampleTop - 1).coerceAtLeast(0) /
                    (sampleRows - 1)
            }.coerceIn(cropTop, cropTop + cropSize - 1)
            weightedRightEdgeColor(
                cropLeft = cropLeft,
                cropRight = cropRight,
                y = y,
                pixelAt = pixelAt,
            )
        }
        val edge = averageArgb(rowColors)
        return intArrayOf(
            edge,
            blendTowardBlack(edge, MIDDLE_BLACK_MIX),
            0xFF000000.toInt(),
        )
    }

    internal fun contrastRatio(first: Int, second: Int): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        val lighter = maxOf(firstLuminance, secondLuminance)
        val darker = minOf(firstLuminance, secondLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }


    private fun profileBackground(backgroundAnchors: IntArray): BackgroundProfile {
        val luminances = backgroundAnchors.map(::relativeLuminance)
        val minimum = luminances.minOrNull() ?: Double.NaN
        val maximum = luminances.maxOrNull() ?: Double.NaN
        val darkTail = luminances.lastOrNull()?.let { it <= DARK_TAIL_LUMINANCE_MAX } == true
        val uniformlyLight = !darkTail &&
            luminances.isNotEmpty() &&
            luminances.all { it >= UNIFORMLY_LIGHT_BACKGROUND_MIN_LUMINANCE }
        val directionPolicy = when {
            darkTail -> DirectionPolicy.BRIGHTEN_FOR_DARK_TAIL
            uniformlyLight -> DirectionPolicy.DARKEN_FOR_UNIFORMLY_LIGHT_BACKGROUND
            else -> DirectionPolicy.BRIGHTEN_FOR_MIXED_OR_DARK_BACKGROUND
        }
        return BackgroundProfile(
            directionPolicy = directionPolicy,
            minimumLuminance = minimum,
            maximumLuminance = maximum,
        )
    }

    private fun candidate(
        textColors: IntArray,
        backgroundAnchors: IntArray,
        delta: Double,
    ): Candidate {
        val adjusted = textColors.map { color ->
            PerceptualGradient.shiftLightness(color, delta)
        }.toIntArray()
        return Candidate(
            delta = delta,
            colors = adjusted,
            evaluation = evaluate(adjusted, backgroundAnchors),
        )
    }

    private fun evaluate(textColors: IntArray, backgroundAnchors: IntArray): Evaluation {
        var minimumContrast = Double.POSITIVE_INFINITY
        var minimumDistance = Double.POSITIVE_INFINITY
        val pairCount = maxOf(textColors.size, backgroundAnchors.size)
        for (index in 0 until pairCount) {
            val textIndex = scaledIndex(index, pairCount, textColors.size)
            val backgroundIndex = scaledIndex(index, pairCount, backgroundAnchors.size)
            val text = textColors[textIndex]
            val background = backgroundAnchors[backgroundIndex]
            minimumContrast = minOf(minimumContrast, contrastRatio(text, background))
            minimumDistance = minOf(
                minimumDistance,
                PerceptualGradient.oklabDistance(text, background),
            )
        }
        return Evaluation(minimumContrast, minimumDistance)
    }

    private fun scaledIndex(index: Int, pairCount: Int, itemCount: Int): Int {
        if (itemCount <= 1 || pairCount <= 1) return 0
        return (index.toDouble() * (itemCount - 1) / (pairCount - 1))
            .roundToInt()
            .coerceIn(0, itemCount - 1)
    }

    private fun weightedRightEdgeColor(
        cropLeft: Int,
        cropRight: Int,
        y: Int,
        pixelAt: (x: Int, y: Int) -> Int,
    ): Int {
        val sampleCount = minOf(3, cropRight - cropLeft).coerceAtLeast(1)
        var totalWeight = 0
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        for (index in 0 until sampleCount) {
            val x = cropRight - sampleCount + index
            val weight = index + 1
            val color = pixelAt(x, y)
            totalWeight += weight
            alpha += ((color ushr 24) and 0xFF) * weight
            red += ((color ushr 16) and 0xFF) * weight
            green += ((color ushr 8) and 0xFF) * weight
            blue += (color and 0xFF) * weight
        }
        return argb(
            alpha / totalWeight,
            red / totalWeight,
            green / totalWeight,
            blue / totalWeight,
        )
    }

    private fun averageArgb(colors: IntArray): Int {
        if (colors.isEmpty()) return 0
        var alpha = 0L
        var red = 0L
        var green = 0L
        var blue = 0L
        colors.forEach { color ->
            alpha += (color ushr 24) and 0xFF
            red += (color ushr 16) and 0xFF
            green += (color ushr 8) and 0xFF
            blue += color and 0xFF
        }
        return argb(
            (alpha / colors.size).toInt(),
            (red / colors.size).toInt(),
            (green / colors.size).toInt(),
            (blue / colors.size).toInt(),
        )
    }

    private fun blendTowardBlack(color: Int, fraction: Double): Int {
        val retained = 1.0 - fraction.coerceIn(0.0, 1.0)
        return argb(
            (color ushr 24) and 0xFF,
            (((color ushr 16) and 0xFF) * retained).roundToInt(),
            (((color ushr 8) and 0xFF) * retained).roundToInt(),
            ((color and 0xFF) * retained).roundToInt(),
        )
    }

    private fun relativeLuminance(color: Int): Double {
        val alpha = ((color ushr 24) and 0xFF) / 255.0
        val red = linearChannel((color ushr 16) and 0xFF)
        val green = linearChannel((color ushr 8) and 0xFF)
        val blue = linearChannel(color and 0xFF)
        return alpha * (0.2126 * red + 0.7152 * green + 0.0722 * blue)
    }

    private fun linearChannel(channel: Int): Double {
        val value = channel.coerceIn(0, 255) / 255.0
        return if (value <= 0.04045) {
            value / 12.92
        } else {
            ((value + 0.055) / 1.055).pow(2.4)
        }
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or
            (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or
            blue.coerceIn(0, 255)

    private data class BackgroundProfile(
        val directionPolicy: DirectionPolicy,
        val minimumLuminance: Double,
        val maximumLuminance: Double,
    )

    private data class Evaluation(
        val minimumContrast: Double,
        val minimumDistance: Double,
    )

    private data class Candidate(
        val delta: Double,
        val colors: IntArray,
        val evaluation: Evaluation,
    )
}
