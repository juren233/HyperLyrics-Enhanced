/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

/**
 * Apple 在线兜底歌词期间的中心进度：只转发「新读到」的原始进度。
 *
 * 兜底期间直连进度回调被丢弃，直连参考停在兜底前的旧值；MediaSession 外推在播放卡顿后
 * 会恒定超前（真机日志约 1.1 秒）。息屏时中心 ticker 停止，订阅端约每 16ms 重复上一次
 * 的原始值，只在约 5 秒一次的 playback_state 发布时才变成新读数。
 *
 * 如果这些样本照常走 [AppleCentralPositionPolicy.resolve]：旧直连值（DIRECT_PREFERRED）、
 * 超前的 MediaSession 外推（冻结值与外推相差超过 5 秒时 MEDIA_REPLACED_DIVERGENT）和真实
 * 发布值会轮流成为输出，时间轴每隔几秒被来回拉扯（息屏兜底时进度来回跳）。重复的冻结值
 * 也会在偏后连续段满 1 秒后被当成新锚点，把锚点钉在已经过去的位置上。
 *
 * 所以兜底期间绕开 resolve：原始值与上一次相同就不转发，变化了才扣掉提供器延迟后转发，
 * 每个读数只在它新鲜的那一刻进入时间轴，外推和纠偏交给时间轴自己。
 */
internal class AppleFallbackCentralClock {
    private var lastRawPosition: Long? = null

    fun reset() {
        lastRawPosition = null
    }

    /** 返回要转发给时间轴的位置；与上一次原始值相同（冻结重复）时返回 null。 */
    fun accept(rawPosition: Long, providerDelayMs: Int): Long? {
        val previous = lastRawPosition
        lastRawPosition = rawPosition
        if (previous == rawPosition) return null
        return (rawPosition - providerDelayMs).coerceAtLeast(0L)
    }
}
