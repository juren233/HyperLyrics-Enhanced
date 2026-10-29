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

/**
 * What one online source delivered for a song: [hasTranslation]/[hasPronunciation] when it
 * returned lyrics, or [searchFailed] when it returned nothing usable (no hit, timeout, network).
 * A source absent from the encoded map has not been searched yet.
 */
data class OnlineSourceContentState(
    val hasTranslation: Boolean,
    val hasPronunciation: Boolean,
    val searchFailed: Boolean = false,
)

object OnlineSourceContentStatesCodec {
    private const val SOURCE_SEPARATOR = ";"
    private const val VALUE_SEPARATOR = "="
    private const val FAILED = "F"
    private const val TRANSLATION = 'T'
    private const val PRONUNCIATION = 'P'
    private const val EMPTY = "-"

    fun encode(states: Map<String, OnlineSourceContentState>): String? = states
        .asSequence()
        .filter { it.key.isNotBlank() }
        .joinToString(SOURCE_SEPARATOR) { (source, state) ->
            source + VALUE_SEPARATOR + when {
                state.searchFailed -> FAILED
                else -> buildString {
                    if (state.hasTranslation) append(TRANSLATION)
                    if (state.hasPronunciation) append(PRONUNCIATION)
                }.ifEmpty { EMPTY }
            }
        }
        .takeIf(String::isNotBlank)

    fun decode(encoded: String?): Map<String, OnlineSourceContentState> = encoded
        .orEmpty()
        .split(SOURCE_SEPARATOR)
        .mapNotNull { token ->
            val value = token.split(VALUE_SEPARATOR, limit = 2)
            if (value.size != 2 || value[0].isBlank()) return@mapNotNull null
            value[0] to OnlineSourceContentState(
                hasTranslation = TRANSLATION in value[1],
                hasPronunciation = PRONUNCIATION in value[1],
                searchFailed = value[1] == FAILED,
            )
        }
        .toMap()
}
