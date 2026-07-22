package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object OnlineTranslationMatcher {
    private const val MIN_TEXT_SIMILARITY = 0.65
    private const val MATCH_TIME_WINDOW_MS = 15_000L
    private const val MAX_TIME_PENALTY = 0.20
    private const val MAX_GROUP_SPAN = 3
    private const val GROUP_SPAN_PENALTY = 0.015

    data class Result(
        val song: Song,
        val matchedCount: Int,
        val averageMatchScore: Double,
        val lineMatchScores: Map<Int, Double> = emptyMap(),
        val lineCandidateIndices: Map<Int, List<Int>> = emptyMap()
    )

    fun apply(song: Song, onlineLines: List<LrcLine>): Result {
        val candidates = onlineLines
            .asSequence()
            .sortedBy(LrcLine::startTimeMs)
            .mapIndexed { index, line -> Candidate(index, line, normalizedVariants(line.content)) }
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
            if (!nativeLyrics[nativeIndex].translation.isNullOrBlank()) {
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
            translations.forEachIndexed { offset, parts ->
                if (parts == null || parts.main.isBlank()) return@forEachIndexed
                val targetIndex = nativeIndex + offset
                matchedLyrics[targetIndex] = applyTranslation(matchedLyrics[targetIndex], parts)
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
            val needsMainTranslation = primaryLine.translation.isNullOrBlank() &&
                !supplementalLine.translation.isNullOrBlank()
            val needsBackgroundTranslation = primaryLine.metadata
                ?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
                .isNullOrBlank() && !supplementalLine.metadata
                ?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
                .isNullOrBlank()
            if (!needsMainTranslation && !needsBackgroundTranslation) {
                primaryLine
            } else {
                if (needsMainTranslation) {
                    addedCount++
                    supplemental.lineMatchScores[index]?.let { scores[index] = it }
                    supplemental.lineCandidateIndices[index]?.let { candidateIndices[index] = it }
                }
                primaryLine.copy(
                    translation = if (needsMainTranslation) {
                        supplementalLine.translation
                    } else {
                        primaryLine.translation
                    },
                    translationWords = if (needsMainTranslation) {
                        supplementalLine.translationWords
                    } else {
                        primaryLine.translationWords
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

    private fun mergeBackgroundTranslationMetadata(
        primary: com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine,
        supplemental: com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
    ) = primary.metadata?.entries
        ?.map { it.key to it.value }
        .orEmpty()
        .toMutableList()
        .apply {
            val hasBackgroundTranslation = any {
                it.first == LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION
            }
            if (!hasBackgroundTranslation) {
                supplemental.metadata
                    ?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
                    ?.takeIf(String::isNotBlank)
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
                        !candidateCoversEveryNativeLine(candidateGroup.single(), nativeGroup)
                    ) {
                        continue
                    }
                    if (distributeTranslations(nativeGroup, candidateGroup).none {
                            it?.main?.isNotBlank() == true
                        }
                    ) {
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
                candidate.line.translation?.trim()?.takeIf(String::isNotEmpty)?.let { translation ->
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
                candidate.line.translation?.trim()?.takeIf(String::isNotEmpty)?.let { translation ->
                    splitBackingTranslation(
                        nativeText = nativeLine.text,
                        nativeSecondary = nativeLine.secondary,
                        candidate = candidate,
                        translation = translation
                    )
                }
            }
        }

        val combinedTranslation = candidateGroup.mapNotNull { it.line.translation?.trim() }
            .filter(String::isNotEmpty)
            .joinToString(" ")
        return splitTextByNativeWeights(combinedTranslation, nativeGroup)
            .map { it?.let { text -> TranslationParts(text, null) } }
    }

    private fun splitCandidateTranslation(
        nativeGroup: List<com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine>,
        candidate: Candidate
    ): List<TranslationParts?> {
        val translation = candidate.line.translation?.trim()?.takeIf(String::isNotEmpty)
            ?: return List(nativeGroup.size) { null }
        val sourceParts = extractBracketedSegments(candidate.line.content)
        val translationParts = extractBracketedSegments(translation)
        val sourceComponents = listOf(sourceParts.first) + sourceParts.second
        val translatedComponents = listOf(translationParts.first) + translationParts.second
        if (sourceComponents.size == nativeGroup.size &&
            translatedComponents.size == nativeGroup.size &&
            sourceComponents.all(String::isNotBlank) &&
            translatedComponents.all(String::isNotBlank)
        ) {
            val componentMatches = nativeGroup.zip(sourceComponents).all { (nativeLine, sourceText) ->
                normalizedVariants(nativeLine.text.orEmpty()).maxOfOrNull { nativeText ->
                    normalizedVariants(sourceText).maxOfOrNull { sourceVariant ->
                        similarity(nativeText, sourceVariant)
                    } ?: 0.0
                }?.let { it >= MIN_TEXT_SIMILARITY } == true
            }
            if (componentMatches) {
                return translatedComponents.map { TranslationParts(it.trim(), null) }
            }
        }
        return splitTextByNativeWeights(translation, nativeGroup)
            .map { it?.let { text -> TranslationParts(text, null) } }
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

        // A translated sentence cannot be divided safely from source-language
        // character ratios. Leave it unmatched so a later source or AI can fill it.
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
