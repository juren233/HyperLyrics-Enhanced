/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

/**
 * 全岛歌词（SpaceGate）的接缝单元表。
 *
 * 条带保持未挖孔布局连续：所有单元在自然 advance 位置。滚动态（第 6 轮
 * 拍板）＝边缘滑过＋渐隐：advance 区间横跨接缝的那一个单元由摄像头边缘
 * 渐进遮盖——只绘制多数侧残部、按遮盖深度渐隐，切点恒在接缝＝摄像头边
 * 缘（见 [SeamStripPlan]）。静止态＝绕孔分段：跨缝单元及其后（前）整段
 * 平移过缝，整行位移形态已全部真机否决。
 *
 * 滚动单元：中文等非拉丁文本按字；拉丁字符逐字成单元。另存静止词簇表，
 * 有足够空间时按完整英文词绕孔分段，不改变滚动渐隐粒度。标点按禁则成组——尾随标
 * 点并入前一单元、行首/开引号类并入后一单元，保证接缝右侧开头永远不会
 * 是孤立标点。空白自成分段，无可见墨迹，不参与渐隐与避让。
 *
 * 纯 Kotlin、无 Android 依赖：字符宽度由调用方按绘制同管线度量后传入，
 * 本类只做几何——主从两槽同输入必然同输出。
 */
internal class SeamOcclusionLayout private constructor(
    private val units: List<Unit>,
    private val staticUnits: List<Unit>,
) {

    internal class Unit internal constructor(
        /** 单元首字符索引（含并入的标点）。 */
        val charStart: Int,
        /** 单元末字符索引（不含）。 */
        val charEnd: Int,
        /** 条带 advance 起点。 */
        val start: Float,
        /** 条带 advance 终点。 */
        val end: Float,
        /** 纯空白分段：无可见墨迹，永不渐隐。 */
        val blank: Boolean,
    ) {
        val width: Float get() = end - start
    }

    /** 文本总字符数＝末单元 charEnd。 */
    val textLength: Int get() = units.last().charEnd

    /** 条带总 advance＝末单元 end。 */
    val totalAdvance: Float get() = units.last().end

    /** Immutable character/punctuation groups shared by the promotion drawing pass. */
    internal val drawingUnits: List<Unit> get() = units

    /**
     * 绘制偏移 [stripOffset]（条带原点在视口中的 x）下，advance 区间横跨
     * 接缝 [seam] 的可见单元；无跨缝可见单元返回 null。单元按序互不重叠，
     * 任何时刻至多一个单元跨缝。接缝落在单元 advance 边界附近（[FUZZ]
     * 容差内）时该单元视为完整位于缝一侧，不参与渐隐——字形墨迹恒在
     * advance 框内且距边界有侧轴承，边界即安全。
     */
    fun straddlingUnit(stripOffset: Float, seam: Float): Unit? {
        return straddlingUnit(stripOffset, seam, units)
    }

    /** 静止词界独立于 timing；即使缝恰在词内两个字母之间，也返回完整词簇。 */
    fun straddlingStaticUnit(stripOffset: Float, seam: Float): Unit? =
        straddlingUnit(stripOffset, seam, staticUnits)

    private fun straddlingUnit(stripOffset: Float, seam: Float, table: List<Unit>): Unit? {
        val u = unitAt(seam - stripOffset, table) ?: return null
        if (u.blank) return null
        val drawnStart = stripOffset + u.start
        return if (seam > drawnStart + FUZZ && seam < drawnStart + u.width - FUZZ) {
            u
        } else {
            null
        }
    }

    /**
     * 静止避让（绕孔分段，第 6 轮拍板）所需的右段平移量 ≥0：把跨缝单元
     * 整段推到缝右（HEAD 侧完整显示）。无跨缝单元返回 0。
     */
    fun rightShiftToClear(stripOffset: Float, seam: Float): Float {
        val u = straddlingUnit(stripOffset, seam) ?: return 0f
        return (seam - (stripOffset + u.start)).coerceAtLeast(0f)
    }

    /** 镜像：左段平移量 ≤0，把跨缝单元拉到缝左（TAIL 侧完整显示）。 */
    fun leftShiftToClear(stripOffset: Float, seam: Float): Float {
        val u = straddlingUnit(stripOffset, seam) ?: return 0f
        return (seam - (stripOffset + u.end)).coerceAtMost(0f)
    }

    /** advance 落在单元 [start, end)（边界归右）内的单元；越界返回 null。 */
    internal fun unitAt(advance: Float): Unit? = unitAt(advance, units)

    private fun unitAt(advance: Float, table: List<Unit>): Unit? {
        var lo = 0
        var hi = table.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val unit = table[mid]
            if (advance < unit.start) {
                hi = mid - 1
            } else if (advance >= unit.end) {
                lo = mid + 1
            } else {
                return unit
            }
        }
        return null
    }

    /** 字符索引所在单元首的自然 advance（段带起点换算用）；0 或越界回 0。 */
    internal fun charAdvanceAt(charIndex: Int): Float {
        if (charIndex <= 0) return 0f
        var lo = 0
        var hi = units.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val unit = units[mid]
            if (charIndex < unit.charStart) {
                hi = mid - 1
            } else if (charIndex >= unit.charEnd) {
                lo = mid + 1
            } else {
                return unit.start
            }
        }
        return 0f
    }

    companion object {

        /** 边界容差（px）：远小于任何字形侧轴承，仅吸收几何判定的浮点噪声。 */
        internal const val FUZZ = 0.05f

        /**
         * 由 [text] 与绘制同管线的逐字符 advance [charWidths] 构建单元表。
         * 返回 null 表示输入退化（空文本/宽度不足），调用方回退连续绘制。
         */
        fun build(text: String, charWidths: FloatArray): SeamOcclusionLayout? {
            if (text.isEmpty() || charWidths.size < text.length) return null
            val prefix = FloatArray(text.length + 1)
            for (i in text.indices) {
                prefix[i + 1] = prefix[i] + charWidths[i]
            }

            val units = ArrayList<Unit>(text.length)
            // 待定单元：由开引号/开括号或行首闭标点起头，等待第一个基础字符并入。
            var pendingStart = -1
            var pendingEnd = -1
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c.isWhitespace() -> {
                        flushPending(units, text, prefix, pendingStart, pendingEnd)
                        pendingStart = -1
                        pendingEnd = -1
                        var j = i
                        while (j < text.length && text[j].isWhitespace()) j++
                        units.add(Unit(i, j, prefix[i], prefix[j], blank = true))
                        i = j
                    }
                    isClosePunct(c) -> {
                        val last = units.lastOrNull()
                        if (pendingStart < 0 && last != null && !last.blank && last.charEnd == i) {
                            // 尾随标点回并前一单元（相邻无空白才成组）。
                            units.removeAt(units.size - 1)
                            units.add(
                                Unit(last.charStart, i + 1, last.start, prefix[i + 1], blank = false)
                            )
                        } else {
                            // 行首/空白后/开引号串中的闭标点：前向并入待定单元。
                            if (pendingStart < 0) pendingStart = i
                            pendingEnd = i + 1
                        }
                        i++
                    }
                    isOpenPunct(c) -> {
                        if (pendingStart < 0) pendingStart = i
                        pendingEnd = i + 1
                        i++
                    }
                    else -> {
                        // 基础字符：并入待定单元（开引号成组）或独立成单元。
                        if (pendingStart >= 0) {
                            units.add(Unit(pendingStart, i + 1, prefix[pendingStart], prefix[i + 1], blank = false))
                            pendingStart = -1
                            pendingEnd = -1
                        } else {
                            units.add(Unit(i, i + 1, prefix[i], prefix[i + 1], blank = false))
                        }
                        i++
                    }
                }
            }
            flushPending(units, text, prefix, pendingStart, pendingEnd)
            if (units.isEmpty()) return null
            // 仅构建时合并静止词簇，沿用字符表的 advance 和标点禁则。
            // 不读取 LyricWord：上游 timing 可能把整句或多个中文字符打成一组。
            val staticUnits = ArrayList<Unit>(units.size)
            for (unit in units) {
                val previous = staticUnits.lastOrNull()
                if (previous != null && !previous.blank && !unit.blank &&
                    isLatinWordChar(text[previous.charEnd - 1]) &&
                    isLatinWordChar(text[unit.charStart])
                ) {
                    staticUnits[staticUnits.lastIndex] = Unit(
                        previous.charStart, unit.charEnd, previous.start, unit.end, blank = false,
                    )
                } else {
                    staticUnits.add(unit)
                }
            }
            return SeamOcclusionLayout(units, staticUnits)
        }

        private fun isLatinWordChar(c: Char): Boolean =
            (c.isLetter() && Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN) ||
                c in '0'..'9' || c == '\'' || c == '’' || c == '-'

        private fun flushPending(
            units: ArrayList<Unit>,
            text: String,
            prefix: FloatArray,
            pendingStart: Int,
            pendingEnd: Int,
        ) {
            if (pendingStart < 0 || pendingEnd <= pendingStart) return
            // 行尾只剩标点（如句末省略号后跟开引号无下文）：自成一个单元。
            units.add(Unit(pendingStart, pendingEnd, prefix[pendingStart], prefix[pendingEnd], blank = false))
        }

        /** 尾随/闭合类标点：随前一单元走，不能单独出现在接缝右侧开头。 */
        private fun isClosePunct(c: Char): Boolean =
            c in ",.!?;:" || c == '…' || c == '‥' ||
                c in "，。！？；：、·．" ||
                c in "”』」）〉》〕〗〙〛＞»"

        /** 开引号/开括号类：并入后一单元，不能单独留在接缝左侧末尾。 */
        private fun isOpenPunct(c: Char): Boolean =
            c in "「『“（《〈〔〖〘〚(" || c == '<' || c == '«'
    }
}
