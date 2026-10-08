/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

import com.juren233.hyperlyricsenhanced.lyric.view.line.model.LyricModel

/**
 * 换句落地追赶：换句过渡在淡出结束后才落地新句，快歌此时已唱过开头几个词。
 * 新句首个进度若直接同步到当前位置，高亮会从 0 一下跳到中段。这里改为从 0 起步，
 * 在有限窗口内补到窗口末端时刻的同步宽度；窗口结束后回到原有逐词动画。
 */
internal object SwitchProgressCatchUp {
    /**
     * 落后超过该值视为中途进入（如歌曲信息切回歌词），保留原硬同步。
     * 220070／220071 真机换句（含形变延后进度）落后最多约 406ms；中途进入实测 1005ms。
     */
    const val MAX_LAG_MS = 800L
    const val MIN_WINDOW_MS = 160L
    const val MAX_WINDOW_MS = 320L

    /** 同一句歌词的重新绑定（设置刷新、元数据重绑），不是换句。 */
    fun isSameLine(previous: LyricModel, next: LyricModel): Boolean =
        previous.text.isNotEmpty() && previous.begin == next.begin &&
            previous.end == next.end && previous.text == next.text

    /** 追赶窗口时长；null 表示不追赶。[lagMs] 为当前位置相对首词开始的落后量。 */
    fun windowMs(lagMs: Long): Long? =
        if (lagMs <= 0L || lagMs > MAX_LAG_MS) null else lagMs.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)

    /** 词内按时间插值；与渲染器既有的逐词插值同一公式。 */
    fun interpolate(posMs: Long, begin: Long, end: Long, duration: Long, start: Float, endPosition: Float): Float {
        val span = (end - begin).takeIf { it > 0 } ?: duration
        if (span <= 0L) return endPosition
        val progress = ((posMs - begin).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        return start + (endPosition - start) * progress
    }

    /**
     * [posMs] 时刻应有的高亮宽度：所在词内插值，词间空隙或末词之后取前一词终点，首词前为 0。
     * 只用不改导航缓存的 [findPreviousEntry][com.juren233.hyperlyricsenhanced.lyric.model.extensions.TimingNavigator.findPreviousEntry]。
     */
    fun syncWidthAt(posMs: Long, model: LyricModel): Float {
        val word = model.wordTimingNavigator.findPreviousEntry(posMs) ?: return 0f
        if (posMs > word.end) return word.endPosition
        return interpolate(posMs, word.begin, word.end, word.duration, word.startPosition, word.endPosition)
    }
}
