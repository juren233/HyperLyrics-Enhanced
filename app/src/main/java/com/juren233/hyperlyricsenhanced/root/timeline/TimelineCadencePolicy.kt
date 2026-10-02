/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

/**
 * 时间轴位置环自适应节拍：逐字行与「换句后稳定窗」内维持 33ms——换句动画、
 * 动态宽度重测后的跑马灯闩锁重试（LyricLineView.startScrolling 依赖下一个位置
 * tick）、间奏指示、进度光晕都是位置流的隐式消费者；稳定窗外普通行放宽到
 * 250ms 中距档，并睡到下一个显示行切换点之前（边界到期唤醒，切句精度不损失）。
 * 句间主线程唤醒从 30 次/秒降到约 4-5 次/秒。
 * （210158 教训：普通行放宽到 1s 会让跑马灯启动重试迟至 1s 生效，
 * 逐行歌词每句开头顿一下——见 DEBUGGING_MISTAKES TIMELINE-CADENCE-001。）
 */
internal object TimelineCadencePolicy {

    /** 逐字行固定节拍，保持字级填充平滑度。 */
    const val WORD_SYNC_INTERVAL_MS = 33L

    const val MIN_INTERVAL_MS = 33L

    /** 稳定窗外普通行的句中节拍上限（~4Hz），足够喂光晕/间奏指示等轻消费者。 */
    const val MID_LINE_INTERVAL_MS = 250L

    /** 边界保护量：提前于切换点醒来，吸收位置外推与时钟漂移。 */
    const val BOUNDARY_GUARD_MS = 30L

    /** 下一句是逐字行时的提前升频窗口：进入窗口即切回快档，避免字级填充起步迟滞。 */
    const val WORD_SYNC_PREARM_MS = 500L

    /** 换句后稳定窗：覆盖换句动画（~600ms）与动态宽度重测后的跑马灯闩锁重试。 */
    const val SETTLE_WINDOW_MS = 1_200L

    fun nextIntervalMs(
        positionMs: Long,
        currentLineWordSync: Boolean,
        nextBoundaryMs: Long?,
        nextLineWordSync: Boolean,
        msSinceLineChange: Long?,
        activeConsumerWordSync: Boolean = false,
    ): Long {
        if (currentLineWordSync || activeConsumerWordSync) return WORD_SYNC_INTERVAL_MS
        if (msSinceLineChange != null && msSinceLineChange < SETTLE_WINDOW_MS) {
            return WORD_SYNC_INTERVAL_MS
        }
        val boundaryMs = nextBoundaryMs ?: return MID_LINE_INTERVAL_MS
        val distanceMs = boundaryMs - positionMs
        if (nextLineWordSync && distanceMs <= WORD_SYNC_PREARM_MS) {
            return WORD_SYNC_INTERVAL_MS
        }
        return (distanceMs - BOUNDARY_GUARD_MS).coerceIn(MIN_INTERVAL_MS, MID_LINE_INTERVAL_MS)
    }
}
