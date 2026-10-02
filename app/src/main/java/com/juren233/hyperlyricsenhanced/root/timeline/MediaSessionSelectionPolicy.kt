/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

/**
 * 锚点活动会话选择的纯逻辑（起播事件驱动）。
 *
 * 优先级：
 * 1. 其他会话刚刚转入播放，且起播时刻新于已持有会话 → 立即让位。
 *    该判定必须先于"持有者仍报 PLAYING"，否则 A 的暂停回调晚于 B 的起播回调时
 *    仍会人为拖慢 A→B；
 * 2. 无更新起播事件时，已持有且仍在播放的会话保持，避免双会话抖动；
 * 3. 持有会话非播放（暂停/缓冲）时，**只有其他会话"新近起播"（转入播放不超过
 *    [playStartWindowMs]）才让位**，且选择最新起播的一个。长期在放的会话
 *    （投屏/后台视频）不构成让位信号：持有
 *    会话很可能只是缓冲期上报暂停态（部分音乐 app 缓冲时上报 PAUSED 而非
 *    BUFFERING），此时必须保持持有、时间轴不动（2026-09-20 用户真机两轮反馈：
 *    计时宽限既拖慢了 A→B 切换，又没挡住长缓冲）；
 * 4. 持有会话仍在列表 → 保持（无任何让位信号时恢复旧有"持有不放手"语义）；
 * 5. 无持有会话：优先播放中的会话，再退回列表首会话（初始化/持有会话已消失）。
 */
internal object MediaSessionSelectionPolicy {

    data class Selection<T>(val selected: T?)

    fun <T, K> select(
        sessions: List<T>,
        keyOf: (T) -> K,
        heldKey: K?,
        isPlaying: (T) -> Boolean,
        playStartedAtMs: (T) -> Long?,
        nowMs: Long,
        playStartWindowMs: Long,
    ): Selection<T> {
        if (sessions.isEmpty()) return Selection(null)
        val heldAlive = heldKey?.let { key -> sessions.firstOrNull { keyOf(it) == key } }
        if (heldAlive != null) {
            val heldIdentity = keyOf(heldAlive)
            val heldStartedAt = playStartedAtMs(heldAlive)
            val freshOther = sessions
                .filter { keyOf(it) != heldIdentity && isPlaying(it) }
                .mapNotNull { session ->
                    playStartedAtMs(session)?.takeIf { startedAt ->
                        val ageMs = nowMs - startedAt
                        ageMs in 0..playStartWindowMs &&
                            (heldStartedAt == null || startedAt > heldStartedAt)
                    }?.let { startedAt -> startedAt to session }
                }
                .maxByOrNull { (startedAt, _) -> startedAt }
                ?.second
            if (freshOther != null) return Selection(freshOther)
            return Selection(heldAlive)
        }
        return Selection(sessions.firstOrNull(isPlaying) ?: sessions.first())
    }
}
