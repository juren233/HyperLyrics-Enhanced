/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.root.island.IslandDynamicLimitPolicy.Geometry

/** Content demand and native capacity must not be derived from the shrunken layout. */
internal object IslandFullIslandWidthPolicy {
    fun contentCapacity(areaLimit: Int, measuredArea: Int, measuredText: Int): Int =
        minOf(measuredText, areaLimit - overhead(measuredArea, measuredText)).coerceAtLeast(0)

    fun requiredArea(measuredArea: Int, measuredText: Int, content: Int): Int =
        overhead(measuredArea, measuredText) + content.coerceIn(0, measuredText.coerceAtLeast(0))

    private fun overhead(area: Int, text: Int): Int = (area - text).coerceAtLeast(0)

    /** Only shrink an already limited native result; preserve cutout, floor and centering. */
    fun shrink(
        original: Geometry,
        leftDemand: Int,
        rightDemand: Int,
        screenWidth: Int,
        minWidth: Int,
        pad: Boolean,
    ): Geometry {
        val fixed = original.width - original.left - original.right
        if (fixed < 0 || original.left < 0 || original.right < 0 || screenWidth <= 0) return original
        // A phone's normal big island is symmetric. Leave other native shapes untouched.
        if (!pad && original.left != original.right) return original
        var left = leftDemand.coerceIn(0, original.left)
        var right = rightDemand.coerceIn(0, original.right)
        if (!pad) {
            val floorSide = ((minWidth - fixed).coerceAtLeast(0) + 1) / 2
            val side = maxOf(left, right, floorSide).coerceAtMost(original.left)
            left = side
            right = side
        } else {
            var missing = (minWidth - fixed - left - right).coerceAtLeast(0)
            val addLeft = minOf(missing, original.left - left)
            left += addLeft
            missing -= addLeft
            right += minOf(missing, original.right - right)
        }
        val width = fixed + left + right
        if (width >= original.width) return original
        return Geometry(width, left, right, (screenWidth - width) / 2)
    }
}
