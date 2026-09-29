/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

/**
 * AOD 歌词位置轮询自适应节拍：AOD 是整行显示（无字级填充），行间轮询改为
 * 「到期唤醒」——睡到下一个显示行切换点之前，doze 内的 CPU 唤醒从 10 次/秒
 * 降到句间约 1 次/秒，切句瞬间精度不损失（唤醒点 = 边界 - 保护量）。
 * 无歌词时保 500ms，与无词预览刷新节拍对齐。
 */
internal object AodLyricPollCadencePolicy {

    const val MIN_INTERVAL_MS = 100L

    const val MAX_INTERVAL_MS = 1_000L

    const val NO_LYRIC_INTERVAL_MS = 500L

    /** 边界保护量：AOD 刷新含 draw wake lock，比岛上多留一点唤醒余量。 */
    const val BOUNDARY_GUARD_MS = 50L

    fun nextIntervalMs(
        positionMs: Long,
        nextBoundaryMs: Long?,
        hasLyrics: Boolean,
    ): Long {
        if (!hasLyrics) return NO_LYRIC_INTERVAL_MS
        val boundaryMs = nextBoundaryMs ?: return MAX_INTERVAL_MS
        return (boundaryMs - positionMs - BOUNDARY_GUARD_MS)
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }
}
