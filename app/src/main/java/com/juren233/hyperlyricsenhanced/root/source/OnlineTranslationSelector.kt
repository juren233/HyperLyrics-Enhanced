package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.online.model.Source

internal object OnlineTranslationSelector {
    private const val PREFERRED_COVERAGE_THRESHOLD = 0.96
    private const val COVERAGE_WEIGHT = 0.75
    private const val CONFIDENCE_WEIGHT = 0.25
    private const val QUALITY_EPSILON = 0.005

    data class Candidate(
        val source: Source,
        val onlineLineCount: Int,
        val translatedLineCount: Int,
        val result: OnlineTranslationMatcher.Result
    )

    fun shouldTryAlternative(candidate: Candidate?, totalLineCount: Int): Boolean {
        if (candidate == null || totalLineCount <= 0) return true
        return coverage(candidate, totalLineCount) < PREFERRED_COVERAGE_THRESHOLD
    }

    fun select(
        preferred: Candidate?,
        alternative: Candidate?,
        totalLineCount: Int
    ): Candidate? {
        if (preferred == null) return alternative
        if (alternative == null) return preferred
        val preferredQuality = quality(preferred, totalLineCount)
        val alternativeQuality = quality(alternative, totalLineCount)
        return if (alternativeQuality > preferredQuality + QUALITY_EPSILON) {
            alternative
        } else {
            preferred
        }
    }

    fun coverage(candidate: Candidate, totalLineCount: Int): Double {
        if (totalLineCount <= 0) return 0.0
        return candidate.result.matchedCount.toDouble() / totalLineCount
    }

    fun quality(candidate: Candidate, totalLineCount: Int): Double {
        return coverage(candidate, totalLineCount) * COVERAGE_WEIGHT +
            candidate.result.averageMatchScore * CONFIDENCE_WEIGHT
    }
}
