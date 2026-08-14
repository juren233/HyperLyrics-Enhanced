/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationContentPolicy
import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.online.model.Source
import kotlin.math.abs

internal object OnlineTranslationDiagnostics {
    data class LineContribution(
        val index: Int,
        val begin: Long,
        val text: String,
        val translationSource: Source?,
        val backgroundTranslationSource: Source?,
        val pronunciationSource: Source?,
    )

    data class MissingLine(
        val index: Int,
        val begin: Long,
        val text: String,
        val missing: List<String>,
        val reasonsBySource: Map<Source, List<String>>,
    )

    fun contributions(
        baseSong: Song,
        resultSong: Song,
        candidates: Map<Source, OnlineTranslationMatcher.Result>,
        translationOrder: List<Source>,
        pronunciationOrder: List<Source>,
    ): List<LineContribution> = baseSong.lyrics.orEmpty().indices.mapNotNull { index ->
        val base = baseSong.lyrics?.getOrNull(index) ?: return@mapNotNull null
        val result = resultSong.lyrics?.getOrNull(index) ?: return@mapNotNull null
        val translationSource = if (
            !OnlineTranslationContentPolicy.isMeaningful(base.translation) &&
            OnlineTranslationContentPolicy.isMeaningful(result.translation)
        ) {
            translationOrder.firstOrNull { source ->
                OnlineTranslationContentPolicy.isMeaningful(
                    candidates[source]?.song?.lyrics?.getOrNull(index)?.translation
                )
            }
        } else {
            null
        }
        val baseBackground = base.backgroundTranslation()
        val resultBackground = result.backgroundTranslation()
        val backgroundSource = if (
            !OnlineTranslationContentPolicy.isMeaningful(baseBackground) &&
            OnlineTranslationContentPolicy.isMeaningful(resultBackground)
        ) {
            translationOrder.firstOrNull { source ->
                OnlineTranslationContentPolicy.isMeaningful(
                    candidates[source]?.song?.lyrics?.getOrNull(index)?.backgroundTranslation()
                )
            }
        } else {
            null
        }
        val pronunciationSource = if (base.roma.isNullOrBlank() && !result.roma.isNullOrBlank()) {
            pronunciationOrder.firstOrNull { source ->
                !candidates[source]?.song?.lyrics?.getOrNull(index)?.roma.isNullOrBlank()
            }
        } else {
            null
        }
        if (translationSource == null && backgroundSource == null && pronunciationSource == null) {
            null
        } else {
            LineContribution(
                index = index,
                begin = result.begin,
                text = result.text.orEmpty(),
                translationSource = translationSource,
                backgroundTranslationSource = backgroundSource,
                pronunciationSource = pronunciationSource,
            )
        }
    }

    fun missingLines(
        resultSong: Song,
        requestedSources: List<Source>,
        onlineLinesBySource: Map<Source, List<LrcLine>>,
        candidates: Map<Source, OnlineTranslationMatcher.Result>,
        pronunciationRequested: Boolean,
    ): List<MissingLine> = resultSong.lyrics.orEmpty().mapIndexedNotNull { index, line ->
        val missing = buildList {
            if (!OnlineTranslationContentPolicy.isMeaningful(line.translation)) add("translation")
            if (pronunciationRequested && line.roma.isNullOrBlank()) add("pronunciation")
        }
        if (line.text.isNullOrBlank() || missing.isEmpty()) return@mapIndexedNotNull null
        MissingLine(
            index = index,
            begin = line.begin,
            text = line.text.orEmpty(),
            missing = missing,
            reasonsBySource = requestedSources.associateWith { source ->
                missing.map { content ->
                    reasonFor(
                        content = content,
                        nativeLine = line,
                        nativeIndex = index,
                        onlineLines = onlineLinesBySource[source],
                        candidate = candidates[source],
                    )
                }
            },
        )
    }

    private fun reasonFor(
        content: String,
        nativeLine: RichLyricLine,
        nativeIndex: Int,
        onlineLines: List<LrcLine>?,
        candidate: OnlineTranslationMatcher.Result?,
    ): String {
        if (onlineLines == null) return "candidate_unavailable"
        val candidateIndices = candidate?.lineCandidateIndices?.get(nativeIndex).orEmpty()
        // Matcher indices refer to the time-sorted payload before invalid-text
        // candidates are filtered, so use that exact ordering for diagnostics.
        val sortedOnlineLines = onlineLines.sortedBy(LrcLine::startTimeMs)
        val relevantLines = if (candidateIndices.isNotEmpty()) {
            candidateIndices.mapNotNull(sortedOnlineLines::getOrNull)
        } else {
            onlineLines.filter { online -> likelySameLine(nativeLine, online) }
        }
        if (relevantLines.isEmpty()) return "match_miss"
        return when (content) {
            "translation" -> when {
                relevantLines.any { isSlashPlaceholder(it.translation) } -> "placeholder_sanitized"
                relevantLines.none { OnlineTranslationContentPolicy.isMeaningful(it.translation) } ->
                    "matched_line_no_translation"
                candidateIndices.isEmpty() -> "match_miss"
                else -> "translation_not_applied"
            }
            "pronunciation" -> when {
                relevantLines.none { !it.romanization.isNullOrBlank() } ->
                    "matched_line_no_pronunciation"
                candidateIndices.isEmpty() -> "match_miss"
                else -> "pronunciation_not_applied"
            }
            else -> "unknown"
        }
    }

    private fun likelySameLine(nativeLine: RichLyricLine, onlineLine: LrcLine): Boolean =
        abs(nativeLine.begin - onlineLine.startTimeMs) <= 1_500L ||
            comparableText(nativeLine.text.orEmpty()) == comparableText(onlineLine.content)

    private fun comparableText(text: String): String = text
        .lowercase()
        .filter(Char::isLetterOrDigit)

    private fun isSlashPlaceholder(text: String?): Boolean {
        val compact = text.orEmpty().filterNot(Char::isWhitespace)
        return compact.isNotEmpty() && compact.all { it == '/' }
    }

    private fun RichLyricLine.backgroundTranslation(): String? = metadata
        ?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
}
