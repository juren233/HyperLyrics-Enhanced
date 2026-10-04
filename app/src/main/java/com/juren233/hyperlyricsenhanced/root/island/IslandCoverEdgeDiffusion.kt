/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt

/** Reuses a column's Gaussian coefficients across its rows without changing pixel arithmetic. */
internal class IslandCoverEdgeDiffusion(private val edgeColumn: IntArray) {
    private var radius = 0f
    private var extent = 0
    private var weights = FloatArray(0)
    private var weightSum = 1f

    fun prepareRadius(value: Float) {
        if (radius == value) return
        radius = value
        if (value < 0.5f) return
        val sigma = (value * 0.5f).coerceAtLeast(0.75f)
        extent = ceil(value).toInt().coerceAtLeast(1)
        val count = extent * 2 + 1
        if (weights.size < count) weights = FloatArray(count)
        val denominator = 2f * sigma * sigma
        weightSum = 0f
        for (offset in -extent..extent) {
            val distance = offset.toFloat()
            val weight = exp(-(distance * distance) / denominator)
            weights[offset + extent] = weight
            weightSum += weight
        }
    }

    fun colorAt(centerY: Int): Int {
        val last = edgeColumn.lastIndex
        if (last < 0) return 0
        val center = centerY.coerceIn(0, last)
        if (radius < 0.5f) return edgeColumn[center]
        var alpha = 0f
        var red = 0f
        var green = 0f
        var blue = 0f
        // Keep the old offset order and Float accumulators, including clamped edge samples.
        for (offset in -extent..extent) {
            val color = edgeColumn[(center + offset).coerceIn(0, last)]
            val weight = weights[offset + extent]
            alpha += (color ushr 24) * weight
            red += (color ushr 16 and 0xff) * weight
            green += (color ushr 8 and 0xff) * weight
            blue += (color and 0xff) * weight
        }
        return ((alpha / weightSum).roundToInt().coerceIn(0, 255) shl 24) or
            ((red / weightSum).roundToInt().coerceIn(0, 255) shl 16) or
            ((green / weightSum).roundToInt().coerceIn(0, 255) shl 8) or
            (blue / weightSum).roundToInt().coerceIn(0, 255)
    }
}
