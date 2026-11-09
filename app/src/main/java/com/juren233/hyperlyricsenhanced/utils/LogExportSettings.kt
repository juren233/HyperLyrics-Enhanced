/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.utils

import com.juren233.hyperlyricsenhanced.common.SensitivePreferences
import java.net.URI

/** 日志导出里打码后的取值文案，由调用方从字符串资源填入。 */
internal data class LogExportSettingLabels(
    val secretSet: String,
    val secretEmpty: String,
    val defaultValue: String,
    /** 参数为主机名。 */
    val hostOnlyFormat: String,
    /** 参数为字符数。 */
    val customLengthFormat: String,
    val customValue: String,
)

/**
 * 把模块偏好（`SharedPreferences.all`）整理成按键名排序的「键 = 值」行。
 * 只含已写入的键；敏感项按 [SensitivePreferences] 打码。
 */
internal object LogExportSettings {
    fun lines(all: Map<String, *>, labels: LogExportSettingLabels): List<String> =
        all.entries
            .sortedBy { it.key }
            .map { (key, value) -> "$key = ${formatValue(key, value, labels)}" }

    private fun formatValue(key: String, value: Any?, labels: LogExportSettingLabels): String {
        if (key in SensitivePreferences.SECRET_KEYS) {
            return if (value?.toString().isNullOrBlank()) labels.secretEmpty else labels.secretSet
        }
        if (key in SensitivePreferences.HOST_ONLY_KEYS) {
            val url = value?.toString()?.trim().orEmpty()
            if (url.isEmpty()) return labels.defaultValue
            val host = runCatching { URI(url).host }.getOrNull()?.takeIf(String::isNotBlank)
            return host?.let { String.format(labels.hostOnlyFormat, it) } ?: labels.customValue
        }
        SensitivePreferences.TEXT_LENGTH_ONLY_KEYS[key]?.let { default ->
            val text = value?.toString().orEmpty()
            if (text.isBlank() || text == default) return labels.defaultValue
            return String.format(labels.customLengthFormat, text.length)
        }
        return when (value) {
            null -> "null"
            is String -> "\"${escape(value)}\""
            is Set<*> -> value.map { escape(it.toString()) }.sorted().joinToString(", ", "[", "]")
            else -> value.toString()
        }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\"", "\\\"")
}
