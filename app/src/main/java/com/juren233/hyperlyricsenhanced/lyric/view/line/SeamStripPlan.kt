/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import kotlin.math.abs

/**
 * 全岛歌词一帧的「接缝绘制方案」（ISLAND-GATE-OCCLUSION-001 第 6 轮拍板）。
 *
 * 三条语义（用户 2026-10-01 拍板，取代整行微移）：
 * 1. 滚动过缝＝边缘滑过＋渐隐：条带连续滚动，跨缝单元被摄像头边缘渐进
 *    遮盖——只绘制多数侧残部、按遮盖深度渐隐（alpha=2|f−0.5|，f＝缝左
 *    占比），切点恒在接缝＝摄像头边缘；无整字消失瞬间、无同字双像。
 * 2. 静止对齐＝绕孔分段：行头（或行尾）保持贴缘不动，跨缝单元与其后
 *    （前）内容作为一段整体平移过缝完整显示，拉开的一处字距被摄像头
 *    黑区吸收；任何整行位移形态都已被真机否决，不再使用。
 * 3. 静止↔滚动过渡＝间隙随滚动连续合拢/张开：分段平移量是条带偏移的
 *    纯线性函数（无时钟、无动画状态），起滚后右移量渐减至 0、停驻前
 *    左移量渐增至满，全程零跳变，seek/暂停天然可逆。
 *
 * 段带按单元边界把文本切成至多三段：驻留端跨缝单元 X 之后的段携带右移
 * extraR≥0（HEAD 避让），停驻端跨缝单元 Y 之前的段携带左移 shiftL≤0
 * （TAIL 避让）；X 不晚于 Y（可能是同一词）。过渡窗口随总滚动距离
 * 收缩以免互相侵入端点。纯函数：主从两槽与阴影同输入必然同输出。
 */
internal data class SeamStripPlan private constructor(
    /** 接缝（视口虚拟坐标）＝左槽宽；渐隐裁剪用。 */
    val seamX: Float,
    /** 段带：按单元边界连续覆盖全文本，delta=0 的段在自然位置。 */
    val bands: List<Band>,
    /** 当前帧跨缝单元的渐隐参数；null＝无跨缝（避让落定或缝在边界/空白上）。 */
    val straddler: Straddle?,
) {

    internal data class Band(
        /** 段首字符索引（含）。 */
        val charStart: Int,
        /** 段末字符索引（不含）。 */
        val charEnd: Int,
        /** 叠加到段内自然 advance 上的平移：右移 ≥0（HEAD 避让）/ 左移 ≤0（TAIL 避让）。 */
        val delta: Float,
        /** 段首字符的自然条带 advance。 */
        val startAdvance: Float,
    )

    internal data class Straddle(
        val unit: SeamOcclusionLayout.Unit,
        /** 相对条带原点的绘制 x（含段带平移）。 */
        val stripDrawX: Float,
        /** 渐隐 alpha＝2|f−0.5|：缝在单元边界处 1、正中 0，连续无跳变。 */
        val alpha: Float,
        /** f>0.5＝只绘制缝左多数侧。 */
        val majorityLeft: Boolean,
    )

    /** 跨缝绘制的裁剪/渐隐参数，换算进已平移 xShift 的段内坐标系。 */
    internal data class SeamFade internal constructor(
        val alpha: Float,
        val seamLocalX: Float,
        val majorityLeft: Boolean,
    )

    /** 条带自然 advance 所在段带的平移量（段带单调连续覆盖全文本）。 */
    fun shiftAt(naturalX: Float): Float {
        var lo = 0
        var hi = bands.size - 1
        var result = 0f
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (bands[mid].startAdvance <= naturalX) {
                result = bands[mid].delta
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    /** timing 组可含多个词；段带位移和渐隐边界均须拆笔，不能只取组首位移。 */
    inline fun forEachWordRun(
        charStarts: FloatArray,
        charEnds: FloatArray,
        draw: (start: Int, end: Int, shift: Float, fading: Boolean) -> Unit,
    ) {
        if (charStarts.isEmpty()) return
        val unit = straddler?.unit
        var start = 0
        var shift = shiftAt(charStarts[0])
        var fading = unit != null && charEnds[0] > unit.start && charStarts[0] < unit.end
        for (i in 1 until charStarts.size) {
            val nextShift = shiftAt(charStarts[i])
            val nextFading = unit != null && charEnds[i] > unit.start && charStarts[i] < unit.end
            if (nextShift != shift || nextFading != fading) {
                draw(start, i, shift, fading)
                start = i
                shift = nextShift
                fading = nextFading
            }
        }
        draw(start, charStarts.size, shift, fading)
    }

    /** 把 [straddler] 换算进条带原点 stripOrigin、平移 xShift 的当前坐标系。 */
    fun fadeAt(stripOrigin: Float, xShift: Float): SeamFade? =
        straddler?.let { SeamFade(it.alpha, seamX - stripOrigin - xShift, it.majorityLeft) }

    /** 若自然 advance 区间 [unitStart, unitEnd) 与跨缝单元相交，返回其渐隐参数（逐字路径用）。 */
    fun straddleFadeFor(
        unitStart: Float,
        unitEnd: Float,
        stripOrigin: Float,
        xShift: Float,
    ): SeamFade? {
        val st = straddler ?: return null
        if (unitEnd <= st.unit.start || unitStart >= st.unit.end) return null
        return fadeAt(stripOrigin, xShift)
    }

    /** 静止锚定方向：HEAD＝行首贴缘（跨缝段右移过缝）；TAIL＝行尾贴缘（左移过缝）；FREE＝取位移较小侧。 */
    internal enum class Anchor { HEAD, TAIL, FREE }

    internal data class ProgressFollow(val offset: Float, val anchor: Float)

    /** 绘制与进度跟随共用端点参数，避免只校正自然坐标而遗漏段带位移。 */
    private class ScrollEndpoints(layout: SeamOcclusionLayout, seam: Float, val s0: Float, val sEnd: Float) {
        val head = layout.straddlingStaticUnit(s0, seam)
        val tail = layout.straddlingStaticUnit(sEnd, seam)
        val rightDelta = head?.let { (seam - (s0 + it.start)).coerceAtLeast(0f) } ?: 0f
        val leftDistance = tail?.let { (sEnd + it.end - seam).coerceAtLeast(0f) } ?: 0f
        private val travel = (s0 - sEnd).coerceAtLeast(0f)
        private val totalAvoidance = rightDelta + leftDistance
        private val windowScale = if (totalAvoidance > travel && totalAvoidance > 0f) {
            travel / totalAvoidance
        } else 1f
        val rightWindow = rightDelta * windowScale
        val leftWindow = leftDistance * windowScale
        // Spread the final displacement without borrowing the head transition's
        // window. Overlapping them would add their velocities on the middle band.
        private val easedLeftWindow = minOf(leftDistance * 3f, (travel - rightWindow).coerceAtLeast(0f))
        // FastOutSlowIn's slope is below 3. Limit its contribution when there is
        // less than 3x room, so easing cannot exceed the old linear peak velocity.
        private val tailEaseMix = if (leftWindow > 0f) {
            ((easedLeftWindow / leftWindow - 1f) / 2f).coerceIn(0f, 1f)
        } else 0f

        fun rightShift(origin: Float): Float = if (rightWindow > 0f) {
            rightDelta * (1f - (s0 - origin) / rightWindow).coerceIn(0f, 1f)
        } else if (travel == 0f) rightDelta else 0f

        fun leftShift(origin: Float): Float = if (easedLeftWindow > 0f) {
            val progress = (1f - (origin - sEnd) / easedLeftWindow).coerceIn(0f, 1f)
            val eased = tailInterpolator.getInterpolation(progress)
            -leftDistance * (progress + (eased - progress) * tailEaseMix)
        } else 0f
    }

    companion object {

        // Same gentle entrance/exit curve as the lyric view's LayoutTransitionX.
        private val tailInterpolator = FastOutSlowInInterpolator()

        /**
         * 全岛逐字进度跟随右槽，速度由每帧真实进度决定。首端分段间隙尚未
         * 合拢时，按绘制位置反解偏移（斜率 1 + delta/window），不能直接
         * 用 anchor-progress，否则额外右移会把高亮推到屏外。
         * 末端尽量留出 leftDistance+leftWindow，使进度先通过该词再进入
         * 停驻避让窗；空间不足时保留右缘余量及连续滚动，不强追导致回跳。
         */
        fun followProgress(
            layout: SeamOcclusionLayout,
            progress: Float,
            lineWidth: Float,
            viewWidth: Float,
            seam: Float,
            preferredAnchor: Float,
        ): ProgressFollow {
            if (lineWidth <= viewWidth) return ProgressFollow(0f, preferredAnchor)
            val end = -(lineWidth - viewWidth)
            val endpoints = ScrollEndpoints(layout, seam, 0f, end)
            val rightRoom = (viewWidth - seam).coerceAtLeast(0f)
            val tailSafeAnchor = seam + endpoints.leftDistance + endpoints.leftWindow
            val anchor = maxOf(preferredAnchor, minOf(tailSafeAnchor, viewWidth - rightRoom * 0.1f))
            var offset = (anchor - progress).coerceIn(end, 0f)
            val head = endpoints.head
            if (head != null && progress >= head.start && endpoints.rightWindow > 0f &&
                offset > -endpoints.rightWindow
            ) {
                offset = ((anchor - progress - endpoints.rightDelta) /
                    (1f + endpoints.rightDelta / endpoints.rightWindow))
                    .coerceIn(-endpoints.rightWindow, 0f)
            }
            return ProgressFollow(offset, anchor)
        }

        /** Right preview continues past the old tail stop, retaining only head avoidance. */
        fun scrollFromHead(
            layout: SeamOcclusionLayout, origin: Float, seam: Float, travel: Float,
        ): SeamStripPlan {
            val head = layout.straddlingStaticUnit(0f, seam)
            val delta = head?.let { seam - it.start } ?: 0f
            val window = minOf(delta, travel)
            val shift = if (window > 0f) delta * (1f + origin / window).coerceIn(0f, 1f) else 0f
            val bands = if (head != null && shift > 0f) buildList {
                if (head.charStart > 0) add(Band(0, head.charStart, 0f, 0f))
                add(Band(head.charStart, layout.textLength, shift, head.start))
            } else listOf(Band(0, layout.textLength, 0f, 0f))
            return SeamStripPlan(seam, bands, findStraddler(layout, origin, seam, bands))
        }

        /** Follow the requested focus using the same head avoidance as the drawn glyphs. */
        fun followFromHead(
            layout: SeamOcclusionLayout, progress: Float, seam: Float, travel: Float, anchor: Float,
        ): ProgressFollow {
            val head = layout.straddlingStaticUnit(0f, seam)
            val delta = head?.let { seam - it.start } ?: 0f
            val window = minOf(delta, travel)
            if (head != null && window > 0f && anchor < seam) {
                // A left-side focus can start scrolling before the avoided word. Switching
                // equations at that word's start would jump by its remaining head gap.
                // Collapse it over one continuous progress interval, then follow normally.
                val start = minOf(anchor, head.start)
                val end = anchor + window
                val offset = if (progress < end) {
                    -window * ((progress - start) / (end - start)).coerceIn(0f, 1f)
                } else anchor - progress
                return ProgressFollow(offset.coerceIn(-travel, 0f), anchor)
            }
            var offset = (anchor - progress).coerceIn(-travel, 0f)
            if (head != null && progress >= head.start && window > 0f && offset > -window) {
                offset = ((anchor - progress - delta) / (1f + delta / window)).coerceIn(-window, 0f)
            }
            return ProgressFollow(offset, anchor)
        }

        /** 纯过缝（滚动中/ghost）：单带 δ=0，仅带跨缝渐隐。 */
        fun transit(layout: SeamOcclusionLayout, origin: Float, seam: Float): SeamStripPlan {
            val bands = listOf(Band(0, layout.textLength, 0f, 0f))
            return SeamStripPlan(seam, bands, findStraddler(layout, origin, seam, bands))
        }

        /**
         * 静止绕孔分段：锚定边一段保持自然位置贴缘，跨缝单元及其后（前）
         * 整段平移过缝。静止时始终按完整词簇；容量不足由 SpaceGateLineLayout
         * 统一交给滚动，不能用字符级分割或行尾裁字冒充静止完整显示。
         */
        fun staticClear(
            layout: SeamOcclusionLayout,
            origin: Float,
            seam: Float,
            anchor: Anchor,
            viewWidth: Float,
        ): SeamStripPlan {
            val x = layout.straddlingStaticUnit(origin, seam) ?: return identity(layout, seam)
            val rightDelta = (seam - (origin + x.start)).coerceAtLeast(0f)
            val leftDelta = (seam - (origin + x.end)).coerceAtMost(0f)
            val rightFits = origin + layout.totalAdvance + rightDelta <= viewWidth + SeamOcclusionLayout.FUZZ
            val leftFits = origin + leftDelta >= -SeamOcclusionLayout.FUZZ
            val useRight = when (anchor) {
                Anchor.HEAD -> true
                Anchor.TAIL -> false
                Anchor.FREE -> rightFits && (!leftFits || abs(rightDelta) <= abs(leftDelta))
            }
            val len = layout.textLength
            val bands = ArrayList<Band>(2)
            if (useRight) {
                if (x.charStart > 0) bands.add(Band(0, x.charStart, 0f, 0f))
                if (x.charStart < len) bands.add(Band(x.charStart, len, rightDelta, x.start))
            } else {
                bands.add(Band(0, x.charEnd, leftDelta, 0f))
                if (x.charEnd < len) bands.add(Band(x.charEnd, len, 0f, x.end))
            }
            // 避让落定后必无跨缝；findStraddler 仅作浮点噪声兜底（此时 alpha≈1）。
            return SeamStripPlan(seam, bands, findStraddler(layout, origin, seam, bands))
        }

        /**
         * 原始短行绕词后超宽：该词从缝右完整进入、到缝左完整退出。
         * 滚动行程为词宽；前段只向左移动 end-seam，后段只向左移动
         * seam-start-spare，分别保留起点左锚和终点右锚。间隙连续变化，
         * 所有字符单调左移、词内字距恒定；不增加真实歌词 advance。
         */
        fun capacityScroll(
            layout: SeamOcclusionLayout,
            origin: Float,
            seam: Float,
            word: SeamOcclusionLayout.Unit,
            spare: Float,
        ): SeamStripPlan {
            val fraction = (-origin / word.width).coerceIn(0f, 1f)
            val rightDelta = seam - word.start
            val bands = ArrayList<Band>(3)
            if (word.charStart > 0) {
                bands.add(Band(0, word.charStart, rightDelta * fraction, 0f))
            }
            bands.add(Band(word.charStart, word.charEnd, rightDelta, word.start))
            if (word.charEnd < layout.textLength) {
                bands.add(Band(
                    word.charEnd, layout.textLength,
                    rightDelta + (word.end - seam + spare) * fraction, word.end,
                ))
            }
            return SeamStripPlan(seam, bands, findStraddler(layout, origin, seam, bands))
        }

        /**
         * 溢出行：首端间隙随滚动合拢，末端避让提前缓入缓出。s0/sEnd＝驻留/停驻
         * 时的条带原点（左锚定溢出行均为 scrollOffset=0 / −(lineW−vw)）。
         * X/Y 取静止词簇（英文整词，CJK 按字），保证主/次行起滚前与停驻后
         * 均不拆词。origin 从 s0 滚向 sEnd 时 extraR 线性减到 0、shiftL
         * 平滑增到满，中间仍为字符级过缝渐隐。短行程限制缓动强度与窗口，
         * 防止另一端的位移侵入当前端点，尤其 X/Y 是同一单词的情况。
         */
        fun scrollWithEndpoints(
            layout: SeamOcclusionLayout,
            origin: Float,
            seam: Float,
            s0: Float,
            sEnd: Float,
        ): SeamStripPlan {
            val endpoints = ScrollEndpoints(layout, seam, s0, sEnd)
            val x0 = endpoints.head
            val y = endpoints.tail
            val extraR = endpoints.rightShift(origin)
            val shiftL = endpoints.leftShift(origin)

            val len = layout.textLength
            val cuts = sortedSetOf(0, len, x0?.charStart ?: 0, y?.charEnd ?: len).toList()
            val bands = ArrayList<Band>(cuts.size)
            for (i in 0 until cuts.size - 1) {
                val a = cuts[i]
                val b = cuts[i + 1]
                if (b <= a) continue
                var delta = 0f
                if (x0 != null && a >= x0.charStart) delta += extraR
                if (y != null && b <= y.charEnd) delta += shiftL
                val last = bands.lastOrNull()
                if (last != null && last.delta == delta) {
                    bands[bands.size - 1] = last.copy(charEnd = b)
                } else {
                    bands.add(Band(a, b, delta, layout.charAdvanceAt(a)))
                }
            }
            if (bands.isEmpty()) bands.add(Band(0, len, 0f, 0f))
            return SeamStripPlan(seam, bands, findStraddler(layout, origin, seam, bands))
        }

        private fun identity(layout: SeamOcclusionLayout, seam: Float): SeamStripPlan =
            SeamStripPlan(seam, listOf(Band(0, layout.textLength, 0f, 0f)), null)

        /** 在段带平移后的绘制位置上找跨缝单元并给出渐隐参数；空白/边界容差内返回 null。 */
        private fun findStraddler(
            layout: SeamOcclusionLayout,
            origin: Float,
            seam: Float,
            bands: List<Band>,
        ): Straddle? {
            for (band in bands) {
                if (band.charEnd <= band.charStart) continue
                val u = layout.unitAt(seam - origin - band.delta) ?: continue
                if (u.charStart < band.charStart || u.charEnd > band.charEnd) continue
                if (u.blank) return null
                val drawnStart = origin + band.delta + u.start
                if (seam <= drawnStart + SeamOcclusionLayout.FUZZ ||
                    seam >= drawnStart + u.width - SeamOcclusionLayout.FUZZ
                ) {
                    continue
                }
                val f = (seam - drawnStart) / u.width
                return Straddle(u, band.delta + u.start, 2f * abs(f - 0.5f), f > 0.5f)
            }
            return null
        }
    }
}
