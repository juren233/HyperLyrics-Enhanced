/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

/**
 * 媒体岛主动重建的守卫判定。
 *
 * 双播冲突（两个媒体会话同时活跃）时原生岛数据层会停止供数：updateBigIslandView
 * 不再被调用，宿主 contentView 的媒体模块子树缺锚点，重挂扫描无目标，岛内容消失
 * 直到下一个原生媒体事件（通常为切歌）才恢复（2026-09-28 酷我+Apple 真机：
 * 23:51:36-23:55:36 无岛窗口）。守卫把"用最后一次媒体数据驱动原生重建"限定在
 * 用户可感知的损失窗口内，避免误伤原生的正常收岛（锁屏/息屏/暂停）。
 */
internal object IslandMediaReinstatePolicy {
    /** 重放尝试的最小间隔：给原生切换动画与暂态收岛留出窗口。 */
    const val MIN_INTERVAL_MS = 8_000L

    fun shouldReinstate(
        cachedDataAvailable: Boolean,
        cachedPkgMatchesLyric: Boolean,
        playbackActive: Boolean,
        screenInteractive: Boolean,
        msSinceLastAttempt: Long?,
        minIntervalMs: Long = MIN_INTERVAL_MS,
    ): Boolean {
        if (!cachedDataAvailable || !cachedPkgMatchesLyric) return false
        if (!playbackActive || !screenInteractive) return false
        // 首次尝试立即执行；此后按节流间隔重试。
        return msSinceLastAttempt == null || msSinceLastAttempt >= minIntervalMs
    }
}
