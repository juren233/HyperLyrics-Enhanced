/*
 * Copyright 2026 juren233
 */
package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences

/**
 * 浮点型偏好的唯一登记处。备份 JSON 里整数值的浮点（如 1.0）会被序列化成 `1`，
 * 恢复时若按 JSON 类型写入会变成 Int，之后任何 `getFloat` 都会抛 ClassCastException，
 * 因此恢复、迁移与读取都以这里登记的键为准。
 */
internal object FloatPreferences {
    val KEYS: Set<String> = setOf(
        RootConstants.KEY_HOOK_TEXT_SIZE_RATIO,
        RootConstants.KEY_HOOK_SWITCH_ANIM_CUSTOM_RATE,
        RootConstants.KEY_HOOK_WORD_MOTION_CJK_LIFT,
        RootConstants.KEY_HOOK_WORD_MOTION_CJK_WAVE,
        RootConstants.KEY_HOOK_WORD_MOTION_LATIN_LIFT,
        RootConstants.KEY_HOOK_WORD_MOTION_LATIN_WAVE,
        RootConstants.KEY_HOOK_AI_TRANS_TEMPERATURE,
        RootConstants.KEY_HOOK_AI_TRANS_TOP_P,
        RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
        RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP,
    )

    /** 容忍历史上被写成 Int/Long/Double/String 的值；无法解析时返回默认值。 */
    fun read(prefs: SharedPreferences, key: String, default: Float): Float =
        when (val value = prefs.all[key]) {
            is Float -> value
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull() ?: default
            else -> default
        }
}
