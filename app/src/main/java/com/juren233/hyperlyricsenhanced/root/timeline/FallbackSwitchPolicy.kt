/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.root.source.OnlineTranslationBoundaryPolicy

/**
 * 竞速里更高优先级的补充歌词晚到时，何时切换：当前这一句继续用已显示的来源，
 * 到下一句歌词的起点再切过去，避免当前句割裂跳变。只做判定，不碰时间轴状态，便于单测。
 */
internal object FallbackSwitchPolicy {
    sealed interface Plan {
        /** 没有可对齐的下一句（位置未知、已是最后一句等），直接切换。 */
        data object ApplyNow : Plan

        /** 挂起，位置走到 [targetPosition] 再切换。 */
        data class Defer(val targetPosition: Long) : Plan
    }

    enum class Due { WAIT, APPLY, DROP }

    fun plan(shownLines: List<RichLyricLine>, position: Long?): Plan {
        position ?: return Plan.ApplyNow
        val target = OnlineTranslationBoundaryPolicy.nextCommitPosition(shownLines, position)
        return if (target != null && target > position) Plan.Defer(target) else Plan.ApplyNow
    }

    /**
     * @param displayedIsFallback 此刻显示的仍是补充内容；换曲、清空或主源接管后为 false，挂起的切换作废。
     */
    fun due(position: Long?, targetPosition: Long, displayedIsFallback: Boolean): Due = when {
        !displayedIsFallback -> Due.DROP
        position == null || position < targetPosition -> Due.WAIT
        else -> Due.APPLY
    }
}
