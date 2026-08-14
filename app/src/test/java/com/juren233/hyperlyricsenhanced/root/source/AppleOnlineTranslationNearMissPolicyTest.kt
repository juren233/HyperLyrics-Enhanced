package com.juren233.hyperlyricsenhanced.root.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleOnlineTranslationNearMissPolicyTest {

    @Test
    fun `accepts a strong identity near miss with full translation overlap`() {
        assertTrue(
            AppleOnlineTranslationNearMissPolicy.accepts(
                AppleOnlineTranslationNearMissPolicy.VerificationInputs(
                    score = 60,
                    missingTranslationCount = 46,
                    matchedTranslationCount = 46,
                    missingPronunciationCount = 46,
                    matchedPronunciationCount = 0,
                    averageMatchScore = 0.99,
                )
            )
        )
    }

    @Test
    fun `rejects a near miss below the candidate score floor`() {
        assertFalse(
            AppleOnlineTranslationNearMissPolicy.accepts(
                inputs(score = 49)
            )
        )
    }

    @Test
    fun `rejects a near miss with low lyric overlap`() {
        assertFalse(
            AppleOnlineTranslationNearMissPolicy.accepts(
                inputs(
                    matchedTranslationCount = 12,
                    missingTranslationCount = 46,
                )
            )
        )
    }

    @Test
    fun `rejects a near miss with low average match confidence`() {
        assertFalse(
            AppleOnlineTranslationNearMissPolicy.accepts(
                inputs(averageMatchScore = 0.5)
            )
        )
    }

    @Test
    fun `pronunciation overlap can verify when translation is already complete`() {
        val inputs = AppleOnlineTranslationNearMissPolicy.VerificationInputs(
            score = 60,
            missingTranslationCount = 0,
            matchedTranslationCount = 0,
            missingPronunciationCount = 40,
            matchedPronunciationCount = 40,
            averageMatchScore = 0.95,
        )

        assertTrue(AppleOnlineTranslationNearMissPolicy.accepts(inputs))
        assertEquals(1.0, AppleOnlineTranslationNearMissPolicy.contentCoverage(inputs), 1e-9)
    }

    @Test
    fun `coverage takes the better dimension when both are missing`() {
        val inputs = AppleOnlineTranslationNearMissPolicy.VerificationInputs(
            score = 60,
            missingTranslationCount = 10,
            matchedTranslationCount = 10,
            missingPronunciationCount = 10,
            matchedPronunciationCount = 2,
            averageMatchScore = 0.9,
        )

        assertEquals(1.0, AppleOnlineTranslationNearMissPolicy.contentCoverage(inputs), 1e-9)
    }

    @Test
    fun `unverified duration accepts only strong lyric pairing`() {
        assertTrue(
            AppleOnlineTranslationNearMissPolicy.accepts(
                inputs(
                    score = 15,
                    durationVerified = false,
                    matchedTranslationCount = 42,
                    missingTranslationCount = 46,
                    averageMatchScore = 0.94,
                )
            )
        )
        assertFalse(
            AppleOnlineTranslationNearMissPolicy.accepts(
                inputs(
                    score = 15,
                    durationVerified = false,
                    matchedTranslationCount = 40,
                    missingTranslationCount = 46,
                    averageMatchScore = 0.94,
                )
            )
        )
        assertFalse(
            AppleOnlineTranslationNearMissPolicy.accepts(
                inputs(
                    score = 15,
                    durationVerified = false,
                    matchedTranslationCount = 46,
                    missingTranslationCount = 46,
                    averageMatchScore = 0.89,
                )
            )
        )
    }

    private fun inputs(
        score: Int = 60,
        matchedTranslationCount: Int = 46,
        missingTranslationCount: Int = 46,
        matchedPronunciationCount: Int = 0,
        missingPronunciationCount: Int = 46,
        averageMatchScore: Double = 0.99,
        durationVerified: Boolean = true,
    ) = AppleOnlineTranslationNearMissPolicy.VerificationInputs(
        score = score,
        missingTranslationCount = missingTranslationCount,
        matchedTranslationCount = matchedTranslationCount,
        missingPronunciationCount = missingPronunciationCount,
        matchedPronunciationCount = matchedPronunciationCount,
        averageMatchScore = averageMatchScore,
        durationVerified = durationVerified,
    )
}
