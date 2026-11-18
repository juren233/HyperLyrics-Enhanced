/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.common.IslandRealRhythmConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandRealRhythmModelTest {
    private val sampleRateMilliHz = 48_000_000
    private val size = 512

    /** 在指定频率的频点写入幅值，其余为 0。 */
    private fun fftWith(vararg tones: Pair<Float, Int>): ByteArray {
        val fft = ByteArray(size)
        val binHz = sampleRateMilliHz / 1000f / size
        tones.forEach { (hz, magnitude) ->
            val k = (hz / binHz).toInt()
            fft[2 * k] = magnitude.toByte()
        }
        return fft
    }

    @Test fun `geometry matches native music wave extremes`() {
        assertEquals(IslandRealRhythmGeometry.MIN_HEIGHT, IslandRealRhythmGeometry.height(0f), 0f)
        assertEquals(IslandRealRhythmGeometry.MAX_HEIGHT, IslandRealRhythmGeometry.height(1f), 0f)
        assertEquals(IslandRealRhythmGeometry.MAX_HEIGHT, IslandRealRhythmGeometry.height(2f), 0f)
        assertEquals(5, IslandRealRhythmGeometry.BAR_COUNT)
    }

    @Test fun `silence keeps every bar at minimum`() {
        val out = FloatArray(5) { 1f }
        IslandRealRhythmAnalyzer().analyze(ByteArray(size), sampleRateMilliHz, out)
        out.forEach { assertEquals(0f, it, 0f) }
    }

    @Test fun `low tone drives the leftmost bar and other bands stay low`() {
        val analyzer = IslandRealRhythmAnalyzer()
        val out = FloatArray(5)
        repeat(40) { analyzer.analyze(fftWith(100f to if (it % 8 < 4) 30 else 120), sampleRateMilliHz, out) }
        assertTrue(out[0] > 0.6f)
        for (i in 1..4) assertTrue(out[i] < 0.6f)
    }

    @Test fun `sustained loud and quiet passages reach long and short heights`() {
        val analyzer = IslandRealRhythmAnalyzer()
        val out = FloatArray(5)
        repeat(200) { analyzer.analyze(fftWith(100f to if (it % 20 < 10) 30 else 90), sampleRateMilliHz, out) }
        var high = 0f
        var low = 1f
        repeat(40) {
            analyzer.analyze(fftWith(100f to if (it % 20 < 10) 30 else 90), sampleRateMilliHz, out)
            high = maxOf(high, out[0]); low = minOf(low, out[0])
        }
        assertTrue(high > 0.85f)
        assertTrue(low < 0.15f)
    }

    @Test fun `one silent band does not snap its bar to the bottom while others play`() {
        val analyzer = IslandRealRhythmAnalyzer()
        val out = FloatArray(5)
        repeat(200) { analyzer.analyze(fftWith(100f to 80, 6000f to if (it % 4 < 2) 6 else 10), sampleRateMilliHz, out) }
        analyzer.analyze(fftWith(100f to 80, 6000f to 10), sampleRateMilliHz, out)
        val before = out[4]
        analyzer.analyze(fftWith(100f to 80), sampleRateMilliHz, out)
        assertTrue(out[4] > 0f)
        assertTrue(before - out[4] < 0.9f)
    }

    @Test fun `nearly constant band stays near the middle`() {
        val analyzer = IslandRealRhythmAnalyzer()
        val out = FloatArray(5)
        repeat(300) { analyzer.analyze(fftWith(100f to if (it % 2 == 0) 100 else 98), sampleRateMilliHz, out) }
        assertTrue(out[0] > 0.2f && out[0] < 0.8f)
    }

    @Test fun `smoother glides toward the target and settles`() {
        val smoother = IslandRealRhythmSmoother(1)
        smoother.step(floatArrayOf(1f), 16.67f)
        assertEquals(IslandRealRhythmTuning.DEFAULT.upPerFrame, smoother.levels[0], 0.01f)
        repeat(200) { smoother.step(floatArrayOf(1f), 16.67f) }
        assertFalse(smoother.step(floatArrayOf(1f), 16.67f))
        assertEquals(1f, smoother.levels[0], 0f)
    }
    private fun custom(transform: IslandRealRhythmConfig.Values.() -> IslandRealRhythmConfig.Values) =
        IslandRealRhythmTuning(IslandRealRhythmConfig.DEFAULT_VALUES.transform())

    @Test fun `default tuning matches the values chosen on the lab page`() {
        val t = IslandRealRhythmTuning.DEFAULT
        assertEquals(1f - kotlin.math.exp(-50f / 75f), t.envelopeAlpha, 1e-6f)
        assertEquals(1f - kotlin.math.exp(-50f / 2000f), t.statsAlpha, 1e-6f)
        assertEquals(1f, t.deviations, 0f)
        assertEquals(0, t.holdFrames)
        assertEquals(0.18f, t.upPerFrame, 1e-6f)
        assertEquals(0.18f, t.downPerFrame, 1e-6f)
        assertTrue(t.weakBand)
    }

    @Test fun `zero envelope disables smoothing`() {
        assertEquals(1f, custom { copy(envelopeMs = 0) }.envelopeAlpha, 0f)
    }

    @Test fun `weak band off drops a silent band to the bottom`() {
        val analyzer = IslandRealRhythmAnalyzer()
        val out = FloatArray(5)
        val tuning = custom { copy(weakBand = false) }
        repeat(50) { analyzer.analyze(fftWith(100f to 80, 6000f to if (it % 4 < 2) 6 else 10), sampleRateMilliHz, out, tuning) }
        analyzer.analyze(fftWith(100f to 80), sampleRateMilliHz, out, tuning)
        assertEquals(0f, out[4], 0f)
    }

    @Test fun `hold keeps the level after a reversal for the configured frames`() {
        val analyzer = IslandRealRhythmAnalyzer()
        val out = FloatArray(5)
        val tuning = custom { copy(envelopeMs = 0, holdMs = 200) }
        // 持续上升后突降（发生反转），紧接着再回升：反转后 4 帧（200 ms）内保持在降下来的高度。
        repeat(30) { analyzer.analyze(fftWith(100f to 20 + it * 3), sampleRateMilliHz, out, tuning) }
        analyzer.analyze(fftWith(100f to 5), sampleRateMilliHz, out, tuning)
        val dropped = out[0]
        assertTrue(dropped < 0.5f)
        repeat(3) {
            analyzer.analyze(fftWith(100f to 107), sampleRateMilliHz, out, tuning)
            assertEquals(dropped, out[0], 0f)
        }
        analyzer.analyze(fftWith(100f to 107), sampleRateMilliHz, out, tuning)
        assertTrue(out[0] > dropped)
    }

    @Test fun `stored custom values are clamped to their ranges and steps`() {
        val values = IslandRealRhythmConfig.resolveValues(
            IslandRealRhythmConfig.MODE_CUSTOM,
            readInt = { if (it === IslandRealRhythmConfig.ENVELOPE_MS) 9999 else if (it === IslandRealRhythmConfig.HOLD_MS) 60 else it.default },
            readWeakBand = { false },
        )
        assertEquals(400, values.envelopeMs)
        assertEquals(50, values.holdMs)
        assertFalse(values.weakBand)
        assertEquals(IslandRealRhythmConfig.DEFAULT_VALUES, IslandRealRhythmConfig.resolveValues(IslandRealRhythmConfig.MODE_ON, { 1 }, { false }))
    }

    private fun state(
        enabled: Boolean = true,
        muted: Boolean = false,
        anyMusicWave: Boolean = true,
        startFailed: Boolean = false,
        anyVisible: Boolean = true,
        interactive: Boolean = true,
        startedAt: Long? = 1_000L,
        lastCallback: Long = -1L,
        lastEnergy: Long = -1L,
        now: Long = 1_500L,
    ) = IslandRealRhythmState.resolve(
        enabled, muted, anyMusicWave, startFailed, anyVisible, interactive,
        startedAt, lastCallback, lastEnergy, now,
    )

    @Test fun `state reports why rhythm is not taken over in priority order`() {
        assertEquals(IslandRealRhythmState.OFF, state(enabled = false, muted = true))
        assertEquals(IslandRealRhythmState.MUTED, state(muted = true, anyMusicWave = false))
        assertEquals(IslandRealRhythmState.NOT_MUSIC_WAVE, state(anyMusicWave = false, startFailed = true))
        assertEquals(IslandRealRhythmState.START_FAILED, state(startFailed = true, anyVisible = false))
        assertEquals(IslandRealRhythmState.HIDDEN, state(anyVisible = false, interactive = false))
        assertEquals(IslandRealRhythmState.SCREEN_OFF, state(interactive = false))
        assertEquals(IslandRealRhythmState.STARTING, state(startedAt = null))
    }

    @Test fun `state tracks capture health after engine start`() {
        val stall = IslandRealRhythmState.CAPTURE_STALL_MS
        assertEquals(IslandRealRhythmState.STARTING, state(now = 1_000L + stall - 1))
        assertEquals(IslandRealRhythmState.NO_CALLBACK, state(now = 1_000L + stall))
        assertEquals(
            IslandRealRhythmState.NO_ENERGY,
            state(lastCallback = 1_000L + stall, now = 1_000L + stall),
        )
        assertEquals(
            IslandRealRhythmState.RUNNING,
            state(lastCallback = 4_000L, lastEnergy = 4_000L, now = 4_100L),
        )
        // 回调还在，但有能量的数据已中断超过阈值。
        assertEquals(
            IslandRealRhythmState.NO_ENERGY,
            state(lastCallback = 8_000L, lastEnergy = 4_000L, now = 8_000L),
        )
        // 回调整体中断（例如共享效果被关闭）。
        assertEquals(
            IslandRealRhythmState.NO_CALLBACK,
            state(lastCallback = 4_000L, lastEnergy = 4_000L, now = 4_000L + stall),
        )
        // 上一次启动残留的时刻不算本次数据。
        assertEquals(IslandRealRhythmState.STARTING, state(lastCallback = 500L, lastEnergy = 500L))
    }

    @Test fun `transient states are not logged`() {
        assertFalse(IslandRealRhythmState.HIDDEN.logged)
        assertFalse(IslandRealRhythmState.SCREEN_OFF.logged)
        assertFalse(IslandRealRhythmState.STARTING.logged)
        assertTrue(IslandRealRhythmState.NO_CALLBACK.logged)
        assertTrue(IslandRealRhythmState.RUNNING.logged)
    }

    private fun capturePlayer(
        session: Int,
        started: Boolean = true,
        spatialized: Boolean = false,
    ) = IslandRealRhythmCaptureTarget.Player(session, started, spatialized)

    @Test fun `capture stays on output mix unless a started player is spatialized`() {
        assertEquals(0, IslandRealRhythmCaptureTarget.resolve(emptyList()))
        assertEquals(0, IslandRealRhythmCaptureTarget.resolve(listOf(capturePlayer(17545))))
        assertEquals(0, IslandRealRhythmCaptureTarget.resolve(listOf(capturePlayer(17545, started = false, spatialized = true))))
        assertEquals(0, IslandRealRhythmCaptureTarget.resolve(listOf(capturePlayer(0, spatialized = true))))
    }

    @Test fun `capture follows the spatialized media player session`() {
        assertEquals(
            17545,
            IslandRealRhythmCaptureTarget.resolve(
                listOf(capturePlayer(17113, started = false), capturePlayer(17545, spatialized = true)),
            ),
        )
    }
}
