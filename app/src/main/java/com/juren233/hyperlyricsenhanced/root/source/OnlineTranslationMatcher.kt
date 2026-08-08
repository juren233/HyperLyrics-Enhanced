package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationContentPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.online.model.Source
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object OnlineTranslationMatcher {
    private const val MIN_TEXT_SIMILARITY = 0.65
    private const val MATCH_TIME_WINDOW_MS = 15_000L
    private const val MAX_TIME_PENALTY = 0.20
    private const val MAX_GROUP_SPAN = 6
    private const val GROUP_SPAN_PENALTY = 0.015

    data class Result(
        val song: Song,
        val matchedCount: Int,
        val averageMatchScore: Double,
        val lineMatchScores: Map<Int, Double> = emptyMap(),
        val lineCandidateIndices: Map<Int, List<Int>> = emptyMap()
    )

    /**
     * Composes independently selected translation and pronunciation sources.
     *
     * A user-selected source stays authoritative even when it contributes no
     * content. Missing fields are filled from the other source and finally from
     * the already-published song, while the metadata continues to identify the
     * source selected by the user.
     */
    fun composeSelectedSources(
        baseSong: Song,
        candidates: Map<Source, Result>,
        defaultTranslationSource: Source?,
        defaultPronunciationSource: Source?,
        forcedTranslationSource: Source?,
        forcedPronunciationSource: Source?,
        currentPublishedSong: Song?,
    ): Result? {
        val publishedResult = currentPublishedSong?.let(::contentResult)
        val publishedTranslationSource = currentPublishedSong
            ?.onlineContentSource(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE)
        val publishedPronunciationSource = currentPublishedSong
            ?.onlineContentSource(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE)
        val translationSource = forcedTranslationSource
            ?: publishedTranslationSource
            ?: defaultTranslationSource
        val pronunciationSource = forcedPronunciationSource
            ?: publishedPronunciationSource
            ?: defaultPronunciationSource
        if (translationSource == null && pronunciationSource == null) return null

        val translation = composeSourceContent(
            baseSong = baseSong,
            selectedSource = translationSource,
            publishedSource = publishedTranslationSource,
            candidates = candidates,
            publishedResult = publishedResult,
        )
        val pronunciation = composeSourceContent(
            baseSong = baseSong,
            selectedSource = pronunciationSource,
            publishedSource = publishedPronunciationSource,
            candidates = candidates,
            publishedResult = publishedResult,
        )
        val composed = composeContent(
            baseSong = baseSong,
            translation = translation,
            pronunciation = pronunciation,
        ) ?: Result(
            song = baseSong,
            matchedCount = 0,
            averageMatchScore = 0.0,
        )
        return composed.copy(
            song = composed.song.withOnlineContentSources(
                translationSource = translationSource,
                pronunciationSource = pronunciationSource,
            )
        )
    }

    fun apply(song: Song, onlineLines: List<LrcLine>): Result {
        val candidates = onlineLines
            .asSequence()
            .sortedBy(LrcLine::startTimeMs)
            .mapIndexed { index, line ->
                Candidate(
                    originalIndex = index,
                    line = line.copy(
                        translation = OnlineTranslationContentPolicy.sanitize(line.translation)
                    ),
                    normalizedVariants = normalizedVariants(line.content),
                )
            }
            .filter { it.normalizedVariants.isNotEmpty() }
            .toList()
        if (candidates.isEmpty()) return Result(song, 0, 0.0)

        val nativeLyrics = song.lyrics ?: return Result(song, 0, 0.0)
        val matchedLyrics = nativeLyrics.toMutableList()
        var nativeIndex = 0
        var candidateStart = 0
        var matchedCount = 0
        var matchScoreSum = 0.0
        val lineMatchScores = mutableMapOf<Int, Double>()
        val lineCandidateIndices = mutableMapOf<Int, List<Int>>()

        while (nativeIndex < nativeLyrics.size) {
            if (
                OnlineTranslationContentPolicy.isMeaningful(
                    nativeLyrics[nativeIndex].translation
                ) &&
                !nativeLyrics[nativeIndex].roma.isNullOrBlank()
            ) {
                nativeIndex++
                continue
            }
            val plan = findBestPlan(nativeLyrics, nativeIndex, candidates, candidateStart)
            if (plan == null) {
                nativeIndex++
                continue
            }

            val nativeGroup = nativeLyrics.subList(nativeIndex, nativeIndex + plan.nativeSpan)
            val candidateGroup = candidates.subList(
                plan.candidateIndex,
                plan.candidateIndex + plan.candidateSpan
            )
            val translations = distributeTranslations(nativeGroup, candidateGroup)
            val romanizations = distributeRomanizations(nativeGroup, candidateGroup)
            nativeGroup.indices.forEach { offset ->
                val targetIndex = nativeIndex + offset
                val targetLine = matchedLyrics[targetIndex]
                val translation = translations.getOrNull(offset)
                    ?.takeIf {
                        !OnlineTranslationContentPolicy.isMeaningful(targetLine.translation) &&
                            OnlineTranslationContentPolicy.isMeaningful(it.main)
                    }
                val romanization = romanizations.getOrNull(offset)
                    ?.takeIf { targetLine.roma.isNullOrBlank() && it.isNotBlank() }
                if (translation == null && romanization == null) return@forEach

                val translatedLine = translation
                    ?.let { applyTranslation(targetLine, it) }
                    ?: targetLine
                matchedLyrics[targetIndex] = translatedLine.copy(
                    roma = romanization ?: translatedLine.roma
                )
                matchedCount++
                matchScoreSum += plan.score
                lineMatchScores[targetIndex] = plan.score
                lineCandidateIndices[targetIndex] = candidateGroup.map(Candidate::originalIndex)
            }
            nativeIndex += plan.nativeSpan
            candidateStart = plan.candidateIndex + plan.candidateSpan
        }

        return Result(
            song = song.copy(lyrics = matchedLyrics),
            matchedCount = matchedCount,
            averageMatchScore = if (matchedCount == 0) 0.0 else matchScoreSum / matchedCount,
            lineMatchScores = lineMatchScores,
            lineCandidateIndices = lineCandidateIndices
        )
    }

    /**
     * Keeps the selected source authoritative and only fills lines it could not match.
     * This lets QQ and NetEase complement each other without replacing a preferred
     * source's already matched translations.
     */
    fun fillMissing(primary: Result, supplemental: Result?): Result {
        if (supplemental == null) return primary
        val primaryLines = primary.song.lyrics ?: return primary
        val supplementalLines = supplemental.song.lyrics ?: return primary
        if (primaryLines.size != supplementalLines.size) return primary

        var addedCount = 0
        val scores = primary.lineMatchScores.toMutableMap()
        val candidateIndices = primary.lineCandidateIndices.toMutableMap()
        val mergedLines = primaryLines.mapIndexed { index, primaryLine ->
            val supplementalLine = supplementalLines[index]
            val supplementalTranslation = OnlineTranslationContentPolicy.sanitize(
                supplementalLine.translation
            )
            val needsMainTranslation =
                !OnlineTranslationContentPolicy.isMeaningful(primaryLine.translation) &&
                    supplementalTranslation != null
            val needsBackgroundTranslation =
                !OnlineTranslationContentPolicy.isMeaningful(
                    primaryLine.metadata
                        ?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
                ) && OnlineTranslationContentPolicy.isMeaningful(
                    supplementalLine.metadata
                        ?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
                )
            val needsRomanization = primaryLine.roma.isNullOrBlank() &&
                !supplementalLine.roma.isNullOrBlank()
            if (!needsMainTranslation && !needsBackgroundTranslation && !needsRomanization) {
                primaryLine
            } else {
                if (needsMainTranslation || needsBackgroundTranslation || needsRomanization) {
                    addedCount++
                    supplemental.lineMatchScores[index]?.let { scores[index] = it }
                    supplemental.lineCandidateIndices[index]?.let { candidateIndices[index] = it }
                }
                primaryLine.copy(
                    translation = if (needsMainTranslation) {
                        supplementalTranslation
                    } else {
                        primaryLine.translation
                    },
                    translationWords = if (needsMainTranslation) {
                        supplementalLine.translationWords
                    } else {
                        primaryLine.translationWords
                    },
                    roma = if (needsRomanization) {
                        supplementalLine.roma
                    } else {
                        primaryLine.roma
                    },
                    metadata = mergeBackgroundTranslationMetadata(primaryLine, supplementalLine)
                )
            }
        }
        val matchedCount = primary.matchedCount + addedCount
        return Result(
            song = primary.song.copy(lyrics = mergedLines),
            matchedCount = matchedCount,
            averageMatchScore = if (scores.isEmpty()) 0.0 else scores.values.average(),
            lineMatchScores = scores,
            lineCandidateIndices = candidateIndices
        )
    }

    fun composeContent(
        baseSong: Song,
        translation: Result?,
        pronunciation: Result?,
    ): Result? {
        val baseLines = baseSong.lyrics ?: return null
        val translationLines = translation?.song?.lyrics
        val pronunciationLines = pronunciation?.song?.lyrics
        if (translationLines != null && translationLines.size != baseLines.size) return null
        if (pronunciationLines != null && pronunciationLines.size != baseLines.size) return null

        var matchedCount = 0
        val scores = mutableMapOf<Int, Double>()
        val candidateIndices = mutableMapOf<Int, List<Int>>()
        val mergedLines = baseLines.mapIndexed { index, baseLine ->
            val translatedLine = translationLines?.get(index)
            val pronouncedLine = pronunciationLines?.get(index)
            val translatedContent = OnlineTranslationContentPolicy.sanitize(
                translatedLine?.translation
            )
            val useTranslation =
                !OnlineTranslationContentPolicy.isMeaningful(baseLine.translation) &&
                    translatedContent != null
            val useBackgroundTranslation =
                !OnlineTranslationContentPolicy.isMeaningful(
                    baseLine.metadata
                        ?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
                ) && OnlineTranslationContentPolicy.isMeaningful(
                    translatedLine?.metadata
                        ?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
                )
            val usePronunciation = baseLine.roma.isNullOrBlank() &&
                !pronouncedLine?.roma.isNullOrBlank()
            if (useTranslation || useBackgroundTranslation || usePronunciation) {
                matchedCount++
                val scoreSource = when {
                    useTranslation || useBackgroundTranslation -> translation
                    else -> pronunciation
                }
                scoreSource?.lineMatchScores?.get(index)?.let { scores[index] = it }
                scoreSource?.lineCandidateIndices?.get(index)?.let {
                    candidateIndices[index] = it
                }
            }
            baseLine.copy(
                translation = if (useTranslation) translatedContent else baseLine.translation,
                translationWords = if (useTranslation) {
                    translatedLine?.translationWords
                } else {
                    baseLine.translationWords
                },
                roma = if (usePronunciation) pronouncedLine.roma else baseLine.roma,
                metadata = if (useBackgroundTranslation && translatedLine != null) {
                    mergeBackgroundTranslationMetadata(baseLine, translatedLine)
                } else {
                    baseLine.metadata
                },
            )
        }
        if (matchedCount == 0) return null
        return Result(
            song = baseSong.copy(lyrics = mergedLines),
            matchedCount = matchedCount,
            averageMatchScore = if (scores.isEmpty()) 0.0 else scores.values.average(),
            lineMatchScores = scores,
            lineCandidateIndices = candidateIndices,
        )
    }

    fun contributesTranslation(baseSong: Song, result: Result?): Boolean =
        baseSong.lyrics.orEmpty().indices.any { index ->
            val baseLine = baseSong.lyrics?.getOrNull(index) ?: return@any false
            val resultLine = result?.song?.lyrics?.getOrNull(index) ?: return@any false
            (!OnlineTranslationContentPolicy.isMeaningful(baseLine.translation) &&
                OnlineTranslationContentPolicy.isMeaningful(resultLine.translation)) ||
                (!OnlineTranslationContentPolicy.isMeaningful(
                    baseLine.metadata
                        ?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
                ) && OnlineTranslationContentPolicy.isMeaningful(
                    resultLine.metadata
                        ?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
                ))
        }

    fun contributesPronunciation(baseSong: Song, result: Result?): Boolean =
        baseSong.lyrics.orEmpty().indices.any { index ->
            val baseLine = baseSong.lyrics?.getOrNull(index) ?: return@any false
            val resultLine = result?.song?.lyrics?.getOrNull(index) ?: return@any false
            baseLine.roma.isNullOrBlank() && !resultLine.roma.isNullOrBlank()
        }

    private fun composeSourceContent(
        baseSong: Song,
        selectedSource: Source?,
        publishedSource: Source?,
        candidates: Map<Source, Result>,
        publishedResult: Result?,
    ): Result? {
        selectedSource ?: return null
        val selectedCandidate = candidates[selectedSource]
        val publishedIsSelected = publishedSource == selectedSource
        var result = selectedCandidate
            ?: publishedResult?.takeIf { publishedIsSelected }
            ?: contentResult(baseSong)
        candidates.entries
            .firstOrNull { it.key != selectedSource }
            ?.value
            ?.let { result = fillMissing(result, it) }
        if (publishedResult != null && (selectedCandidate != null || !publishedIsSelected)) {
            result = fillMissing(result, publishedResult)
        }
        return result
    }

    private fun contentResult(song: Song): Result {
        val contentLines = song.lyrics.orEmpty().count { line ->
            OnlineTranslationContentPolicy.isMeaningful(line.translation) ||
                !line.roma.isNullOrBlank() ||
                OnlineTranslationContentPolicy.isMeaningful(
                    line.metadata
                        ?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
                )
        }
        return Result(
            song = song,
            matchedCount = contentLines,
            averageMatchScore = 0.0,
        )
    }

    private fun Song.onlineContentSource(key: String): Source? =
        metadata
            ?.getString(key)
            ?.let { runCatching { Source.valueOf(it) }.getOrNull() }

    private fun Song.withOnlineContentSources(
        translationSource: Source?,
        pronunciationSource: Source?,
    ): Song {
        val entries = metadata?.entries
            ?.filterNot {
                it.key == LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE ||
                    it.key == LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE
            }
            ?.map { it.key to it.value }
            .orEmpty()
            .toMutableList()
        translationSource?.let {
            entries += LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE to it.name
        }
        pronunciationSource?.let {
            entries += LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE to it.name
        }
        return copy(
            metadata = entries.takeIf { it.isNotEmpty() }
                ?.let { lyricMetadataOf(*it.toTypedArray()) }
        )
    }

    private fun mergeBackgroundTranslationMetadata(
        primary: com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine,
        supplemental: com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
    ) = primary.metadata?.entries
        ?.map { it.key to it.value }
        .orEmpty()
        .toMutableList()
        .apply {
            val hasBackgroundTranslation = any {
                it.first == LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION &&
                    OnlineTranslationContentPolicy.isMeaningful(it.second)
            }
            removeAll {
                it.first == LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION &&
                    !OnlineTranslationContentPolicy.isMeaningful(it.second)
            }
            if (!hasBackgroundTranslation) {
                supplemental.metadata
                    ?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
                    ?.let(OnlineTranslationContentPolicy::sanitize)
                    ?.let {
                        add(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION to it)
                    }
            }
        }
        .takeIf { it.isNotEmpty() }
        ?.let { lyricMetadataOf(*it.toTypedArray()) }

    private fun findBestPlan(
        nativeLyrics: List<com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine>,
        nativeIndex: Int,
        candidates: List<Candidate>,
        candidateStart: Int
    ): MatchPlan? {
        var bestPlan: MatchPlan? = null
        val maxNativeSpan = min(MAX_GROUP_SPAN, nativeLyrics.size - nativeIndex)
        for (candidateIndex in candidateStart until candidates.size) {
            val timeDistance = abs(
                nativeLyrics[nativeIndex].begin - candidates[candidateIndex].line.startTimeMs
            )
            if (timeDistance > MATCH_TIME_WINDOW_MS) {
                if (candidates[candidateIndex].line.startTimeMs > nativeLyrics[nativeIndex].begin) break
                continue
            }
            val maxCandidateSpan = min(MAX_GROUP_SPAN, candidates.size - candidateIndex)
            for (nativeSpan in 1..maxNativeSpan) {
                val nativeVariants = combinedVariants(
                    nativeLyrics.subList(nativeIndex, nativeIndex + nativeSpan)
                        .map { normalizedVariants(it.text.orEmpty()) }
                )
                if (nativeVariants.isEmpty()) continue
                for (candidateSpan in 1..maxCandidateSpan) {
                    if (nativeSpan > 1 && candidateSpan > 1) continue
                    if (
                        nativeSpan == 1 &&
                        candidateSpan == 1 &&
                        shouldPreferExpandedNativePronunciationGroup(
                            nativeLyrics = nativeLyrics,
                            nativeIndex = nativeIndex,
                            candidate = candidates[candidateIndex],
                        )
                    ) {
                        continue
                    }
                    val candidateVariants = combinedVariants(
                        candidates.subList(candidateIndex, candidateIndex + candidateSpan)
                            .map(Candidate::normalizedVariants)
                    )
                    if (candidateVariants.isEmpty()) continue
                    val textScore = nativeVariants.maxOf { nativeText ->
                        candidateVariants.maxOf { candidateText ->
                            similarity(nativeText, candidateText)
                        }
                    }
                    if (textScore < MIN_TEXT_SIMILARITY) continue
                    val timePenalty = min(
                        MAX_TIME_PENALTY,
                        timeDistance.toDouble() / MATCH_TIME_WINDOW_MS * MAX_TIME_PENALTY
                    )
                    val spanPenalty = (nativeSpan + candidateSpan - 2) * GROUP_SPAN_PENALTY
                    val score = textScore - timePenalty - spanPenalty
                    val nativeGroup = nativeLyrics.subList(nativeIndex, nativeIndex + nativeSpan)
                    val candidateGroup = candidates.subList(
                        candidateIndex,
                        candidateIndex + candidateSpan
                    )
                    if (nativeSpan > 1 && candidateSpan == 1 &&
                        !candidateCoversEveryNativeLine(candidateGroup.single(), nativeGroup) &&
                        !candidateMatchesCombinedNativeGroup(candidateGroup.single(), nativeGroup)
                    ) {
                        continue
                    }
                    val hasTranslation = distributeTranslations(nativeGroup, candidateGroup).any {
                        OnlineTranslationContentPolicy.isMeaningful(it?.main)
                    }
                    val hasRomanization = distributeRomanizations(
                        nativeGroup,
                        candidateGroup
                    ).any { !it.isNullOrBlank() }
                    if (!hasTranslation && !hasRomanization) {
                        continue
                    }
                    if (bestPlan == null || score > bestPlan.score) {
                        bestPlan = MatchPlan(
                            candidateIndex = candidateIndex,
                            nativeSpan = nativeSpan,
                            candidateSpan = candidateSpan,
                            score = score
                        )
                    }
                }
            }
        }
        return bestPlan
    }

    private fun combinedVariants(groups: List<List<String>>): List<String> {
        if (groups.isEmpty() || groups.any(List<String>::isEmpty)) return emptyList()
        return groups.fold(listOf("")) { prefixes, variants ->
            prefixes.flatMap { prefix -> variants.map(prefix::plus) }
        }.distinct()
    }

    private fun distributeTranslations(
        nativeGroup: List<com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine>,
        candidateGroup: List<Candidate>
    ): List<TranslationParts?> {
        if (nativeGroup.size == 1) {
            val nativeLine = nativeGroup.single()
            val parts = candidateGroup.mapNotNull { candidate ->
                OnlineTranslationContentPolicy.sanitize(candidate.line.translation)
                    ?.let { translation ->
                    splitBackingTranslation(
                        nativeText = nativeLine.text,
                        nativeSecondary = nativeLine.secondary,
                        candidate = candidate,
                        translation = translation
                    )
                }
            }
            if (parts.isEmpty()) return listOf(null)
            return listOf(
                TranslationParts(
                    main = parts.joinToString(" ") { it.main }.trim(),
                    background = parts.mapNotNull(TranslationParts::background)
                        .joinToString(" ")
                        .trim()
                        .takeIf(String::isNotEmpty)
                )
            )
        }

        if (candidateGroup.size == 1) {
            return splitCandidateTranslation(nativeGroup, candidateGroup.single())
        }

        if (nativeGroup.size == candidateGroup.size) {
            return nativeGroup.zip(candidateGroup).map { (nativeLine, candidate) ->
                OnlineTranslationContentPolicy.sanitize(candidate.line.translation)
                    ?.let { translation ->
                    splitBackingTranslation(
                        nativeText = nativeLine.text,
                        nativeSecondary = nativeLine.secondary,
                        candidate = candidate,
                        translation = translation
                    )
                }
            }
        }

        val combinedTranslation = candidateGroup.mapNotNull {
            OnlineTranslationContentPolicy.sanitize(it.line.translation)
        }
            .joinToString(" ")
        return splitTextByNativeWeights(combinedTranslation, nativeGroup)
            .map { it?.let { text -> TranslationParts(text, null) } }
    }

    private fun distributeRomanizations(
        nativeGroup: List<com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine>,
        candidateGroup: List<Candidate>
    ): List<String?> {
        if (nativeGroup.size == 1) {
            val combined = candidateGroup.mapNotNull { candidate ->
                candidate.line.romanization
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let(::mainTextWithoutBracketedSegments)
                    ?.takeIf(String::isNotEmpty)
            }.joinToString(" ")
            return listOf(combined.takeIf(String::isNotEmpty))
        }

        if (candidateGroup.size == 1) {
            return splitCandidateRomanization(nativeGroup, candidateGroup.single())
        }

        if (nativeGroup.size == candidateGroup.size) {
            return candidateGroup.map { candidate ->
                candidate.line.romanization
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let(::mainTextWithoutBracketedSegments)
                    ?.takeIf(String::isNotEmpty)
            }
        }

        return List(nativeGroup.size) { null }
    }

    private fun splitCandidateRomanization(
        nativeGroup: List<com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine>,
        candidate: Candidate
    ): List<String?> {
        val romanization = candidate.line.romanization
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return List(nativeGroup.size) { null }
        val sourceParts = extractBracketedSegments(candidate.line.content)
        val pronunciationParts = extractBracketedSegments(romanization)
        val sourceComponents = listOf(sourceParts.first) + sourceParts.second
        val pronunciationComponents =
            listOf(pronunciationParts.first) + pronunciationParts.second
        if (
            sourceComponents.size == nativeGroup.size &&
            pronunciationComponents.size == nativeGroup.size &&
            sourceComponents.all(String::isNotBlank) &&
            pronunciationComponents.all(String::isNotBlank) &&
            nativeComponentsMatch(nativeGroup, sourceComponents)
        ) {
            return pronunciationComponents.map { it.trim().takeIf(String::isNotEmpty) }
        }

        val mainRomanization = mainTextWithoutBracketedSegments(romanization)
            .trim()
        val tokens = mainRomanization
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
        val sourceUnitCount = cjkPronunciationUnitCount(
            mainTextWithoutBracketedSegments(candidate.line.content)
        ) ?: return List(nativeGroup.size) { null }
        val nativeUnitCounts = nativeGroup.map { nativeLine ->
            cjkPronunciationUnitCount(
                mainTextWithoutBracketedSegments(nativeLine.text.orEmpty())
            ) ?: return List(nativeGroup.size) { null }
        }
        if (
            tokens.size != sourceUnitCount ||
            nativeUnitCounts.sum() != sourceUnitCount
        ) {
            return List(nativeGroup.size) { null }
        }
        var tokenIndex = 0
        return nativeUnitCounts.map { unitCount ->
            tokens.subList(tokenIndex, tokenIndex + unitCount)
                .joinToString(" ")
                .also { tokenIndex += unitCount }
                .takeIf(String::isNotEmpty)
        }
    }

    private fun shouldPreferExpandedNativePronunciationGroup(
        nativeLyrics: List<com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine>,
        nativeIndex: Int,
        candidate: Candidate,
    ): Boolean {
        if (candidate.line.romanization.isNullOrBlank()) return false
        val maxNativeSpan = min(MAX_GROUP_SPAN, nativeLyrics.size - nativeIndex)
        if (maxNativeSpan < 2) return false
        for (nativeSpan in 2..maxNativeSpan) {
            val nativeGroup = nativeLyrics.subList(nativeIndex, nativeIndex + nativeSpan)
            if (candidateMatchesCombinedNativeGroup(candidate, nativeGroup)) return true
        }
        return false
    }

    private fun candidateMatchesCombinedNativeGroup(
        candidate: Candidate,
        nativeGroup: List<com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine>,
    ): Boolean {
        val combinedNativeVariants = combinedVariants(
            nativeGroup.map { normalizedVariants(it.text.orEmpty()) }
        )
        return combinedNativeVariants.any { nativeText ->
            candidate.normalizedVariants.any { candidateText ->
                candidateText == nativeText || similarity(nativeText, candidateText) >= 0.98
            }
        }
    }

    private fun cjkPronunciationUnitCount(text: String): Int? {
        var count = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isLetterOrDigit(codePoint)) {
                when (Character.UnicodeScript.of(codePoint)) {
                    Character.UnicodeScript.HAN,
                    Character.UnicodeScript.HIRAGANA,
                    Character.UnicodeScript.KATAKANA,
                    Character.UnicodeScript.HANGUL -> count++
                    else -> return null
                }
            }
            index += Character.charCount(codePoint)
        }
        return count.takeIf { it > 0 }
    }

    private fun splitCandidateTranslation(
        nativeGroup: List<com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine>,
        candidate: Candidate
    ): List<TranslationParts?> {
        val translation = OnlineTranslationContentPolicy.sanitize(candidate.line.translation)
            ?: return List(nativeGroup.size) { null }
        val sourceParts = extractBracketedSegments(candidate.line.content)
        val translationParts = extractBracketedSegments(translation)
        val sourceComponents = listOf(sourceParts.first) + sourceParts.second
        val translatedComponents = listOf(translationParts.first) + translationParts.second
        if (sourceComponents.size == nativeGroup.size &&
            translatedComponents.size == nativeGroup.size &&
            sourceComponents.all(String::isNotBlank) &&
            translatedComponents.all(OnlineTranslationContentPolicy::isMeaningful) &&
            nativeComponentsMatch(nativeGroup, sourceComponents)
        ) {
            return translatedComponents.map { TranslationParts(it.trim(), null) }
        }
        val split = splitTextByNativeWeights(translation, nativeGroup)
        if (split.any { it != null }) {
            return split.map { it?.let { text -> TranslationParts(text, null) } }
        }

        // Apple can split one provider line into several display lines. When the translated
        // sentence has no trustworthy delimiter, repeating it is safer than either cutting at
        // arbitrary characters or leaving most of the Apple group untranslated.
        return List(nativeGroup.size) { TranslationParts(translation, null) }
    }

    private fun nativeComponentsMatch(
        nativeGroup: List<com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine>,
        sourceComponents: List<String>
    ): Boolean = nativeGroup.zip(sourceComponents).all { (nativeLine, sourceText) ->
        normalizedVariants(nativeLine.text.orEmpty()).maxOfOrNull { nativeText ->
            normalizedVariants(sourceText).maxOfOrNull { sourceVariant ->
                similarity(nativeText, sourceVariant)
            } ?: 0.0
        }?.let { it >= MIN_TEXT_SIMILARITY } == true
    }

    private fun splitTextByNativeWeights(
        text: String,
        nativeGroup: List<com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine>
    ): List<String?> {
        if (text.isBlank()) return List(nativeGroup.size) { null }
        val count = nativeGroup.size
        val whitespaceParts = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (whitespaceParts.size == count && whitespaceParts.all(::isCompleteCjkPhrase)) {
            return whitespaceParts
        }

        val punctuationParts = text.trim()
            .split(Regex("(?<=[，,。.!！?？；;])\\s*"))
            .filter(String::isNotBlank)
        if (punctuationParts.size == count) return punctuationParts

        // Signal that there is no trustworthy split; the caller keeps the full sentence intact.
        return List(count) { null }
    }

    private fun candidateCoversEveryNativeLine(
        candidate: Candidate,
        nativeGroup: List<com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine>
    ): Boolean = nativeGroup.all { nativeLine ->
        val nativeVariants = normalizedVariants(nativeLine.text.orEmpty())
        nativeVariants.any { nativeText ->
            nativeText.length >= 4 && candidate.normalizedVariants.any { candidateText ->
                candidateText.contains(nativeText) || similarity(nativeText, candidateText) >= 0.85
            }
        }
    }

    private fun isCompleteCjkPhrase(value: String): Boolean {
        var cjkCount = 0
        for (char in value) {
            if (char in '\u4e00'..'\u9fff' || char in '\u3040'..'\u30ff' || char in '\uac00'..'\ud7af') {
                cjkCount++
            } else if (char.isLetterOrDigit()) {
                return false
            }
        }
        return cjkCount >= 2
    }

    private fun applyTranslation(
        nativeLine: com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine,
        parts: TranslationParts
    ): com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine {
        val metadataEntries = nativeLine.metadata?.entries
            ?.map { it.key to it.value }
            .orEmpty()
            .toMutableList()
        parts.background?.let {
            metadataEntries.removeAll {
                entry -> entry.first == LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION
            }
            metadataEntries += LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION to it
        }
        return nativeLine.copy(
            translation = parts.main,
            translationWords = null,
            metadata = metadataEntries
                .takeIf { it.isNotEmpty() }
                ?.let { lyricMetadataOf(*it.toTypedArray()) }
        )
    }

    private fun normalize(text: String): String = buildString(text.length) {
        text.lowercase().forEach { char ->
            if (char.isLetterOrDigit()) append(char)
        }
    }

    private fun normalizedVariants(text: String): List<String> {
        val full = normalize(text)
        if (full.isEmpty()) return emptyList()
        val withoutBackingSegments = normalize(removeBracketedSegments(text))
        return listOf(full, withoutBackingSegments)
            .filter(String::isNotEmpty)
            .distinct()
    }

    private fun splitBackingTranslation(
        nativeText: String?,
        nativeSecondary: String?,
        candidate: Candidate,
        translation: String
    ): TranslationParts {
        val sourceParts = extractBracketedSegments(candidate.line.content)
        if (sourceParts.second.isEmpty()) {
            return TranslationParts(translation, null)
        }
        val translationParts = extractBracketedSegments(translation)
        if (nativeSecondary.isNullOrBlank()) {
            if (extractBracketedSegments(nativeText.orEmpty()).second.isNotEmpty()) {
                return TranslationParts(translation, null)
            }
            val main = translationParts.first
            return if (main.isNotBlank() && translationParts.second.isNotEmpty()) {
                TranslationParts(main, null)
            } else {
                TranslationParts(translation, null)
            }
        }

        val secondaryVariants = normalizedVariants(nativeSecondary)
        val matchingBackgroundIndex = sourceParts.second.indices.maxByOrNull { index ->
            val sourceVariants = normalizedVariants(sourceParts.second[index])
            secondaryVariants.maxOfOrNull { secondaryText ->
                sourceVariants.maxOfOrNull { sourceText -> similarity(secondaryText, sourceText) }
                    ?: 0.0
            } ?: 0.0
        }
        val backgroundMatches = matchingBackgroundIndex?.let { index ->
            val sourceVariants = normalizedVariants(sourceParts.second[index])
            secondaryVariants.maxOfOrNull { secondaryText ->
                sourceVariants.maxOfOrNull { sourceText -> similarity(secondaryText, sourceText) }
                    ?: 0.0
            } ?: 0.0
        }?.let { it >= MIN_TEXT_SIMILARITY } == true
        if (!backgroundMatches) {
            return TranslationParts(translation, null)
        }

        val main = translationParts.first
        val background = translationParts.second.getOrNull(matchingBackgroundIndex)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        return if (main.isNotBlank() && background != null) {
            TranslationParts(main, background)
        } else {
            TranslationParts(translation, null)
        }
    }

    private fun removeBracketedSegments(text: String): String = buildString(text.length) {
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

    private fun mainTextWithoutBracketedSegments(text: String): String =
        extractBracketedSegments(text).first.ifBlank {
            removeBracketedSegments(text).replace(Regex("\\s+"), " ").trim()
        }

    private fun extractBracketedSegments(text: String): Pair<String, List<String>> {
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

    private fun similarity(first: String, second: String): Double {
        if (first == second) return 1.0
        if (first.isEmpty() || second.isEmpty()) return 0.0
        val longerLength = max(first.length, second.length)
        if ((first.contains(second) || second.contains(first)) && min(first.length, second.length) >= 4) {
            return min(first.length, second.length).toDouble() / longerLength
        }
        return 1.0 - levenshteinDistance(first, second).toDouble() / longerLength
    }

    private fun levenshteinDistance(first: String, second: String): Int {
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

    private data class Candidate(
        val originalIndex: Int,
        val line: LrcLine,
        val normalizedVariants: List<String>
    )

    private data class TranslationParts(val main: String, val background: String?)

    private data class MatchPlan(
        val candidateIndex: Int,
        val nativeSpan: Int,
        val candidateSpan: Int,
        val score: Double
    )
}
