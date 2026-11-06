/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences

/**
 * 以字符串集合落盘的偏好键：备份把集合写成逗号拼接的字符串，恢复时必须按此表还原为集合。
 * 新增集合型偏好时只在这里登记，备份恢复、读取容错和启动迁移都引用这一份。
 */
internal object StringSetPreferences {
    val KEYS: Set<String> = setOf(
        ServiceConstants.KEY_NOTIFICATION_WHITELIST,
        RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE_APP_WHITELIST,
        MediaSessionOnlineFallbackPreferences.PACKAGES,
    )

    /** 备份格式里的集合：逗号分隔，忽略空白与空项。 */
    fun parse(raw: String): Set<String> =
        raw.split(",").map(String::trim).filter(String::isNotEmpty).toSet()

    /**
     * 读取集合偏好。值被误存为字符串（旧版恢复备份的遗留）时按备份格式解析，
     * 不能抛类型转换异常——SystemUI 的歌词运行时在启动阶段读取这些键，一次异常会让整条管线起不来。
     */
    fun read(prefs: SharedPreferences, key: String, default: Set<String>?): Set<String>? =
        when (val value = prefs.all[key]) {
            is Set<*> -> value.filterIsInstance<String>().toSet()
            is String -> parse(value)
            else -> default
        }
}
