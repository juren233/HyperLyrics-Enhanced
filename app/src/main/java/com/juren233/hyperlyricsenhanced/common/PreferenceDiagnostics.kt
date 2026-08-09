/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.BuildConfig

/**
 * Debug-only formatting for comparing app-local and Xposed remote preferences.
 * Sensitive values are intentionally reduced to presence and length.
 */
object PreferenceDiagnostics {
    private val sensitiveKeyMarkers = setOf(
        "api_key",
        "authorization",
        "cookie",
        "password",
        "secret",
        "token",
    )

    fun logSnapshot(
        source: String,
        prefs: SharedPreferences,
        logger: (String) -> Unit,
    ) {
        if (!BuildConfig.DEBUG) return

        val entries = runCatching { prefs.all.toSortedMap() }.getOrElse { error ->
            logger(
                "snapshot_failed source=$source error=${sanitize(error.message ?: error.javaClass.simpleName)}",
            )
            return
        }
        logger("snapshot_begin source=$source count=${entries.size}")
        entries.forEach { (key, value) ->
            logger(
                "snapshot_entry source=$source key=$key " +
                    "type=${typeName(value)} value=${formatValue(key, value)}",
            )
        }
        logger("snapshot_end source=$source count=${entries.size}")
    }

    fun formatValue(key: String, value: Any?): String {
        if (isSensitiveKey(key)) {
            val length = value?.toString()?.length ?: 0
            return "<redacted${if (length > 0) " length=$length" else ""}>"
        }
        return when (value) {
            null -> "<null>"
            is String -> "\"${sanitize(value)}\""
            is Set<*> -> value.joinToString(",", prefix = "[", postfix = "]") {
                sanitize(it?.toString().orEmpty())
            }
            else -> sanitize(value.toString())
        }
    }

    fun typeName(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> "boolean"
        is Int -> "int"
        is Long -> "long"
        is Float -> "float"
        is String -> "string"
        is Set<*> -> "string_set"
        else -> value.javaClass.simpleName.ifBlank { "${value.javaClass}" }
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase()
        return sensitiveKeyMarkers.any(normalized::contains)
    }

    private fun sanitize(value: String): String {
        val normalized = value
            .replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
        return if (normalized.length <= 512) normalized else {
            "${normalized.take(512)}...(truncated,length=${normalized.length})"
        }
    }
}
