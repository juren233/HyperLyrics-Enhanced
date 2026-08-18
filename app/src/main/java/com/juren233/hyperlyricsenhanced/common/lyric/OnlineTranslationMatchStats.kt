/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.lyric

import kotlin.math.roundToInt

data class OnlineTranslationMatchStat(
    val matchedLines: Int,
    val totalLines: Int,
) {
    val percentage: Int
        get() = if (totalLines <= 0) {
            0
        } else {
            (matchedLines.coerceIn(0, totalLines) * 100.0 / totalLines)
                .roundToInt()
                .coerceIn(0, 100)
        }
}

object OnlineTranslationMatchStatsCodec {
    private const val SOURCE_SEPARATOR = ";"
    private const val VALUE_SEPARATOR = "="
    private const val COUNT_SEPARATOR = "/"

    fun encode(stats: Map<String, OnlineTranslationMatchStat>): String? = stats
        .asSequence()
        .filter { (source, stat) -> source.isNotBlank() && stat.totalLines >= 0 }
        .sortedBy { it.key }
        .joinToString(SOURCE_SEPARATOR) { (source, stat) ->
            "$source$VALUE_SEPARATOR${stat.matchedLines.coerceAtLeast(0)}" +
                "$COUNT_SEPARATOR${stat.totalLines}"
        }
        .takeIf(String::isNotBlank)

    fun decode(encoded: String?): Map<String, OnlineTranslationMatchStat> = encoded
        .orEmpty()
        .split(SOURCE_SEPARATOR)
        .asSequence()
        .mapNotNull { token ->
            val value = token.split(VALUE_SEPARATOR, limit = 2)
            if (value.size != 2 || value[0].isBlank()) return@mapNotNull null
            val counts = value[1].split(COUNT_SEPARATOR, limit = 2)
            if (counts.size != 2) return@mapNotNull null
            val matched = counts[0].toIntOrNull() ?: return@mapNotNull null
            val total = counts[1].toIntOrNull() ?: return@mapNotNull null
            value[0] to OnlineTranslationMatchStat(matched, total)
        }
        .toMap()
}
