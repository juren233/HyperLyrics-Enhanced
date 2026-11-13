/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.common.IslandRealRhythmConfig

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 原生音频律动 `R.raw.music_wave` 的几何，单位是 Lottie 画布坐标（66×66）。
 * 数值取自 miui.systemui.plugin 18.3.2.67.0 原包 `res/ElL.json`：
 * 5 个图层，中心 x = 9/21/33/45/57、y = 33；每层的形状组带 300% 缩放（`tr.s = [300, 300]`），
 * 组内胶囊路径宽 1.8（圆角半径 0.9）、高 3.654..14.906，渐变自上而下只覆盖胶囊直线段。
 * 图层自身的 `ks.s = [-100, 100]` 只是水平镜像，胶囊左右对称，不影响绘制。
 * 真实律动只在这里定义一次，绘制与测试都引用这里。
 */
internal object IslandRealRhythmGeometry {
    const val CANVAS_SIZE = 66f
    val CENTER_X = floatArrayOf(9f, 21f, 33f, 45f, 57f)
    const val CENTER_Y = 33f
    /** 形状组缩放（`tr.s` 300%）。 */
    private const val SHAPE_SCALE = 3f
    private const val PATH_WIDTH = 1.8f
    private const val PATH_MIN_HEIGHT = 3.654f
    private const val PATH_MAX_HEIGHT = 14.906f
    const val BAR_WIDTH = PATH_WIDTH * SHAPE_SCALE
    const val MIN_HEIGHT = PATH_MIN_HEIGHT * SHAPE_SCALE
    const val MAX_HEIGHT = PATH_MAX_HEIGHT * SHAPE_SCALE
    val BAR_COUNT: Int get() = CENTER_X.size

    fun height(level: Float): Float = MIN_HEIGHT + (MAX_HEIGHT - MIN_HEIGHT) * level.coerceIn(0f, 1f)
}

/**
 * 一组律动参数，取自 [IslandRealRhythmConfig.Values]（「开启」为默认值，「自定义」为用户设置）。
 * 换算出的平滑系数在构造时算好，FFT 回调与帧回调直接读取。
 */
internal class IslandRealRhythmTuning(val values: IslandRealRhythmConfig.Values) {
    val envelopeAlpha: Float = alpha(values.envelopeMs.toFloat())
    val statsAlpha: Float = alpha(values.statsMs.toFloat())
    val deviations: Float = values.deviationsTenths / 10f
    /** 反向后至少保持的捕获帧数（每帧 [IslandRealRhythmAnalyzer.CAPTURE_INTERVAL_MS]）。 */
    val holdFrames: Int = Math.round(values.holdMs / IslandRealRhythmAnalyzer.CAPTURE_INTERVAL_MS)
    val upPerFrame: Float = values.upPercent / 100f
    val downPerFrame: Float = values.downPercent / 100f
    val weakBand: Boolean = values.weakBand

    companion object {
        val DEFAULT = IslandRealRhythmTuning(IslandRealRhythmConfig.DEFAULT_VALUES)

        /** 时间常数（ms）→ 每个捕获间隔的指数系数；0 表示不平滑。 */
        private fun alpha(timeMs: Float): Float =
            if (timeMs <= 0f) 1f else 1f - exp(-IslandRealRhythmAnalyzer.CAPTURE_INTERVAL_MS / timeMs)
    }
}

/**
 * 把 Visualizer 的 FFT 帧换算成每根条 0..1 的目标高度。
 * 频段从左到右由低到高。处理分三步（参数见 [IslandRealRhythmTuning]）：
 * 1. 包络：每个频段的 dB 做指数平滑，去掉帧间噪声；
 * 2. 归一化：以包络在统计窗口内的均值为中位（0.5），偏离若干个平均偏差即到顶/到底，
 *    各频段按自己的近期分布铺开，响度变化不影响幅度；
 * 3. 反向保持：方向反转后至少保持若干帧，0 表示不保持。
 * 单个频段某一帧没有能量时：开启「按很弱处理」则按 0 dB 计入，否则该条直接落到最低；
 * 五个频段都没有能量时都落到最低。
 * 算法与律动对比页（真机录制频谱 + 模拟音乐）一致，2026-11-12。
 */
internal class IslandRealRhythmAnalyzer(
    private val bandEdgesHz: FloatArray = DEFAULT_BAND_EDGES_HZ,
) {
    init {
        require(bandEdgesHz.size == IslandRealRhythmGeometry.BAR_COUNT + 1)
    }

    private val count = IslandRealRhythmGeometry.BAR_COUNT
    private val envelopeDb = FloatArray(count) { Float.NaN }
    private val meanDb = FloatArray(count) { Float.NaN }
    private val deviationDb = FloatArray(count) { INITIAL_DEVIATION_DB }
    private val bandDb = FloatArray(count)
    private val silent = BooleanArray(count)
    private val heldLevel = FloatArray(count)
    private val heldDirection = IntArray(count)
    private val framesSinceTurn = IntArray(count) { HOLD_IDLE_FRAMES }

    /** 最近一帧各频段的原始 dB（没有能量为 NaN），供 debug 统计与调参。 */
    val lastDb = FloatArray(count) { Float.NaN }

    fun reset() {
        envelopeDb.fill(Float.NaN)
        meanDb.fill(Float.NaN)
        deviationDb.fill(INITIAL_DEVIATION_DB)
        heldLevel.fill(0f)
        heldDirection.fill(0)
        framesSinceTurn.fill(HOLD_IDLE_FRAMES)
    }

    /**
     * @param fft Visualizer.getFft 格式：[0] 直流实部、[1] 奈奎斯特实部，其后为第 k 个频点的 (实部, 虚部)。
     * @param samplingRateMilliHz Visualizer 报告的采样率（毫赫兹）。
     */
    fun analyze(
        fft: ByteArray,
        samplingRateMilliHz: Int,
        out: FloatArray,
        tuning: IslandRealRhythmTuning = IslandRealRhythmTuning.DEFAULT,
    ) {
        val n = fft.size
        val sampleRateHz = samplingRateMilliHz / 1000f
        if (n < 4 || sampleRateHz <= 0f) {
            out.fill(0f)
            lastDb.fill(Float.NaN)
            return
        }
        val binHz = sampleRateHz / n
        val lastBin = n / 2 - 1
        var anyEnergy = false
        for (band in 0 until count) {
            val from = (bandEdgesHz[band] / binHz).toInt().coerceIn(1, lastBin)
            val to = max(from + 1, (bandEdgesHz[band + 1] / binHz).toInt().coerceIn(1, lastBin + 1))
            var sum = 0.0
            for (k in from until to) {
                val re = fft[2 * k].toDouble()
                val im = fft[2 * k + 1].toDouble()
                sum += re * re + im * im
            }
            val magnitude = sqrt(sum / (to - from))
            if (magnitude < SILENCE_MAGNITUDE) {
                lastDb[band] = Float.NaN
                silent[band] = true
                bandDb[band] = 0f
            } else {
                anyEnergy = true
                val db = (20.0 * log10(magnitude)).toFloat()
                lastDb[band] = db
                silent[band] = false
                bandDb[band] = db
            }
        }
        for (band in 0 until count) {
            if (silent[band] && (!tuning.weakBand || !anyEnergy)) {
                out[band] = hold(band, 0f, tuning)
                continue
            }
            val db = bandDb[band]
            val envelope = envelopeDb[band].let { if (it.isNaN()) db else it + (db - it) * tuning.envelopeAlpha }
            envelopeDb[band] = envelope
            val mean = meanDb[band].let { if (it.isNaN()) envelope else it + (envelope - it) * tuning.statsAlpha }
            deviationDb[band] += (abs(envelope - mean) - deviationDb[band]) * tuning.statsAlpha
            meanDb[band] = mean
            val scale = tuning.deviations * max(deviationDb[band], MIN_DEVIATION_DB)
            out[band] = hold(band, (0.5f + (envelope - mean) / (2f * scale)).coerceIn(0f, 1f), tuning)
        }
    }

    /** 方向反转后至少保持 [IslandRealRhythmTuning.holdFrames] 帧；变化小于 [HOLD_DEAD_ZONE] 视为未动。 */
    private fun hold(band: Int, level: Float, tuning: IslandRealRhythmTuning): Float {
        if (tuning.holdFrames <= 0) return level
        framesSinceTurn[band]++
        val delta = level - heldLevel[band]
        if (abs(delta) < HOLD_DEAD_ZONE) return heldLevel[band]
        val direction = if (delta > 0f) 1 else -1
        if (direction != heldDirection[band]) {
            if (framesSinceTurn[band] < tuning.holdFrames) return heldLevel[band]
            heldDirection[band] = direction
            framesSinceTurn[band] = 0
        }
        heldLevel[band] = level
        return level
    }

    companion object {
        val DEFAULT_BAND_EDGES_HZ = floatArrayOf(60f, 250f, 500f, 1200f, 3500f, 10000f)
        /** Visualizer 捕获间隔（最大捕获频率 20 Hz，240101 探测实测）。 */
        const val CAPTURE_INTERVAL_MS = 50f
        /** 平均偏差下限，近乎恒定的频段不被放大成满幅抖动。 */
        const val MIN_DEVIATION_DB = 1f
        const val INITIAL_DEVIATION_DB = 3f
        const val SILENCE_MAGNITUDE = 1.0
        private const val HOLD_DEAD_ZONE = 0.02f
        private const val HOLD_IDLE_FRAMES = 99
    }
}

/** 逐帧把当前高度滑向目标，升降速度见 [IslandRealRhythmTuning]（按 60 fps 每帧），与帧率无关。 */
internal class IslandRealRhythmSmoother(count: Int = IslandRealRhythmGeometry.BAR_COUNT) {
    val levels = FloatArray(count)

    fun reset() = levels.fill(0f)

    /** @return 是否仍在移动（未贴合目标）。 */
    fun step(
        targets: FloatArray,
        dtMs: Float,
        tuning: IslandRealRhythmTuning = IslandRealRhythmTuning.DEFAULT,
    ): Boolean {
        val frames = (dtMs / FRAME_MS).coerceIn(0f, 8f)
        val up = 1f - (1f - tuning.upPerFrame).pow(frames)
        val down = 1f - (1f - tuning.downPerFrame).pow(frames)
        var moving = false
        for (i in levels.indices) {
            val target = targets.getOrElse(i) { 0f }
            levels[i] += (target - levels[i]) * (if (target > levels[i]) up else down)
            if (abs(target - levels[i]) > SETTLED) moving = true else levels[i] = target
        }
        return moving
    }

    companion object {
        private const val FRAME_MS = 1000f / 60f
        private const val SETTLED = 0.002f
    }
}
