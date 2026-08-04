package com.juren233.hyperlyricsenhanced.common.lyric

import kotlin.math.roundToInt

/** Shared policy for the Apple Music lyrics-page focus blur. */
object AppleLyricsBlurPolicy {
    const val OFF = 0
    const val NATIVE = 1
    const val ADVANCED_MATERIAL = 2

    fun normalizeMode(value: Int): Int = value.coerceIn(OFF, ADVANCED_MATERIAL)

    private fun interpolatedRadius(
        rowDistance: Int,
        minRadius: Float,
        maxRadius: Float,
    ): Float {
        if (rowDistance <= 0) return 0f
        val lowerRadius = minOf(minRadius, maxRadius)
        val upperRadius = maxOf(minRadius, maxRadius)
        val step = rowDistance.coerceAtMost(5) - 1
        return lowerRadius + (upperRadius - lowerRadius) * step / 4f
    }

    fun nativeBlurRadiusPx(
        rowDistance: Int,
        minRadiusDp: Float,
        maxRadiusDp: Float,
        density: Float,
    ): Int {
        if (rowDistance <= 0) return 0
        val lowerRadiusDp = minOf(minRadiusDp, maxRadiusDp)
        val upperRadiusDp = maxOf(minRadiusDp, maxRadiusDp)
        val radiusDp = if (rowDistance >= 5) {
            upperRadiusDp
        } else {
            lowerRadiusDp +
                (upperRadiusDp - lowerRadiusDp) * (rowDistance - 1) / 3.5f
        }.coerceAtMost(upperRadiusDp)
        return (radiusDp * density).roundToInt()
    }

    fun advancedMaterialBlurRadiusPx(
        rowDistance: Int,
        minRadiusPx: Int,
        maxRadiusPx: Int,
    ): Int = interpolatedRadius(
        rowDistance = rowDistance,
        minRadius = minRadiusPx.toFloat(),
        maxRadius = maxRadiusPx.toFloat(),
    ).roundToInt()

    fun blurRadiusPx(
        mode: Int,
        rowDistance: Int,
        minRadius: Float,
        maxRadius: Float,
        density: Float,
    ): Int = when (mode) {
        NATIVE -> nativeBlurRadiusPx(
            rowDistance = rowDistance,
            minRadiusDp = minRadius,
            maxRadiusDp = maxRadius,
            density = density,
        )
        ADVANCED_MATERIAL -> advancedMaterialBlurRadiusPx(
            rowDistance = rowDistance,
            minRadiusPx = minRadius.roundToInt(),
            maxRadiusPx = maxRadius.roundToInt(),
        )
        else -> 0
    }

    fun shouldBlurBeforeFirstLine(
        currentPositionMs: Long?,
        firstLineBeginMs: Long?,
    ): Boolean =
        currentPositionMs != null &&
            currentPositionMs >= 0L &&
            firstLineBeginMs != null &&
            firstLineBeginMs > currentPositionMs

    fun beforeFirstLineNativeBlurRadiusPx(
        visibleRowIndex: Int,
        minRadiusDp: Float,
        maxRadiusDp: Float,
        density: Float,
    ): Int = nativeBlurRadiusPx(
        rowDistance = visibleRowIndex.coerceAtLeast(0) + 1,
        minRadiusDp = minRadiusDp,
        maxRadiusDp = maxRadiusDp,
        density = density,
    )

    fun beforeFirstLineAdvancedMaterialBlurRadiusPx(
        visibleRowIndex: Int,
        minRadiusPx: Int,
        maxRadiusPx: Int,
    ): Int = advancedMaterialBlurRadiusPx(
        rowDistance = visibleRowIndex.coerceAtLeast(0) + 1,
        minRadiusPx = minRadiusPx,
        maxRadiusPx = maxRadiusPx,
    )

    fun beforeFirstLineBlurRadiusPx(
        mode: Int,
        visibleRowIndex: Int,
        minRadius: Float,
        maxRadius: Float,
        density: Float,
    ): Int = blurRadiusPx(
        mode = mode,
        rowDistance = visibleRowIndex.coerceAtLeast(0) + 1,
        minRadius = minRadius,
        maxRadius = maxRadius,
        density = density,
    )
}
