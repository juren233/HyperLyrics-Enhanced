/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.root.island.IslandDynamicLimitPolicy.Geometry

/** Content demand and native capacity must not be derived from the shrunken layout. */
internal object IslandFullIslandWidthPolicy {
    fun contentCapacity(areaLimit: Int, measuredArea: Int, measuredText: Int): Int =
        minOf(measuredText, areaLimit - overhead(measuredArea, measuredText)).coerceAtLeast(0)

    fun requiredArea(measuredArea: Int, measuredText: Int, content: Int): Int =
        overhead(measuredArea, measuredText) + content.coerceIn(0, measuredText.coerceAtLeast(0))

    fun overhead(area: Int, text: Int): Int = (area - text).coerceAtLeast(0)

    /**
     * 连续文本带（全岛歌词）所需的对称单侧区域宽。整句只算一次：左右两槽文字宽之和
     * `2 * side - leftOverhead - rightOverhead` 须容下 [content] 加接缝避让额外占用的宽度；
     * [clearance] 以左槽文字宽（接缝位置）求该额外宽度，接缝随单侧宽右移，迭代两次取最大。
     */
    fun continuousSide(content: Int, leftOverhead: Int, rightOverhead: Int, clearance: (seam: Int) -> Int): Int {
        fun side(extra: Int): Int = maxOf(
            leftOverhead,
            rightOverhead,
            (content.coerceAtLeast(0) + extra.coerceAtLeast(0) + leftOverhead + rightOverhead + 1) / 2,
        )
        var side = side(0)
        repeat(2) { side = maxOf(side, side(clearance(side - leftOverhead))) }
        return side
    }

    /**
     * Only shrink an already limited native result; preserve cutout, floor and centering.
     * [fixedOverride] replaces the native middle width (0 when the gapless seam removes the
     * cutout spacer); the freed width becomes side capacity, symmetric on a phone.
     */
    fun shrink(
        original: Geometry,
        leftDemand: Int,
        rightDemand: Int,
        screenWidth: Int,
        minWidth: Int,
        pad: Boolean,
        fixedOverride: Int? = null,
    ): Geometry {
        val nativeFixed = original.width - original.left - original.right
        if (nativeFixed < 0 || original.left < 0 || original.right < 0 || screenWidth <= 0) return original
        // A phone's normal big island is symmetric. Leave other native shapes untouched.
        if (!pad && original.left != original.right) return original
        val fixed = (fixedOverride ?: nativeFixed).coerceIn(0, nativeFixed)
        val freed = nativeFixed - fixed
        val capLeft = if (pad) original.left + freed / 2 else (original.width - fixed) / 2
        val capRight = if (pad) original.right + freed - freed / 2 else capLeft
        var left = leftDemand.coerceIn(0, capLeft)
        var right = rightDemand.coerceIn(0, capRight)
        if (!pad) {
            val floorSide = ((minWidth - fixed).coerceAtLeast(0) + 1) / 2
            val side = maxOf(left, right, floorSide).coerceAtMost(capLeft)
            left = side
            right = side
        } else {
            var missing = (minWidth - fixed - left - right).coerceAtLeast(0)
            val addLeft = minOf(missing, capLeft - left)
            left += addLeft
            missing -= addLeft
            right += minOf(missing, capRight - right)
        }
        val width = fixed + left + right
        if (width >= original.width) return original
        return Geometry(width, left, right, (screenWidth - width) / 2)
    }
}
