/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceMetadata
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceStatus
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationContentPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineSourceContentState
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineSourceContentStatesCodec
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
    /**
     * Set only while the always-visible source dialogs are enabled. A forced source then
     * replaces Apple's own content instead of filling its gaps, and the per-source stats
     * describe every sung line, Apple Music included.
     */
    val control: OnlineSourceControl? = null,
    /** Lyric search outcome per source, for the lyrics source dialog of a track with Apple lyrics. */
    val lyricsSourceStatuses: List<AppleMissingLyricsSourceStatus> = emptyList(),
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
        val automaticFill = control?.automaticFill != false
        val composed = if (automaticFill &&
            sourceOrder.isNotEmpty() &&
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
                defaultTranslationSource = defaultTranslationSource.takeIf { automaticFill },
                defaultPronunciationSource = defaultPronunciationSource.takeIf { automaticFill },
                forcedTranslationSource = forcedTranslationSource,
                forcedPronunciationSource = forcedPronunciationSource,
                currentPublishedSong = currentPublishedSong,
            )
        }
        val control = control ?: return composed?.withOnlineContentMatchStats(
            baseSong = latestNativeSong,
            candidates = rebasedCandidates,
        )
        // Every source is matched again against lines with Apple's content blanked: that is
        // both what a hand-picked source may replace and what its coverage is measured on.
        val strippedNativeSong = OnlineSourceOverride.strip(
            song = latestNativeSong,
            translation = true,
            pronunciation = true,
        )
        val fullCandidates = onlineLinesBySource.mapValues { (_, onlineLines) ->
            OnlineTranslationMatcher.apply(strippedNativeSong, onlineLines)
        }
        return (
            composed ?: OnlineTranslationMatcher.Result(
                song = latestNativeSong,
                matchedCount = 0,
                averageMatchScore = 0.0,
            )
            )
            .withSourceControl(latestNativeSong, fullCandidates, control)
            .withSourceControlStats(latestNativeSong, fullCandidates)
    }

    private fun OnlineTranslationMatcher.Result.withSourceControl(
        nativeSong: Song,
        fullCandidates: Map<Source, OnlineTranslationMatcher.Result>,
        control: OnlineSourceControl,
    ): OnlineTranslationMatcher.Result {
        var composedSong = song
        val translationOverride = forcedTranslationSource
            ?.takeUnless { control.translationNativeOnly }
            ?.let { source -> fullCandidates[source]?.let { source to it.song } }
        val pronunciationOverride = forcedPronunciationSource
            ?.takeUnless { control.pronunciationNativeOnly }
            ?.let { source -> fullCandidates[source]?.let { source to it.song } }
        with(OnlineSourceOverride) {
            when {
                control.translationNativeOnly -> composedSong =
                    restoreNativeTranslation(composedSong, nativeSong)
                        .withMetadataValue(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE, null)
                translationOverride != null -> composedSong =
                    overlayTranslation(composedSong, translationOverride.second)
                        .withMetadataValue(
                            LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE,
                            translationOverride.first.name,
                        )
            }
            when {
                control.pronunciationNativeOnly -> composedSong =
                    restoreNativePronunciation(composedSong, nativeSong)
                        .withMetadataValue(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE, null)
                pronunciationOverride != null -> composedSong =
                    overlayPronunciation(composedSong, pronunciationOverride.second)
                        .withMetadataValue(
                            LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE,
                            pronunciationOverride.first.name,
                        )
            }
            composedSong = composedSong
                .withMetadataFlag(
                    LyricMetadataKeys.ONLINE_TRANSLATION_OVERRIDES_NATIVE,
                    translationOverride != null,
                )
                .withMetadataFlag(
                    LyricMetadataKeys.ONLINE_PRONUNCIATION_OVERRIDES_NATIVE,
                    pronunciationOverride != null,
                )
        }
        return copy(song = composedSong)
    }

    /** Stats and states for dialogs that list every source, Apple Music first, for every track. */
    private fun OnlineTranslationMatcher.Result.withSourceControlStats(
        nativeSong: Song,
        fullCandidates: Map<Source, OnlineTranslationMatcher.Result>,
    ): OnlineTranslationMatcher.Result {
        val native = OnlineSourceOverride.APPLE_NATIVE
        // 三方补充歌词自带的翻译/发音不是 Apple 的内容；这类歌曲的「Apple Music 原生」没有内容。
        val appleSong = nativeSong.takeUnless {
            it.metadata?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT).toBoolean()
        } ?: OnlineSourceOverride.strip(nativeSong, translation = true, pronunciation = true)
        val translationStats = fullCandidates
            .map { (source, candidate) ->
                source.name to OnlineSourceOverride.translationCoverage(candidate.song)
            }
            .toMap() + (native to OnlineSourceOverride.translationCoverage(appleSong))
        val pronunciationStats = fullCandidates
            .map { (source, candidate) ->
                source.name to OnlineSourceOverride.pronunciationCoverage(candidate.song)
            }
            .toMap() + (native to OnlineSourceOverride.pronunciationCoverage(appleSong))
        val sourceStates = requestedSourceStates() +
            (native to OnlineSourceOverride.nativeState(appleSong))
        var statsSong = song
        with(OnlineSourceOverride) {
            statsSong = statsSong
                .withMetadataValue(
                    LyricMetadataKeys.ONLINE_TRANSLATION_MATCH_STATS,
                    OnlineTranslationMatchStatsCodec.encode(translationStats),
                )
                .withMetadataValue(
                    LyricMetadataKeys.ONLINE_PRONUNCIATION_MATCH_STATS,
                    OnlineTranslationMatchStatsCodec.encode(pronunciationStats),
                )
                .withMetadataValue(
                    LyricMetadataKeys.ONLINE_SOURCE_CONTENT_STATES,
                    OnlineSourceContentStatesCodec.encode(sourceStates),
                )
                .withMetadataValue(
                    LyricMetadataKeys.ONLINE_LYRICS_SOURCE_STATUSES,
                    AppleMissingLyricsSourceMetadata.encodeStatuses(lyricsSourceStatuses),
                )
        }
        return copy(song = statsSong)
    }

    private fun requestedSourceStates(): Map<String, OnlineSourceContentState> =
        requestedSources.associate { source ->
            val lines = onlineLinesBySource[source]
            source.name to OnlineSourceContentState(
                hasTranslation = lines?.any {
                    OnlineTranslationContentPolicy.isMeaningful(it.translation)
                } == true,
                hasPronunciation = lines?.any { !it.romanization.isNullOrBlank() } == true,
                searchFailed = lines == null,
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
        val encodedSourceStates = OnlineSourceContentStatesCodec.encode(requestedSourceStates())
        val metadataEntries = song.metadata?.entries
            ?.filterNot {
                it.key == LyricMetadataKeys.ONLINE_TRANSLATION_MATCH_STATS ||
                    it.key == LyricMetadataKeys.ONLINE_PRONUNCIATION_MATCH_STATS ||
                    it.key == LyricMetadataKeys.ONLINE_SOURCE_CONTENT_STATES
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
        encodedSourceStates?.let {
            metadataEntries += LyricMetadataKeys.ONLINE_SOURCE_CONTENT_STATES to it
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
