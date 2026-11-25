/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.root.timeline.DisplayPositionSmoother
import com.juren233.hyperlyricsenhanced.root.timeline.SourceClockTracker
import com.juren233.hyperlyricsenhanced.root.timeline.SourceClockUpdatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 用真机日志（2026-11-25 09:52 Apple Music「Villain」息屏兜底歌词）
 * 回放中心进度样本，验证兜底期间的进度不再来回跳。
 *
 * 时间统一取 09:52:00.000 起的毫秒数。日志里的原始数据：
 * - 21.087 `central_anchor_publish reason=song_changed position=22231`（亮屏 ticker 起点，41ms 一次）
 * - 直连回调 `path=direct_primary rawPosition=21362` 出现在 21.153、21.170、21.175、21.253（直连进度早已
 *   冻结在 21362，21.253 是兜底前最后一次）
 * - 21.273 `Apple Music 在线兜底命中`，此后直连回调被丢弃
 * - 22.770 `central_position_ticker event=stop_requested screen=OFF`；最后一个 tick 值 23909，之后订阅端原始值停在 23909
 *   （24.290 `Timing receive rawPosition=23909`、25.224 `subscriber_callback rawPosition=23909`）
 * - 息屏 `central_anchor_publish reason=playback_state`：25398@25.359、30424@30.385、35447@35.406、
 *   40470@40.435、45520@45.492、50578@50.540（真实读数）
 * - MediaSession 外推 mediaPosition：21496@20.366 起 1 倍速（29.276=30405、35.419=36549 吻合），
 *   比真实读数恒定超前约 1091ms
 * - 日志里时间轴的 SEEK：21.813(21362)、30.809(30424)、35.825(35447)、40.850(40470)、
 *   45.907(45520)、50.961(50578)；以及 MEDIA_REPLACED_DIVERGENT 的前跳 29.276、34.298、39.319、44.342、49.421
 *
 * 推断（日志没有逐条记录）：订阅端回调约 16ms 一次、每次重复最新原始值；发布值约 20ms 后到达订阅端；
 * 亮屏 ticker 每 41ms 一个 tick、以最后一个 tick（22.768，值 23909）往前按 1 倍速倒推。
 */
class AppleFallbackPositionReplayTest {

    private val songGeneration = 1
    private val songDuration = 153_000L
    private val fallbackStartMs = 21_273L
    private val tickerAnchorPosition = 22_231L
    private val tickerIntervalMs = 41L
    private val lastTickAtMs = 22_768L
    private val lastTickPosition = 23_909L
    private val publishDeliveryMs = 20L
    private val publishes = listOf(
        25_359L to 25_398L,
        30_385L to 30_424L,
        35_406L to 35_447L,
        40_435L to 40_470L,
        45_492L to 45_520L,
        50_540L to 50_578L,
    )
    private val directCallbackTimesMs = listOf(21_153L, 21_170L, 21_175L, 21_253L)
    private val directFrozenPosition = 21_362L
    private val replayStartMs = 21_169L
    private val replayEndMs = 52_000L
    private val callbackIntervalMs = 16L
    private val loopIntervalMs = 33L

    private fun mediaPosition(nowMs: Long): Long = 21_496L + (nowMs - 20_366L)

    /** 真实进度：卡顿后以 25.359 的发布值为准（之后每次发布都与它 1 倍速外推相差 <30ms）。 */
    private fun truthPosition(nowMs: Long): Long = 25_398L + (nowMs - 25_359L)

    /** 订阅端在 [nowMs] 收到的中心原始进度（亮屏 ticker 外推值 / 息屏冻结值 / 最近一次发布值）。 */
    private fun centralRawPosition(nowMs: Long): Long {
        val latestPublish = publishes.lastOrNull { (at, _) -> at + publishDeliveryMs <= nowMs }
        if (latestPublish != null) return latestPublish.second
        if (nowMs >= lastTickAtMs) return lastTickPosition
        val ticksBeforeLast = (lastTickAtMs - nowMs + tickerIntervalMs - 1) / tickerIntervalMs
        return lastTickPosition - ticksBeforeLast * tickerIntervalMs
    }

    /** 兜底前直连回调不断刷新参考时刻（值一直是冻结的 21362）；兜底后直连回调被丢弃，参考停在 21.253。 */
    private fun directReference(nowMs: Long): AppleCentralPositionPolicy.DirectReference? =
        directCallbackTimesMs.lastOrNull { it <= nowMs }?.let { observedAt ->
            AppleCentralPositionPolicy.DirectReference(songGeneration, directFrozenPosition, observedAt)
        }

    private fun resolveOldPath(raw: Long, nowMs: Long): Long? = AppleCentralPositionPolicy.resolve(
        centralPosition = raw,
        currentSongDuration = songDuration,
        currentSongGeneration = songGeneration,
        mediaReference = AppleCentralPositionPolicy.MediaReference(
            songGeneration = songGeneration,
            title = "Villain",
            artist = "mj apanay",
            duration = songDuration,
            position = mediaPosition(nowMs),
            isPlaying = true,
            playbackSpeed = 1f,
            observedAtMs = nowMs,
        ),
        directReference = directReference(nowMs),
        providerDelayMs = 0,
        nowMs = nowMs,
        explicitSeek = false,
    ).position

    /** 与 LocalTimelineDriver 播放中的路径一致：来源回调校准源时钟，33ms 位置循环推进显示。 */
    private class Timeline(private val fallbackTruth: (Long) -> Long) {
        val tracker = SourceClockTracker()
        val smoother = DisplayPositionSmoother()
        val seeks = mutableListOf<Pair<Long, Long>>()
        val jumps = mutableListOf<Long>()
        val shown = mutableListOf<Pair<Long, Long>>()

        /** 每段纠偏开始时的（时刻, 速度）。 */
        val corrections = mutableListOf<Pair<Long, Double>>()
        private var correcting = false

        fun start(position: Long, nowMs: Long) {
            tracker.accept(position, explicitSeek = false, nowMs = nowMs, playing = true)
            smoother.snap(position, nowMs)
        }

        fun onSource(position: Long, nowMs: Long) {
            val result = tracker.accept(position, explicitSeek = false, nowMs = nowMs, playing = true)
            if (result.action == SourceClockUpdatePolicy.Action.SEEK) {
                seeks += nowMs to position
                val truth = tracker.projected(nowMs, playing = true) ?: position
                record(smoother.advance(truth, nowMs), nowMs)
            }
        }

        fun onLoop(nowMs: Long) {
            val truth = tracker.projected(nowMs, playing = true) ?: fallbackTruth(nowMs)
            record(smoother.advance(truth, nowMs), nowMs)
        }

        private fun record(step: DisplayPositionSmoother.Step, nowMs: Long) {
            if (step.jumped) jumps += nowMs
            shown += nowMs to step.position
            if (smoother.isCorrecting && !correcting) corrections += nowMs to smoother.rate
            correcting = smoother.isCorrecting
        }
    }

    /**
     * [callbackPhaseMs]：回调相对 ticker 的相位（日志不记录，逐一试遍 0..15ms）。
     * [dropDuringFallback]：c6beb2d 的做法，兜底期间丢弃全部中心进度。
     */
    private fun replay(fixed: Boolean, callbackPhaseMs: Long = 0L, dropDuringFallback: Boolean = false): Timeline {
        val timeline = Timeline(::mediaPosition)
        timeline.start(tickerAnchorPosition, replayStartMs)
        val fallbackClock = AppleFallbackCentralClock()
        var nextCallback = replayStartMs + callbackIntervalMs + callbackPhaseMs
        var nextLoop = replayStartMs + loopIntervalMs
        while (minOf(nextCallback, nextLoop) <= replayEndMs) {
            if (nextCallback <= nextLoop) {
                val now = nextCallback
                val raw = centralRawPosition(now)
                val forwarded = if (fixed && now >= fallbackStartMs) {
                    fallbackClock.accept(raw, providerDelayMs = 0)
                } else if (dropDuringFallback && now >= fallbackStartMs) {
                    null
                } else {
                    resolveOldPath(raw, now)
                }
                forwarded?.let { timeline.onSource(it, now) }
                nextCallback += callbackIntervalMs
            } else {
                timeline.onLoop(nextLoop)
                nextLoop += loopIntervalMs
            }
        }
        return timeline
    }

    @Test
    fun `old fallback path reproduces the logged back-and-forth seeks`() {
        val timeline = replay(fixed = false)

        // 日志里每隔约 5 秒一次的回拉 SEEK（incoming 为刚发布的真实读数），回放必须在同一时刻复现。
        val loggedBackwardSeeks = listOf(
            21_813L to 21_362L,
            30_809L to 30_424L,
            35_825L to 35_447L,
            40_850L to 40_470L,
            45_907L to 45_520L,
            50_961L to 50_578L,
        )
        loggedBackwardSeeks.forEach { (atMs, incoming) ->
            assertTrue(
                "missing logged seek to $incoming near $atMs; replayed seeks=${timeline.seeks}",
                timeline.seeks.any { (t, p) -> p == incoming && abs(t - atMs) <= 50L },
            )
        }
        // 以及 MediaSession 外推顶替冻结值造成的前跳（日志 MEDIA_REPLACED_DIVERGENT）。
        listOf(29_276L, 34_298L, 39_319L, 44_342L, 49_421L).forEach { atMs ->
            assertTrue(
                "missing media-substitution seek near $atMs; replayed seeks=${timeline.seeks}",
                timeline.seeks.any { (t, p) -> abs(t - atMs) <= 50L && p > truthPosition(t) + 900L },
            )
        }
        // 显示层被反复拉扯：2 倍速追赶和原地等待交替出现。
        assertTrue(
            "corrections=${timeline.corrections}",
            timeline.corrections.count { (_, rate) -> rate >= 1.9 } >= 4,
        )
    }

    @Test
    fun `fixed fallback path follows the real central readings without jumping`() {
        (0L until callbackIntervalMs).forEach { phase ->
            val timeline = replay(fixed = true, callbackPhaseMs = phase)
            val label = "phase=$phase"
            val afterFallback = timeline.shown.filter { (t, _) -> t >= fallbackStartMs }

            assertEquals("$label seeks=${timeline.seeks}", emptyList<Pair<Long, Long>>(),
                timeline.seeks.filter { it.first >= fallbackStartMs })
            assertEquals(label, emptyList<Long>(), timeline.jumps)
            afterFallback.zipWithNext().forEach { (a, b) ->
                assertTrue("$label display went back at ${b.first}: ${a.second} -> ${b.second}", b.second >= a.second)
            }
            // 卡顿（21~25 秒之间）之后只允许一段纠偏：显示原地等约 1.1 秒，等真实进度追上。
            // 它在卡顿后第一个读数（25.359，20ms 后送达）还是第二个读数（30.385）触发（加上回调和 33ms 循环的间隔，最多晚 60ms），取决于 ticker 最后一个样本
            // 是否恰好比外推早几毫秒（日志不记录这个细节），两种都覆盖到。
            // 发布读数本身有 ±30ms 抖动，超出 20ms 死区时会有一两帧 0.95 倍速的微调，肉眼不可见，
            // 这里只数速度偏离 1 倍超过 0.2（误差超过 100ms）的纠偏。
            val corrections = timeline.corrections.filter { (t, rate) -> t >= lastTickAtMs && abs(rate - 1.0) >= 0.2 }
            assertEquals("$label corrections=$corrections", 1, corrections.size)
            val (correctedAt, rate) = corrections.single()
            assertEquals("$label corrections=$corrections", 0.0, rate, 0.0)
            assertTrue(
                "$label corrections=$corrections",
                correctedAt - 25_379L in 0L..60L || correctedAt - 30_405L in 0L..60L,
            )
            // 纠偏开始前：超前约 1.1 秒（卡顿造成，读数稀疏时只能等读数确认）。
            timeline.shown.filter { (t, _) -> t in 25_400L until correctedAt }.forEach { (t, position) ->
                val lead = position - truthPosition(t)
                assertTrue("$label lead $lead at $t", lead in 1_050L..1_200L)
            }
            // 平滑器先原地等、再按误差减速逼近（误差 500ms 以内是指数收敛，约 2.2 秒进 20ms 死区），
            // 2.5 秒后直到回放结束都贴着真实读数走。
            timeline.shown.filter { (t, _) -> t >= correctedAt + 2_500L }.forEach { (t, position) ->
                assertTrue(
                    "$label display $position vs truth ${truthPosition(t)} at $t",
                    abs(position - truthPosition(t)) <= 60L,
                )
            }
        }
    }

    @Test
    fun `dropping every fallback reading leaves the display ahead until the song ends`() {
        // 提交前（c6beb2d）兜底期间丢弃全部中心进度：源时钟 15 秒后过期，显示改跟 MediaSession 外推，
        // 一直超前约 1.1 秒，没有任何读数能把它拉回来。
        (0L until callbackIntervalMs).forEach { phase ->
            val timeline = replay(fixed = false, callbackPhaseMs = phase, dropDuringFallback = true)
            timeline.shown.filter { (t, _) -> t >= 40_000L }.forEach { (t, position) ->
                val lead = position - truthPosition(t)
                assertTrue("phase=$phase lead $lead at $t", lead >= 900L)
            }
        }
    }

    @Test
    fun `old fallback path keeps jumping for every callback phase`() {
        (0L until callbackIntervalMs).forEach { phase ->
            val timeline = replay(fixed = false, callbackPhaseMs = phase)
            val seeksAfterStall = timeline.seeks.filter { (t, _) -> t >= 26_000L }
            assertTrue("phase=$phase seeks=$seeksAfterStall", seeksAfterStall.size >= 8)
        }
    }

    @Test
    fun `fallback clock forwards each new reading once and drops frozen repeats`() {
        val clock = AppleFallbackCentralClock()
        assertEquals(25_398L, clock.accept(25_398L, providerDelayMs = 0))
        assertEquals(null, clock.accept(25_398L, providerDelayMs = 0))
        assertEquals(null, clock.accept(25_398L, providerDelayMs = 0))
        assertEquals(30_224L, clock.accept(30_424L, providerDelayMs = 200))
        clock.reset()
        assertEquals(30_424L, clock.accept(30_424L, providerDelayMs = 0))
        assertFalse(clock.accept(0L, providerDelayMs = 500) == null)
    }
}
