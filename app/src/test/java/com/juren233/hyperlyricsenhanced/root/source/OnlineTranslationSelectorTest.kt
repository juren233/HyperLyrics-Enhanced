package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.online.model.Source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineTranslationSelectorTest {
    @Test
    fun `tries alternative when preferred coverage is below threshold`() {
        assertTrue(OnlineTranslationSelector.shouldTryAlternative(candidate(Source.NE, 44, 0.95), 48))
        assertFalse(OnlineTranslationSelector.shouldTryAlternative(candidate(Source.NE, 47, 0.95), 48))
    }

    @Test
    fun `selects alternative when its combined quality is better`() {
        val preferred = candidate(Source.NE, 44, 0.94)
        val alternative = candidate(Source.QM, 47, 0.92)

        assertSame(
            alternative,
            OnlineTranslationSelector.select(preferred, alternative, totalLineCount = 48)
        )
    }

    @Test
    fun `keeps preferred source when quality difference is negligible`() {
        val preferred = candidate(Source.QM, 47, 0.95)
        val alternative = candidate(Source.NE, 47, 0.96)

        assertSame(
            preferred,
            OnlineTranslationSelector.select(preferred, alternative, totalLineCount = 48)
        )
    }

    private fun candidate(
        source: Source,
        matchedCount: Int,
        averageMatchScore: Double
    ): OnlineTranslationSelector.Candidate {
        return OnlineTranslationSelector.Candidate(
            source = source,
            onlineLineCount = 48,
            translatedLineCount = matchedCount,
            result = OnlineTranslationMatcher.Result(
                song = Song(lyrics = listOf(RichLyricLine(text = "line"))),
                matchedCount = matchedCount,
                averageMatchScore = averageMatchScore
            )
        )
    }
}
