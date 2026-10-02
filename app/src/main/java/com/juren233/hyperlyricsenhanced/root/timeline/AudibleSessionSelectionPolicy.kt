/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.timeline

/**
 * 音频真值驱动的会话资格判定（发声排序 × 音乐目录 × 持有不发声超时）。
 *
 * 背景（2026-09-29 真机定稿）：纯会话规则的让位依赖「观测到对方暂停→播放翻转」；
 * 当持有者的暂停落在会话摘除/冻结的观测盲区时，起播时刻永不刷新，已暂停的持有者
 * 「持有不放手」，超级岛歌词归属与岛显示分叉且无自愈路径。本策略改用播放器事件流
 * （与 AOSP 媒体按键仲裁器同源）判定「谁在真实发声」，并做两处域修正：
 * - 只认音乐目录内会话——官方「最近发声」会把抖音/系统提示音也排进来（真机实测），
 *   非目录持有者同样不享有保持权；
 * - 持有者不发声短于 [silentHoldYieldMs] 时保持（覆盖换曲间隙与缓冲误报暂停，
 *   2026-09-20 计时宽限被真机否决的两条教训），超过即让位给最新发声的目录会话。
 *
 * 返回 null 表示音频层不足以裁决（跟踪不可用，或有持有但目录内无人发声），调用方回退
 * 既有 [MediaSessionSelectionPolicy] 纯会话规则，行为与升级前完全一致。
 */
internal object AudibleSessionSelectionPolicy {

    data class Selection<T>(val selected: T?)

    fun <T, K> select(
        sessions: List<T>,
        keyOf: (T) -> K,
        heldKey: K?,
        inMusicCatalog: (T) -> Boolean,
        audioAvailable: Boolean,
        isAudible: (T) -> Boolean,
        lastAudibleAtMs: (T) -> Long?,
        audioStartedAtMs: (T) -> Long?,
        nowMs: Long,
        silentHoldYieldMs: Long,
        freshStartWindowMs: Long,
    ): Selection<T>? {
        if (!audioAvailable || sessions.isEmpty()) return null

        val held = heldKey?.let { key -> sessions.firstOrNull { keyOf(it) == key } }
        val candidates = sessions.filter { inMusicCatalog(it) && isAudible(it) }
        // 双发声并列时（挑战者进场那拍会把所有在场发声 uid 的时刻刷成同拍）以
        // 「转入发声时刻」决胜，保住最新播放器事件者的身份，对齐官方仲裁器语义。
        val best = candidates.maxWithOrNull(
            compareBy(
                { lastAudibleAtMs(it) ?: Long.MIN_VALUE },
                { audioStartedAtMs(it) ?: Long.MIN_VALUE },
            )
        )

        if (held == null) {
            if (best != null) return Selection(best)
            // 全静默且无持有：取目录内「最近发声史」最新的会话，替代纯会话规则
            // 「列表第一个」的兜底（2026-09-29 22:51 真机曾掉进停播一小时的僵尸）。
            val byHistory = sessions
                .filter { inMusicCatalog(it) && lastAudibleAtMs(it) != null }
                .maxByOrNull { lastAudibleAtMs(it)!! }
                ?: // 目录会话均无发声史：音频层无信息，交回旧规则。
                return null
            return Selection(byHistory)
        }
        if (best == null) {
            // 目录内无人发声（全部暂停/仅视频在响）：歌词冻结与保持语义交回旧规则。
            return null
        }

        if (keyOf(held) == keyOf(best)) return Selection(held)
        if (!inMusicCatalog(held)) {
            // 非目录持有者（视频等）不享有保持权：目录会话一开始发声立即让位。
            return Selection(best)
        }

        val heldLast = lastAudibleAtMs(held)
        if (isAudible(held)) {
            // 双方都在发声：挑战者「新近转入发声」（用户刚按播放）立即接管——持有者被
            // 抢焦点后系统有 ~2s 淡出尾巴，淡出期间双方发声时刻被刷成同拍，等尾巴结束
            // 才切会让文字恒定迟 2 秒（2026-09-29 23:45-23:47 真机十连切换实测）。
            // 无新转入的稳态双播保持持有者（换曲间隙重入的防抖见下方静默分支）。
            val challengerFresh = audioStartedAtMs(best)
                ?.let { nowMs - it <= freshStartWindowMs } == true
            if (challengerFresh) return Selection(best)
            val heldStart = heldLast ?: Long.MIN_VALUE
            val bestStart = lastAudibleAtMs(best) ?: Long.MIN_VALUE
            return if (bestStart > heldStart) Selection(best) else Selection(held)
        }

        // 持有者不发声：挑战者「新近转入发声」（用户刚按播放）立即接管，不等静默宽限。
        // 修复 210160 回归：正常 A→B 交接中 A 先被抢焦点暂停（静默仅 1-2 秒、在宽限
        // 期内），若等宽限会把切换拖慢 15 秒（2026-09-29 23:23 真机实测酷我迟 16 秒）。
        // 长期在放的挑战者仍须等持有者静默超时：双播下换曲间隙/缓冲的短暂静默不被
        // 长播放者挤走（2026-09-20 计时宽限否决的核心教训）。
        val challengerStartedAt = audioStartedAtMs(best)
        val challengerFresh = challengerStartedAt != null &&
            nowMs - challengerStartedAt <= freshStartWindowMs
        val silentForMs = heldLast?.let { nowMs - it }
        val keepHeld = !challengerFresh &&
            silentForMs != null && silentForMs < silentHoldYieldMs
        return if (keepHeld) Selection(held) else Selection(best)
    }
}
