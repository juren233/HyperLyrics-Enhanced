/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import com.juren233.hyperlyricsenhanced.lyric.view.NextLinePromotionMotion
import kotlin.math.PI
import kotlin.math.cos

/** 锁屏 AOD 一次内容变化用哪种转场。 */
internal enum class AodTransitionKind {
    /** 直接替换，不做动画。 */
    NONE,

    /** 「歌词滚动」：展示中的下一句飞到主行位置放大，旧句整块上浮淡出，新句其余行与下下句同时上浮进入。 */
    PROMOTE,

    /** 「歌词滚动」但没有可用的下一句预览（关了下一句、切歌、拖进度等）：旧块整体上浮淡出，新块整体上浮淡入。 */
    BLOCK,

    /** 「灵动圆点」：旧句整块上浮淡出，圆点同时原地渐显。 */
    INTERLUDE_ENTER,

    /** 没换句，只是某几行内容变了（翻译晚到、同曲替换、下一首预告）：变化的行原地交叉淡变，其余行平移到新位置。 */
    ROW_FADE,
}

/** 新内容里某一行怎么出现。 */
internal enum class AodRowEnter {
    /** 新内容里不显示，或位置内容都不变。 */
    NONE,

    /** 内容不变，从旧位置平移到新位置。 */
    MOVE,

    /** 从下方上浮淡入。 */
    ENTER,

    /** 跟着被提升的主行一起从下一句的位置升上来，同时淡入（新句的翻译、发音、伴唱等）。 */
    RIDE,

    /** 原地渐显。 */
    FADE_IN,

    /** 新主行：从下一句的位置和字号放大飞到主行。 */
    PROMOTED,
}

/** 旧内容里某一行怎么离开（由副本完成，真实控件已换成新内容）。 */
internal enum class AodRowExit {
    NONE,

    /** 随旧句整块上浮淡出。 */
    EXIT_UP,

    /** 原地淡出。 */
    FADE_OUT,

    /** 旧的下一句：与新主行同轨迹放大飞到主行位置，同时淡出（与新主行交叉淡变）。 */
    PROMOTE_SOURCE,
}

internal data class AodRowPlan(val enter: AodRowEnter, val exit: AodRowExit)

/**
 * 锁屏 AOD 转场的判定与数值，全部是纯函数。动作时长与位移系数与超级岛的「下一句上浮」同源
 * （[NextLinePromotionMotion]），所有行同时开始、同时结束。
 */
internal object AodLyricTransitionPolicy {

    /** 移动、上浮进入/退出的时长。 */
    const val MOVE_DURATION_MS = NextLinePromotionMotion.DURATION_MS

    /** 原地渐显/淡出的时长（与上浮落地后的预览淡入同源）。 */
    const val FADE_DURATION_MS = NextLinePromotionMotion.PREVIEW_FADE_DURATION_MS

    /** 旧块上浮退出的距离，相对旧主行高度。 */
    const val EXIT_TRAVEL_FACTOR = NextLinePromotionMotion.MAIN_EXIT_TRAVEL_FACTOR

    /** 新行从下方上浮进入的起始距离，相对参照行高度。 */
    const val ENTER_TRAVEL_FACTOR = 0.5f

    /** 新主行与旧下一句交叉淡变时，各自用一半时长完成淡入/淡出，避免中途整体变暗。 */
    private const val CROSSFADE_SPAN = 0.5f

    fun decide(
        canAnimate: Boolean,
        lyricScroll: Boolean,
        interludeDots: Boolean,
        wasInterlude: Boolean,
        nowInterlude: Boolean,
        lineAdvanced: Boolean,
        oldRows: Map<AodLyricRow, String>,
        newRows: Map<AodLyricRow, String>,
    ): AodTransitionKind {
        if (!canAnimate) return AodTransitionKind.NONE
        if (oldRows.values.all { it.isBlank() }) return AodTransitionKind.NONE
        val oldMain = oldRows[AodLyricRow.MAIN].orEmpty()
        val newMain = newRows[AodLyricRow.MAIN].orEmpty()
        val lineChanged = lineAdvanced || oldMain != newMain
        if (nowInterlude && !wasInterlude && lineChanged && oldMain.isNotBlank()) {
            return when {
                interludeDots -> AodTransitionKind.INTERLUDE_ENTER
                lyricScroll -> AodTransitionKind.BLOCK
                else -> AodTransitionKind.NONE
            }
        }
        if (!lyricScroll) return AodTransitionKind.NONE
        if (!lineChanged) {
            return if (rowsDiffer(oldRows, newRows)) AodTransitionKind.ROW_FADE else AodTransitionKind.NONE
        }
        val oldNext = oldRows[AodLyricRow.NEXT].orEmpty()
        return if (oldNext.isNotBlank() && oldNext == newMain) {
            AodTransitionKind.PROMOTE
        } else {
            AodTransitionKind.BLOCK
        }
    }

    fun rowsDiffer(oldRows: Map<AodLyricRow, String>, newRows: Map<AodLyricRow, String>): Boolean =
        AodLyricRow.entries.any { oldRows[it].orEmpty() != newRows[it].orEmpty() }

    fun plan(kind: AodTransitionKind, row: AodLyricRow, oldText: String, newText: String): AodRowPlan {
        val oldShown = oldText.isNotBlank()
        val newShown = newText.isNotBlank()
        fun enterIf(role: AodRowEnter) = if (newShown) role else AodRowEnter.NONE
        fun exitIf(role: AodRowExit) = if (oldShown) role else AodRowExit.NONE
        fun inPlace(): AodRowPlan =
            if (oldShown && newShown && oldText == newText) {
                AodRowPlan(AodRowEnter.MOVE, AodRowExit.NONE)
            } else {
                AodRowPlan(enterIf(AodRowEnter.FADE_IN), exitIf(AodRowExit.FADE_OUT))
            }
        return when (kind) {
            AodTransitionKind.NONE -> AodRowPlan(AodRowEnter.NONE, AodRowExit.NONE)
            AodTransitionKind.PROMOTE -> when (row) {
                AodLyricRow.MAIN -> AodRowPlan(enterIf(AodRowEnter.PROMOTED), exitIf(AodRowExit.EXIT_UP))
                AodLyricRow.NEXT -> AodRowPlan(enterIf(AodRowEnter.ENTER), exitIf(AodRowExit.PROMOTE_SOURCE))
                else -> AodRowPlan(enterIf(AodRowEnter.RIDE), exitIf(AodRowExit.EXIT_UP))
            }
            AodTransitionKind.BLOCK -> AodRowPlan(enterIf(AodRowEnter.ENTER), exitIf(AodRowExit.EXIT_UP))
            AodTransitionKind.INTERLUDE_ENTER -> when (row) {
                AodLyricRow.NEXT -> inPlace()
                AodLyricRow.MAIN -> AodRowPlan(enterIf(AodRowEnter.FADE_IN), exitIf(AodRowExit.EXIT_UP))
                else -> AodRowPlan(enterIf(AodRowEnter.FADE_IN), exitIf(AodRowExit.EXIT_UP))
            }
            AodTransitionKind.ROW_FADE -> inPlace()
        }
    }

    /** 新主行起始缩放：旧下一句字号相对主行字号，与超级岛的放大比同一夹取范围。 */
    fun promotedStartScale(mainTextSize: Float, nextTextSize: Float): Float =
        1f / NextLinePromotionMotion.targetScale(mainTextSize, nextTextSize)

    fun anchorFactor(alignment: AodLyricAlignment): Float = when (alignment) {
        AodLyricAlignment.LEFT -> 0f
        AodLyricAlignment.CENTER -> 0.5f
        AodLyricAlignment.RIGHT -> 1f
    }

    /** 与 AccelerateDecelerateInterpolator 相同的曲线（超级岛上浮用的平台默认插值器）。 */
    fun ease(fraction: Float): Float {
        val x = fraction.coerceIn(0f, 1f)
        return (cos((x + 1f) * PI) / 2.0 + 0.5).toFloat()
    }

    /** 整个转场的线性进度（0..1，按 [MOVE_DURATION_MS]）换算成移动进度。 */
    fun moveProgress(linear: Float): Float = ease(linear)

    /** 整个转场的线性进度换算成原地渐显/淡出进度：前 [FADE_DURATION_MS] 内完成。 */
    fun fadeProgress(linear: Float): Float =
        ease(linear * MOVE_DURATION_MS.toFloat() / FADE_DURATION_MS.toFloat())

    /** 新主行交叉淡入的不透明度（移动进度 [move]）。 */
    fun crossfadeIn(move: Float): Float = (move / CROSSFADE_SPAN).coerceIn(0f, 1f)

    /** 旧下一句副本交叉淡出的不透明度。 */
    fun crossfadeOut(move: Float): Float = ((1f - move) / CROSSFADE_SPAN).coerceIn(0f, 1f)

    fun lerp(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction
}
