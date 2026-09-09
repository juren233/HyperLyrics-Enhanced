/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.common.lyric.ChineseLyricsPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationContentPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.online.model.Source
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal fun OnlineTranslationMatcher.removeBracketedSegments(text: String): String = buildString(text.length) {
    var bracketDepth = 0
    text.forEach { char ->
        when (char) {
            '(', '[', '{', '（', '【', '｛' -> bracketDepth++
            ')', ']', '}', '）', '】', '｝' -> {
                if (bracketDepth > 0) bracketDepth-- else append(char)
            }
            else -> if (bracketDepth == 0) append(char)
        }
    }
}

internal fun OnlineTranslationMatcher.mainTextWithoutBracketedSegments(text: String): String =
    extractBracketedSegments(text).first.ifBlank {
        removeBracketedSegments(text).replace(Regex("\\s+"), " ").trim()
    }

internal fun OnlineTranslationMatcher.extractBracketedSegments(text: String): Pair<String, List<String>> {
    val main = StringBuilder(text.length)
    val segment = StringBuilder()
    val segments = mutableListOf<String>()
    var bracketDepth = 0
    text.forEach { char ->
        when (char) {
            '(', '[', '{', '（', '【', '｛' -> {
                if (bracketDepth > 0) segment.append(char)
                bracketDepth++
            }
            ')', ']', '}', '）', '】', '｝' -> {
                if (bracketDepth == 0) {
                    main.append(char)
                } else {
                    bracketDepth--
                    if (bracketDepth == 0) {
                        segment.toString().trim().takeIf(String::isNotEmpty)?.let(segments::add)
                        segment.clear()
                    } else {
                        segment.append(char)
                    }
                }
            }
            else -> if (bracketDepth == 0) main.append(char) else segment.append(char)
        }
    }
    if (bracketDepth > 0 && segment.isNotEmpty()) {
        main.append(segment)
    }
    val normalizedMain = main.toString().replace(Regex("\\s+"), " ").trim()
    return normalizedMain to segments
}

internal fun OnlineTranslationMatcher.similarity(first: String, second: String): Double {
    if (first == second) return 1.0
    if (first.isEmpty() || second.isEmpty()) return 0.0
    val longerLength = max(first.length, second.length)
    if ((first.contains(second) || second.contains(first)) && min(first.length, second.length) >= 4) {
        return min(first.length, second.length).toDouble() / longerLength
    }
    return 1.0 - levenshteinDistance(first, second).toDouble() / longerLength
}

internal fun OnlineTranslationMatcher.levenshteinDistance(first: String, second: String): Int {
    var previous = IntArray(second.length + 1) { it }
    var current = IntArray(second.length + 1)
    for (firstIndex in first.indices) {
        current[0] = firstIndex + 1
        for (secondIndex in second.indices) {
            val substitutionCost = if (first[firstIndex] == second[secondIndex]) 0 else 1
            current[secondIndex + 1] = minOf(
                current[secondIndex] + 1,
                previous[secondIndex + 1] + 1,
                previous[secondIndex] + substitutionCost
            )
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[second.length]
}

internal data class Candidate(
    val originalIndex: Int,
    val line: LrcLine,
    val normalizedVariants: List<String>
)

internal data class TranslationParts(val main: String, val background: String?)

internal data class MatchPlan(
    val candidateIndex: Int,
    val nativeSpan: Int,
    val candidateSpan: Int,
    val score: Double
)
