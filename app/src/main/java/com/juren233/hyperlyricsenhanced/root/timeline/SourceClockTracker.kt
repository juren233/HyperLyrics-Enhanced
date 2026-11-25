/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

/**
 * 源时钟锚点：活动源最近一次被接受的（位置, 单调时刻），以及「持续偏后」连续段。
 * 从 [LocalTimelineDriver] 抽出的纯逻辑，时间由调用方传入，便于用真机日志回放验证。
 */
internal class SourceClockTracker(private val ttlMs: Long = DEFAULT_TTL_MS) {

    data class Result(
        val action: SourceClockUpdatePolicy.Action,
        val previous: Long?,
        val projected: Long?,
        /** 重新锚定前的偏后连续时长；不在连续段内为 null。 */
        val endedBacktrackStreakMs: Long?,
    )

    var anchorPosition: Long? = null
        private set

    /** 锚点外推起点；0 = 不外推（暂停中或尚未建立）。 */
    var anchorElapsedMs: Long = 0L
        private set

    /** 最近一次真正收到源样本的单调时刻（恢复播放时的重新计时不算）。 */
    var sampleAtMs: Long = 0L
        private set

    /** 当前这段「源样本持续偏后」从何时开始；0 = 不在偏后连续段内。 */
    var backtrackSinceMs: Long = 0L
        private set

    fun accept(position: Long, explicitSeek: Boolean, nowMs: Long, playing: Boolean): Result {
        val anchored = position.coerceAtLeast(0L)
        val previous = anchorPosition
        val projected = projected(nowMs, playing)
        val action = SourceClockUpdatePolicy.decide(
            explicitSeek = explicitSeek,
            previousAnchorPosition = previous,
            projectedPosition = projected,
            incomingPosition = anchored,
            backtrackStreakMs = backtrackSinceMs.takeIf { it > 0L }?.let { nowMs - it },
        )
        var endedStreak: Long? = null
        when (action) {
            SourceClockUpdatePolicy.Action.IGNORE_DUPLICATE -> Unit
            SourceClockUpdatePolicy.Action.IGNORE_BACKTRACK -> {
                if (backtrackSinceMs == 0L) backtrackSinceMs = nowMs
            }
            SourceClockUpdatePolicy.Action.ANCHOR,
            SourceClockUpdatePolicy.Action.SEEK -> {
                if (backtrackSinceMs != 0L) endedStreak = nowMs - backtrackSinceMs
                backtrackSinceMs = 0L
                anchorPosition = anchored
                anchorElapsedMs = nowMs
                sampleAtMs = nowMs
            }
        }
        return Result(action, previous, projected, endedStreak)
    }

    /** TTL 内按 1 倍速外推；会话不在播放时停在锚点值；过期或缺席为 null。 */
    fun projected(nowMs: Long, playing: Boolean): Long? {
        val src = anchorPosition ?: return null
        val age = nowMs - anchorElapsedMs
        if (age !in 0..ttlMs) return null
        return if (playing) src + age else src
    }

    /** 恢复播放：暂停期间的样本是冻结值，从现在起外推，暂停时长不计入。 */
    fun retimeOnResume(nowMs: Long) {
        if (anchorPosition != null && anchorElapsedMs != 0L) anchorElapsedMs = nowMs
    }

    /** 暂停：保留最后的源值用于去重，但停止外推。 */
    fun stopProjecting() {
        anchorElapsedMs = 0L
    }

    fun reset() {
        anchorPosition = null
        anchorElapsedMs = 0L
        sampleAtMs = 0L
        backtrackSinceMs = 0L
    }

    companion object {
        const val DEFAULT_TTL_MS = 15_000L
    }
}
