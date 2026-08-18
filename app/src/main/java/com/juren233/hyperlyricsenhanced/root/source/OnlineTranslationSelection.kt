/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationContentPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationMatchStat
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationMatchStatsCodec
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.online.model.Source

/**
 * Keeps the fetched source payloads until the result reaches the main thread.
 *
 * Apple Music can emit another native lyric snapshot while an online request is
 * running. The selected sources remain stable, but their lines must be matched
 * again against that latest snapshot instead of merging an old index-based
 * result into a new line structure.
 */
internal data class OnlineTranslationSelection(
    val onlineLinesBySource: Map<Source, List<LrcLine>> = emptyMap(),
    val requestedSources: List<Source> = onlineLinesBySource.keys.toList(),
    val defaultTranslationSource: Source? = null,
    val defaultPronunciationSource: Source? = null,
    val forcedTranslationSource: Source? = null,
    val forcedPronunciationSource: Source? = null,
    val sourceOrder: List<Source> = emptyList(),
    val pronunciationRequested: Boolean = false,
) {
    fun matchCandidates(latestNativeSong: Song): Map<Source, OnlineTranslationMatcher.Result> =
        onlineLinesBySource.mapValues { (_, onlineLines) ->
            OnlineTranslationMatcher.apply(latestNativeSong, onlineLines)
        }

    fun compose(
        latestNativeSong: Song,
        currentPublishedSong: Song?,
    ): OnlineTranslationMatcher.Result? = composeMatched(
        latestNativeSong = latestNativeSong,
        currentPublishedSong = currentPublishedSong,
        rebasedCandidates = matchCandidates(latestNativeSong),
    )

    fun composeMatched(
        latestNativeSong: Song,
        currentPublishedSong: Song?,
        rebasedCandidates: Map<Source, OnlineTranslationMatcher.Result>,
    ): OnlineTranslationMatcher.Result? {
        val composed = if (sourceOrder.isNotEmpty() &&
            forcedTranslationSource == null &&
            forcedPronunciationSource == null
        ) {
            OnlineTranslationMatcher.composeOrderedSources(
                baseSong = latestNativeSong,
                candidates = rebasedCandidates,
                sourceOrder = sourceOrder,
                currentPublishedSong = currentPublishedSong,
            )
        } else {
            OnlineTranslationMatcher.composeSelectedSources(
                baseSong = latestNativeSong,
                candidates = rebasedCandidates,
                defaultTranslationSource = defaultTranslationSource,
                defaultPronunciationSource = defaultPronunciationSource,
                forcedTranslationSource = forcedTranslationSource,
                forcedPronunciationSource = forcedPronunciationSource,
                currentPublishedSong = currentPublishedSong,
            )
        }
        return composed?.withOnlineContentMatchStats(
            baseSong = latestNativeSong,
            candidates = rebasedCandidates,
        )
    }

    private fun OnlineTranslationMatcher.Result.withOnlineContentMatchStats(
        baseSong: Song,
        candidates: Map<Source, OnlineTranslationMatcher.Result>,
    ): OnlineTranslationMatcher.Result {
        val baseLines = baseSong.lyrics.orEmpty()
        fun statsFor(
            matches: (baseLine: com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine,
                candidateLine: com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine?) -> Boolean,
        ): Map<String, OnlineTranslationMatchStat> = candidates.mapValues { (_, candidate) ->
            val matchedLines = baseLines.indices.count { index ->
                val baseLine = baseLines[index]
                val candidateLine = candidate.song.lyrics?.getOrNull(index)
                matches(baseLine, candidateLine)
            }
            OnlineTranslationMatchStat(
                matchedLines = matchedLines,
                totalLines = baseLines.size,
            )
        }.mapKeys { it.key.name }
        val translationStats = statsFor { baseLine, candidateLine ->
            !OnlineTranslationContentPolicy.isMeaningful(baseLine.translation) &&
                OnlineTranslationContentPolicy.isMeaningful(candidateLine?.translation)
        }
        val pronunciationStats = statsFor { baseLine, candidateLine ->
            baseLine.roma.isNullOrBlank() && !candidateLine?.roma.isNullOrBlank()
        }
        val encodedTranslationStats = OnlineTranslationMatchStatsCodec.encode(translationStats)
        val encodedPronunciationStats = OnlineTranslationMatchStatsCodec.encode(pronunciationStats)
        val metadataEntries = song.metadata?.entries
            ?.filterNot {
                it.key == LyricMetadataKeys.ONLINE_TRANSLATION_MATCH_STATS ||
                    it.key == LyricMetadataKeys.ONLINE_PRONUNCIATION_MATCH_STATS
            }
            ?.map { it.key to it.value }
            .orEmpty()
            .toMutableList()
        encodedTranslationStats?.let {
            metadataEntries += LyricMetadataKeys.ONLINE_TRANSLATION_MATCH_STATS to it
        }
        encodedPronunciationStats?.let {
            metadataEntries += LyricMetadataKeys.ONLINE_PRONUNCIATION_MATCH_STATS to it
        }
        return copy(
            song = song.copy(
                metadata = metadataEntries
                    .takeIf { it.isNotEmpty() }
                    ?.let { lyricMetadataOf(*it.toTypedArray()) }
            )
        )
    }
}
