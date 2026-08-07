/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
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
    val defaultTranslationSource: Source? = null,
    val defaultPronunciationSource: Source? = null,
    val forcedTranslationSource: Source? = null,
    val forcedPronunciationSource: Source? = null,
) {
    fun compose(
        latestNativeSong: Song,
        currentPublishedSong: Song?,
    ): OnlineTranslationMatcher.Result? {
        val rebasedCandidates = onlineLinesBySource.mapValues { (_, onlineLines) ->
            OnlineTranslationMatcher.apply(latestNativeSong, onlineLines)
        }
        return OnlineTranslationMatcher.composeSelectedSources(
            baseSong = latestNativeSong,
            candidates = rebasedCandidates,
            defaultTranslationSource = defaultTranslationSource,
            defaultPronunciationSource = defaultPronunciationSource,
            forcedTranslationSource = forcedTranslationSource,
            forcedPronunciationSource = forcedPronunciationSource,
            currentPublishedSong = currentPublishedSong,
        )
    }
}
