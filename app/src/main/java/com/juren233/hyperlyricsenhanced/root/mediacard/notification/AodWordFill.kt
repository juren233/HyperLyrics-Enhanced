/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord

/**
 * 息屏逐字的填充模型：唱的时候柔边固定宽，停下来以后柔边整体往右滑出、补满已唱部分。
 *
 * 旧画法是把一条宽 0.6 字号的渐变挂在进度点身后，进度点停下来（词间停顿、句尾、换行）时
 * 尾巴就半亮挂着，句尾/换行再一帧切成整行纯白，于是末尾会「跳」成白色；英文词唱完时进度点
 * 会直接跨过空格，尾巴也会突然亮一截。
 *
 * 这里每个点的亮度是两项之和（封顶 1）：
 * - 柔边：进度点身后固定 [edgePx] 宽的线性渐变（与旧画法同宽，长音、快词都一样）。横向距离只算字形，
 *   空白按零宽，进度点跨过空格时不会跳；
 * - 补满：进度停下来（词间空档不短于 [STOP_MIN_GAP_MS]，或整句唱完）之后，停之前唱到的点在
 *   [SETTLE_MS] 内线性加到 1。
 * 两项相加而不是取较大值：取较大值时柔边右段会被压成一整片同样亮度、从右侧往上补；相加则柔边保持
 * 原来的形状、越靠右越暗，整体往右滑出。两项都只随时间连续变大，所以任何一个点都不会一帧跳亮或变暗；
 * 整行补满之后与「整行纯白」一致。
 *
 * 这一行被换走的时刻（[build] 的 leaveMs，下一句紧接着开始时就在最后一个字唱完后几毫秒）早于
 * 「唱完 + [SETTLE_MS]」时，补满提前开始，正好在换走那一刻补完：柔边从最后一个字快唱完时就往右滑，
 * 换句时整行已全亮，带走的这一行不会是半亮的尾巴。开了提前换句、最后一个字还没唱完就换走时，
 * 补满项按剩下没唱的那一截放大（见 settleLift），柔边领先进度点多一些，换走时同样整行全亮。
 *
 * 坐标用「字符坐标」：第 i 个字符内的点记为 i + f（f ∈ [0,1]），与 [AodWordProgress.progressChars]
 * 的返回值同一口径；视图把它按字符边界线性映射到横坐标，所以每个像素的亮度就是这里的值。
 */
internal class AodWordFill private constructor(
    /** 第 i 个字符左边缘被唱到的时刻（毫秒）。 */
    private val startMs: DoubleArray,
    /** 第 i 个字符右边缘被唱到的时刻（毫秒）。 */
    private val endMs: DoubleArray,
    /** 第 i 个字符左边缘在「字形轴」上的位置（像素，空白零宽）。 */
    private val glyphLeft: DoubleArray,
    /** 第 i 个字符在字形轴上的宽度（空白为 0）。 */
    private val glyphWidth: DoubleArray,
    /** 每次停下来的开始时刻，升序；最后一项是整句唱完。 */
    private val stopStarts: DoubleArray,
    /** 每次停下来对应的补满开始时刻：通常就是停下来的时刻，赶在换句前补完时会提前。 */
    private val settleStarts: DoubleArray,
    /** 每次停下来时那一段末尾在字形轴上的位置。 */
    private val stopGlyphs: DoubleArray,
    private val edgePx: Double,
    leaveMs: Double?,
) {
    val length: Int get() = startMs.size

    /** 所有字符都补满的时刻；到这之后整行与纯白画法一致。 */
    val settledAtMs: Double = (settleStarts.lastOrNull() ?: 0.0) + SETTLE_MS

    /** 进度点在字形轴上的位置。 */
    private fun sungGlyph(positionMs: Double): Double {
        // 找最后一个已开始唱的字符；字符的起止时刻按顺序不减。
        var lo = 0
        var hi = length - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (startMs[mid] <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (found < 0) return glyphLeft.firstOrNull() ?: 0.0
        val span = endMs[found] - startMs[found]
        val fraction = if (span <= 0.0 || positionMs >= endMs[found]) 1.0 else (positionMs - startMs[found]) / span
        return glyphLeft[found] + glyphWidth[found] * fraction
    }

    /**
     * 每一段补满项的放大倍数，通常为 1。开了提前换句时，这一行可能在最后一个字唱完前就被换走：
     * 换走那一刻进度点离段尾还有一截，补满项要多抬这一截（按柔边宽度计），换走时段尾才能刚好全亮。
     */
    private val settleLift: DoubleArray = DoubleArray(stopStarts.size) { k ->
        if (leaveMs == null || edgePx <= 0.0) 1.0
        else maxOf(1.0, 1.0 + (stopGlyphs[k] - sungGlyph(leaveMs)) / edgePx)
    }

    /** 唱到时刻为 [sungAt] 的点（属于它之后第一次停下来的那一段），在 [positionMs] 时的「补满」项。 */
    private fun settle(sungAt: Double, positionMs: Double): Double {
        // 第一个不早于 sungAt 开始的停顿。
        var lo = 0
        var hi = stopStarts.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (stopStarts[mid] >= sungAt) { found = mid; hi = mid - 1 } else lo = mid + 1
        }
        if (found < 0) return 0.0
        return ((positionMs - settleStarts[found]) / SETTLE_MS).coerceIn(0.0, 1.0) * settleLift[found]
    }

    /** 柔边项（不截断）：进度点处为 0，往左每个柔边宽度加 1，进度点右侧为负。 */
    private fun edge(glyph: Double, sungGlyph: Double): Double =
        if (edgePx <= 0.0) (if (glyph <= sungGlyph) 1.0 else -1.0)
        else (sungGlyph - glyph) / edgePx

    /** 字符坐标 [coord]（index + fraction）处在 [positionMs] 时的亮度，0..1。 */
    fun brightnessAt(coord: Double, positionMs: Double): Double {
        if (length == 0) return 0.0
        val index = coord.toInt().coerceIn(0, length - 1)
        val fraction = (coord - index).coerceIn(0.0, 1.0)
        val sungAt = startMs[index] + (endMs[index] - startMs[index]) * fraction
        val glyph = glyphLeft[index] + glyphWidth[index] * fraction
        // 未唱到的点柔边项为负；只有提前补满的那一段会被补满项抬过 0（最多领先一个柔边宽度）。
        return (edge(glyph, sungGlyph(positionMs)) + settle(sungAt, positionMs)).coerceIn(0.0, 1.0)
    }

    /**
     * [from]..[to)（一行的字符范围）在 [positionMs] 时的亮度折线：按坐标升序把 (坐标, 亮度) 成对写进 [out]，
     * 返回点数。字符内「补满」项是常数、「柔边」项是线性的，两者之和截到 0..1 后只在两处拐弯：
     * 到 1 和到 0 的地方；这里给出每个字符的两端和这两个拐点，相邻字符在同一坐标上可以各有一个点。
     * 平坦段的中间点会被省掉。
     * [out] 至少要有 (to - from) * 12 个位置。
     */
    fun profile(from: Int, to: Int, positionMs: Double, out: DoubleArray): Int {
        var count = 0
        fun add(coord: Double, value: Double) {
            // 连续三个点亮度相同则中间点多余：把上一个点挪到当前位置即可。
            if (count >= 4 && out[count - 1] == value && out[count - 3] == value) {
                out[count - 2] = coord
                return
            }
            out[count++] = coord
            out[count++] = value
        }
        val sung = sungGlyph(positionMs)
        val cuts = DoubleArray(6)
        for (index in from.coerceAtLeast(0) until to.coerceAtMost(length)) {
            val start = startMs[index]
            val span = endMs[index] - start
            val left = glyphLeft[index]
            val width = glyphWidth[index]
            // 字符内：亮度 = 柔边 + 补满常数，截到 0..1。
            val settleValue = settle(start, positionMs)
            fun valueAt(f: Double): Double = (edge(left + width * f, sung) + settleValue).coerceIn(0.0, 1.0)
            var n = 0
            cuts[n++] = 0.0
            if (width > 0.0) {
                // 柔边 + 补满到 1、到 0 的位置。
                val fullAt = (sung - edgePx * (1.0 - settleValue) - left) / width
                val zeroAt = (sung + edgePx * settleValue - left) / width
                if (fullAt > 0.0 && fullAt < 1.0) cuts[n++] = fullAt
                if (zeroAt > 0.0 && zeroAt < 1.0) cuts[n++] = zeroAt
            }
            cuts[n++] = 1.0
            java.util.Arrays.sort(cuts, 0, n)
            for (k in 0 until n) add(index + cuts[k], valueAt(cuts[k]))
        }
        return count
    }

    companion object {
        /** 停下来之后，已唱部分补满所用的时间。 */
        const val SETTLE_MS = 300.0

        /** 词间空档不短于这个才算「停下来」，更短的空档照常由柔边推进。 */
        const val STOP_MIN_GAP_MS = 100.0

        /**
         * 用渐变画已唱部分时画笔本身的颜色。渐变上色时画笔的透明度还会再乘到渐变颜色上，
         * 所以画笔用不透明的同色，透明度只由 [stopColor] 带；否则字色半透明时，渐变里全亮的部分
         * 会比整行纯白暗一截（透明度相当于平方）。
         */
        fun gradientPaintColor(textColor: Int): Int = textColor or (0xFF shl 24)

        /** 亮度为 [brightness] 的渐变节点颜色：文字颜色的透明度乘上亮度。 */
        fun stopColor(textColor: Int, brightness: Double): Int {
            val alpha = ((textColor ushr 24) * brightness).toInt().coerceIn(0, 255)
            return (alpha shl 24) or (textColor and 0x00FFFFFF)
        }

        /**
         * 用与 [AodWordProgress.progressChars] 相同的对齐规则把 [words] 铺到 [text] 上：
         * 只比较非空白字符，对不上返回 null。[charWidth] 是第 i 个字符在屏幕上的宽度（像素），
         * [edgePx] 是柔边宽度（像素），[leaveMs] 是这一行预计被换走的时刻（未知为 null）。
         */
        fun build(
            text: String,
            words: List<LyricWord>,
            charWidth: (Int) -> Double,
            edgePx: Double,
            leaveMs: Double? = null,
        ): AodWordFill? {
            val expected = text.filterNot(Char::isWhitespace)
            val actual = words.joinToString("") { it.text.orEmpty() }.filterNot(Char::isWhitespace)
            if (expected != actual) return null

            // 每个非空白字符（按出现顺序）对应的词与词内序号。
            data class Slot(val begin: Double, val end: Double, val offset: Int, val count: Int)
            val slots = ArrayList<Slot>(expected.length)
            var previousEnd = Double.NEGATIVE_INFINITY
            words.forEach { word ->
                val count = word.text.orEmpty().count { !it.isWhitespace() }
                if (count == 0) return@forEach
                // progressChars 只有在前面的词都唱完后才推进当前词，所以开始时刻不早于前面词的结束。
                val begin = maxOf(word.begin.toDouble(), previousEnd)
                val end = maxOf(AodWordProgress.effectiveEnd(word).toDouble(), begin)
                for (offset in 0 until count) slots += Slot(begin, end, offset, count)
                previousEnd = maxOf(previousEnd, end)
            }

            val length = text.length
            val start = DoubleArray(length)
            val end = DoubleArray(length)
            val glyphLeft = DoubleArray(length)
            val glyphWidth = DoubleArray(length)
            val stops = ArrayList<Double>()
            val stopGlyphs = ArrayList<Double>()
            var seen = 0
            var glyph = 0.0
            var lastTime = slots.firstOrNull()?.begin ?: 0.0
            for (index in 0 until length) {
                glyphLeft[index] = glyph
                if (text[index].isWhitespace()) {
                    // 空白：进度点跨过它的那一刻（前一个字唱完）被唱到，字形轴上零宽。
                    start[index] = lastTime
                    end[index] = lastTime
                    continue
                }
                val slot = slots[seen++]
                val duration = slot.end - slot.begin
                start[index] = slot.begin + duration * slot.offset / slot.count
                end[index] = slot.begin + duration * (slot.offset + 1) / slot.count
                if (seen > 1 && start[index] - lastTime >= STOP_MIN_GAP_MS) {
                    stops += lastTime
                    stopGlyphs += glyph
                }
                glyphWidth[index] = charWidth(index).coerceAtLeast(0.0)
                glyph += glyphWidth[index]
                lastTime = end[index]
            }
            stops += lastTime
            stopGlyphs += glyph
            // 换句之前补不完的，提前开始补满，正好在换句时补完。
            val settleStarts = stops.map { stop -> if (leaveMs == null) stop else minOf(stop, leaveMs - SETTLE_MS) }
            return AodWordFill(
                start, end, glyphLeft, glyphWidth, stops.toDoubleArray(), settleStarts.toDoubleArray(),
                stopGlyphs.toDoubleArray(), edgePx, leaveMs,
            )
        }
    }
}
