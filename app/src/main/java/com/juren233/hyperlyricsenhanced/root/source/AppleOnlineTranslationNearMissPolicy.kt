/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

/**
 * 低于 85 分阈值的候选只有在强身份成立后才放行，该路径不降低普通候选的
 * 85 分阈值：
 * - 时长已校验（目标器保证标题精确相等、时长差小于 1500 ms 且分数不低于
 *   50）：覆盖率 ≥ 0.85 且置信度 ≥ 0.85。
 * - 多人署名时长未校验（标题精确相等但时长差 ≥ 1500 ms）：改用更严格的
 *   逐行歌词配对兜底，覆盖率 ≥ 0.90 且置信度 ≥ 0.90。
 */
internal object AppleOnlineTranslationNearMissPolicy {
    const val MIN_CANDIDATE_SCORE = 50
    const val MIN_COVERAGE = 0.85
    const val MIN_CONFIDENCE = 0.85
    const val LYRIC_FALLBACK_MIN_COVERAGE = 0.90
    const val LYRIC_FALLBACK_MIN_CONFIDENCE = 0.90

    data class VerificationInputs(
        val score: Int,
        val missingTranslationCount: Int,
        val matchedTranslationCount: Int,
        val missingPronunciationCount: Int,
        val matchedPronunciationCount: Int,
        val averageMatchScore: Double,
        val durationVerified: Boolean = true,
    )

    fun accepts(inputs: VerificationInputs): Boolean {
        val coverage = contentCoverage(inputs)
        return if (inputs.durationVerified) {
            inputs.score >= MIN_CANDIDATE_SCORE &&
                inputs.averageMatchScore >= MIN_CONFIDENCE &&
                coverage >= MIN_COVERAGE
        } else {
            inputs.averageMatchScore >= LYRIC_FALLBACK_MIN_CONFIDENCE &&
                coverage >= LYRIC_FALLBACK_MIN_COVERAGE
        }
    }

    /**
     * 翻译与发音任一维度缺失时按该维度的匹配占比衡量；两维都缺失时取较高者，
     * 避免只缺一种内容时被另一维度的空值压低覆盖率。
     */
    fun contentCoverage(inputs: VerificationInputs): Double {
        val translationCoverage = coverage(
            matched = inputs.matchedTranslationCount,
            missing = inputs.missingTranslationCount,
        )
        val pronunciationCoverage = coverage(
            matched = inputs.matchedPronunciationCount,
            missing = inputs.missingPronunciationCount,
        )
        return maxOf(
            translationCoverage ?: 0.0,
            pronunciationCoverage ?: 0.0,
        )
    }

    private fun coverage(matched: Int, missing: Int): Double? =
        if (missing <= 0) null else matched.toDouble() / missing
}
