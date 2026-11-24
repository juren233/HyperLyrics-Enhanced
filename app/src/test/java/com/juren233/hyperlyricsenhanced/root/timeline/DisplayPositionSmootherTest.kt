/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DisplayPositionSmootherTest {

    private class Run(start: Long, val smoother: DisplayPositionSmoother = DisplayPositionSmoother()) {
        var now = 1_000_000L
        val shown = mutableListOf<Long>()
        val jumps = mutableListOf<Long>()

        init {
            smoother.snap(start, now)
            shown += start
        }

        /** 以 [frameMs] 帧间隔推进 [durationMs]，真实位置由 [truthAt]（相对起点的毫秒）给出。 */
        fun play(durationMs: Long, frameMs: Long = 33L, truthAt: (Long) -> Long) {
            val begin = now
            while (now - begin < durationMs) {
                now += frameMs
                val step = smoother.advance(truthAt(now - begin), now)
                if (step.jumped) jumps += step.position
                shown += step.position
            }
        }

        fun assertMonotonic() {
            shown.zipWithNext().forEach { (a, b) -> assertTrue("went back $a -> $b", b >= a) }
        }
    }

    @Test
    fun `in sync playback advances at real speed without correcting`() {
        val run = Run(10_000)
        run.play(2_000) { 10_000 + it }
        run.assertMonotonic()
        assertTrue(run.jumps.isEmpty())
        assertEquals(10_000 + 2_013L, run.shown.last())
        assertFalse(run.smoother.isCorrecting)
    }

    @Test
    fun `display ahead of truth never moves backwards and waits for truth`() {
        // 显示超前 1.2s（旧逻辑会一次性抽回去）。
        val run = Run(21_200)
        run.play(5_000) { 20_000 + it }
        run.assertMonotonic()
        assertTrue(run.jumps.isEmpty())
        // 超前较多时原地等：开头几帧不前进。
        assertEquals(21_200L, run.shown[3])
        // 最终与真实位置同步。
        assertTrue(abs(run.shown.last() - (20_000 + run.now - 1_000_000L)) <= DisplayPositionSmoother.DEADBAND_MS)
        assertFalse(run.smoother.isCorrecting)
    }

    @Test
    fun `display behind truth speeds up but never overshoots`() {
        val run = Run(20_000)
        var maxLead = Long.MIN_VALUE
        run.play(5_000) { elapsed ->
            val truth = 21_000 + elapsed
            truth
        }
        run.shown.forEachIndexed { i, shown ->
            val truth = if (i == 0) 21_000L else 21_000 + i * 33L
            maxLead = maxOf(maxLead, shown - truth)
        }
        run.assertMonotonic()
        assertTrue(run.jumps.isEmpty())
        assertTrue("overshot by $maxLead", maxLead <= 0)
        // 第一帧就开始加速：前进超过 1 倍速，但不超过最高倍速。
        val firstStep = run.shown[1] - run.shown[0]
        assertTrue(firstStep > 33)
        assertTrue(firstStep <= (33 * DisplayPositionSmoother.MAX_CATCH_UP_RATE).toLong())
        assertTrue(abs(run.shown.last() - (21_000 + run.now - 1_000_000L)) <= DisplayPositionSmoother.DEADBAND_MS)
    }

    @Test
    fun `stale anchor snapping back mid playback is absorbed`() {
        // 进度正常推进 3s 后，真实时钟突然被重新锚定到 800ms 之前（TTL 到期/持续回退重锚）。
        val run = Run(50_000)
        run.play(3_000) { 50_000 + it }
        val before = run.shown.last()
        run.play(4_000) { 50_000 + 3_000 - 800 + it }
        run.assertMonotonic()
        assertTrue(run.jumps.isEmpty())
        assertTrue(run.shown.last() > before)
    }

    @Test
    fun `large discontinuity is a real seek and jumps`() {
        val run = Run(50_000)
        run.play(500) { 50_000 + it }
        run.play(100) { 120_000 + it }
        assertEquals(1, run.jumps.size)
        assertTrue(run.jumps.single() >= 120_000)

        val back = Run(120_000)
        back.play(100) { 30_000 + it }
        assertEquals(1, back.jumps.size)
        assertTrue(back.jumps.single() < 40_000)
    }

    @Test
    fun `just below the jump threshold is still smoothed`() {
        val run = Run(60_000)
        run.play(9_000) { 60_000 - (DisplayPositionSmoother.HARD_JUMP_MS - 200) + it }
        run.assertMonotonic()
        assertTrue(run.jumps.isEmpty())
    }

    @Test
    fun `jittery truth does not make display go backwards`() {
        val run = Run(0)
        val jitter = longArrayOf(0, -15, 30, -40, 10, -25, 45, -5)
        run.play(3_000) { it + jitter[(it / 33 % jitter.size).toInt()] }
        run.assertMonotonic()
        assertTrue(run.jumps.isEmpty())
    }

    @Test
    fun `rebase after a pause does not count paused time`() {
        val smoother = DisplayPositionSmoother()
        smoother.snap(30_000, 1_000)
        smoother.rebase(60_000)
        val step = smoother.advance(30_033, 60_033)
        assertFalse(step.jumped)
        assertEquals(30_033L, step.position)
    }

    @Test
    fun `long frame gap aligns directly`() {
        val smoother = DisplayPositionSmoother()
        smoother.snap(30_000, 1_000)
        val step = smoother.advance(31_000, 1_000 + DisplayPositionSmoother.MAX_STEP_GAP_MS + 1)
        assertEquals(31_000L, step.position)
    }

    @Test
    fun `first advance without a display position adopts truth`() {
        val smoother = DisplayPositionSmoother()
        val step = smoother.advance(12_345, 5_000)
        assertFalse(step.jumped)
        assertEquals(12_345L, step.position)
    }

    @Test
    fun `rate is bounded in both directions`() {
        assertEquals(DisplayPositionSmoother.MAX_CATCH_UP_RATE, DisplayPositionSmoother.rateFor(2_900), 0.0)
        assertEquals(1.5, DisplayPositionSmoother.rateFor(250), 1e-9)
        assertEquals(0.5, DisplayPositionSmoother.rateFor(-250), 1e-9)
        assertEquals(0.0, DisplayPositionSmoother.rateFor(-2_900), 0.0)
    }
}
