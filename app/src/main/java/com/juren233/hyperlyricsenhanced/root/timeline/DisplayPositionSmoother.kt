/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 播放中的显示位置平滑器：把「真实位置」（源时钟/会话外推，可能前后跳动）
 * 变成只向前走的显示位置。
 *
 * 逐字渲染（WordSyncRenderer）收到比上次小的位置会直接 seek 并 jumpTo，
 * 进度条肉眼可见地抽回去；向前的小幅纠正也会让当前字瞬间填满。所以纠偏
 * 改成调速：
 * - 显示落后：按误差加速（最高 [MAX_CATCH_UP_RATE] 倍）追上，不越过真实位置；
 * - 显示超前：按误差减速，超前较多时原地等（速度 0），真实位置追上后恢复 1 倍速；
 * - 误差达到 [HARD_JUMP_MS]：当作真正的 seek / 切换，直接跳。
 *
 * 明确的 seek、换曲、换内容、暂停态由调用方 [snap]。纯逻辑，时间由调用方传入。
 */
internal class DisplayPositionSmoother {

    data class Step(val position: Long, val jumped: Boolean)

    /** 最近一次输出的显示位置；null 表示尚未建立（下一次推进直接取真实位置）。 */
    var displayed: Long? = null
        private set

    private var lastAtMs: Long = 0L

    /** 上一步使用的速度；1.0 表示与真实时钟同步，没有在纠偏。 */
    var rate: Double = 1.0
        private set

    val isCorrecting: Boolean get() = rate != 1.0

    fun reset() {
        displayed = null
        lastAtMs = 0L
        rate = 1.0
    }

    /** 直接对齐到 [position]（seek、换曲、暂停态等离散事件）。 */
    fun snap(position: Long, nowMs: Long): Long {
        val value = position.coerceAtLeast(0L)
        displayed = value
        lastAtMs = nowMs
        rate = 1.0
        return value
    }

    /** 位置循环（重新）开始：保留显示位置，只把计时起点挪到现在，暂停时长不计入推进。 */
    fun rebase(nowMs: Long) {
        lastAtMs = nowMs
        rate = 1.0
    }

    /** 播放中推进一帧：返回本帧显示位置，且保证不小于上一帧（除非判定为跳转）。 */
    fun advance(truth: Long, nowMs: Long): Step {
        val target = truth.coerceAtLeast(0L)
        val previous = displayed
        val dt = nowMs - lastAtMs
        if (previous == null || dt < 0L || dt > MAX_STEP_GAP_MS) {
            return Step(snap(target, nowMs), jumped = previous != null && previous != target)
        }
        // 按 1 倍速自由前进后与真实位置的误差：正 = 显示落后，负 = 显示超前。
        val error = target - (previous + dt)
        if (abs(error) >= HARD_JUMP_MS) {
            return Step(snap(target, nowMs), jumped = true)
        }
        val next: Long
        if (abs(error) <= DEADBAND_MS) {
            // 几十毫秒以内的误差肉眼不可见：直接对齐，只是不往回走。
            rate = 1.0
            next = maxOf(previous, target)
        } else {
            rate = rateFor(error)
            val stepped = previous + (dt * rate).roundToLong()
            next = if (error > 0L) {
                // 追赶不越过真实位置。
                minOf(stepped, target)
            } else {
                // 等待期间真实位置若已越过显示位置，跟上它即可。
                maxOf(stepped, target)
            }.coerceAtLeast(previous)
        }
        displayed = next
        lastAtMs = nowMs
        return Step(next, jumped = false)
    }

    companion object {
        /** 误差达到这个量（前后都算）就不再调速，当作 seek 直接跳。 */
        const val HARD_JUMP_MS = 3_000L

        /** 误差在此以内直接对齐（不后退），不算纠偏。 */
        const val DEADBAND_MS = 20L

        /** 落后时最高追赶速度。 */
        const val MAX_CATCH_UP_RATE = 2.0

        /** 落后误差每有这么多毫秒，速度 +1 倍（封顶 [MAX_CATCH_UP_RATE]）。 */
        const val CATCH_UP_TAU_MS = 500.0

        /** 超前误差每有这么多毫秒，速度 -1 倍（下限 0，即原地等）。 */
        const val SLOW_DOWN_TAU_MS = 500.0

        /** 两帧间隔超过这个值说明循环停过（或主线程长时间卡住），直接对齐。 */
        const val MAX_STEP_GAP_MS = 2_000L

        fun rateFor(error: Long): Double = if (error > 0L) {
            (1.0 + error / CATCH_UP_TAU_MS).coerceAtMost(MAX_CATCH_UP_RATE)
        } else {
            (1.0 + error / SLOW_DOWN_TAU_MS).coerceAtLeast(0.0)
        }
    }
}
