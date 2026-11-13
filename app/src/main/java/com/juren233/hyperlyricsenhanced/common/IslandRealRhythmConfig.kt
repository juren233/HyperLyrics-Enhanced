/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.common

/**
 * 「跟随音乐真实律动」的模式与自定义参数。键、默认值、取值范围只在这里定义，
 * 设置页滑块与 SystemUI 读取共用。参数项与律动对比页的滑块一一对应，
 * 默认值是用户在对比页调定的值（2026-11-12：包络 75 ms，其余见 [DEFAULT_VALUES]）。
 * 为沿用整数偏好的广播同步，小数参数按固定倍数存成整数（见各项注释）。
 */
object IslandRealRhythmConfig {
    const val MODE_OFF = 0
    const val MODE_ON = 1
    const val MODE_CUSTOM = 2

    const val KEY_MODE = "key_hook_island_music_wave_real_rhythm_mode"
    const val DEFAULT_MODE = MODE_OFF

    class IntParam(val key: String, val default: Int, val min: Int, val max: Int, val step: Int) {
        val steps: Int get() = (max - min) / step - 1
        fun coerce(value: Int): Int = (min + ((value - min).toFloat() / step).let(Math::round) * step).coerceIn(min, max)
    }

    /** 包络平滑时间常数，毫秒；0 表示不平滑。 */
    val ENVELOPE_MS = IntParam("key_hook_island_music_wave_real_rhythm_envelope_ms", 75, 0, 400, 25)
    /** 归一化统计窗口，毫秒。 */
    val STATS_MS = IntParam("key_hook_island_music_wave_real_rhythm_stats_ms", 2000, 500, 6000, 500)
    /** 偏离几个平均偏差到顶，存十分之一（10 = 1.0）。 */
    val DEVIATIONS_TENTHS = IntParam("key_hook_island_music_wave_real_rhythm_deviations_tenths", 10, 8, 30, 1)
    /** 反向后至少保持，毫秒；0 表示不保持。 */
    val HOLD_MS = IntParam("key_hook_island_music_wave_real_rhythm_hold_ms", 0, 0, 500, 50)
    /** 显示上升速度（每帧，60 fps），存百分之一（18 = 0.18）。 */
    val UP_PERCENT = IntParam("key_hook_island_music_wave_real_rhythm_up_percent", 18, 5, 80, 1)
    /** 显示回落速度（每帧，60 fps），存百分之一。 */
    val DOWN_PERCENT = IntParam("key_hook_island_music_wave_real_rhythm_down_percent", 18, 5, 80, 1)

    /** 单个频段没能量时按「很弱」处理；关闭时该频段直接落到最低。 */
    const val KEY_WEAK_BAND = "key_hook_island_music_wave_real_rhythm_weak_band"
    const val DEFAULT_WEAK_BAND = true

    val intParams: List<IntParam> = listOf(ENVELOPE_MS, STATS_MS, DEVIATIONS_TENTHS, HOLD_MS, UP_PERCENT, DOWN_PERCENT)

    val preferenceKeys: Set<String> = buildSet {
        add(KEY_MODE)
        add(KEY_WEAK_BAND)
        intParams.forEach { add(it.key) }
    }

    data class Values(
        val envelopeMs: Int,
        val statsMs: Int,
        val deviationsTenths: Int,
        val holdMs: Int,
        val upPercent: Int,
        val downPercent: Int,
        val weakBand: Boolean,
    )

    val DEFAULT_VALUES = Values(
        envelopeMs = ENVELOPE_MS.default,
        statsMs = STATS_MS.default,
        deviationsTenths = DEVIATIONS_TENTHS.default,
        holdMs = HOLD_MS.default,
        upPercent = UP_PERCENT.default,
        downPercent = DOWN_PERCENT.default,
        weakBand = DEFAULT_WEAK_BAND,
    )

    fun resolveMode(stored: Int): Int = if (stored in MODE_OFF..MODE_CUSTOM) stored else DEFAULT_MODE

    /** 「开启」用默认值，「自定义」按存储值（越界的按范围收回）。 */
    fun resolveValues(mode: Int, readInt: (IntParam) -> Int, readWeakBand: () -> Boolean): Values =
        if (mode != MODE_CUSTOM) {
            DEFAULT_VALUES
        } else {
            Values(
                envelopeMs = ENVELOPE_MS.coerce(readInt(ENVELOPE_MS)),
                statsMs = STATS_MS.coerce(readInt(STATS_MS)),
                deviationsTenths = DEVIATIONS_TENTHS.coerce(readInt(DEVIATIONS_TENTHS)),
                holdMs = HOLD_MS.coerce(readInt(HOLD_MS)),
                upPercent = UP_PERCENT.coerce(readInt(UP_PERCENT)),
                downPercent = DOWN_PERCENT.coerce(readInt(DOWN_PERCENT)),
                weakBand = readWeakBand(),
            )
        }
}
