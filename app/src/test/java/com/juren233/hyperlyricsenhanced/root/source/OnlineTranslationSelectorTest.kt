package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.online.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineTranslationSelectorTest {
    @Test
    fun `tries alternatives until all requested content is covered`() {
        assertTrue(OnlineTranslationSelector.shouldTryAlternative(candidate(Source.NE, 44, 0.95), 48))
        assertTrue(OnlineTranslationSelector.shouldTryAlternative(candidate(Source.NE, 47, 0.95), 48))
        assertFalse(OnlineTranslationSelector.shouldTryAlternative(candidate(Source.NE, 48, 0.95), 48))
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

    @Test
    fun `ranks every candidate by combined quality`() {
        val netease = candidate(Source.NE, 44, 0.98)
        val qq = candidate(Source.QM, 47, 0.92)
        val kuwo = candidate(Source.KUWO, 46, 0.97)
        val kugou = candidate(Source.KUGOU, 48, 0.96)

        assertEquals(
            listOf(kugou, qq, kuwo, netease),
            OnlineTranslationSelector.rank(
                candidates = listOf(netease, qq, kuwo, kugou),
                totalLineCount = 48,
                tieBreakOrder = listOf(Source.NE, Source.QM, Source.KUWO, Source.KUGOU),
            )
        )
    }

    @Test
    fun `ranking uses stable source order when qualities tie`() {
        val netease = candidate(Source.NE, 48, 0.95)
        val qq = candidate(Source.QM, 48, 0.95)

        assertEquals(
            listOf(qq, netease),
            OnlineTranslationSelector.rank(
                candidates = listOf(netease, qq),
                totalLineCount = 48,
                tieBreakOrder = listOf(Source.QM, Source.NE),
            )
        )
    }

    @Test
    fun `tries alternative when translation is complete but pronunciation is missing`() {
        val translationOnly = candidate(
            source = Source.NE,
            matchedCount = 48,
            averageMatchScore = 0.98,
            matchedContentCount = 48,
        )

        assertTrue(
            OnlineTranslationSelector.shouldTryAlternative(
                translationOnly,
                totalLineCount = 96,
            )
        )
    }

    private fun candidate(
        source: Source,
        matchedCount: Int,
        averageMatchScore: Double,
        matchedContentCount: Int = matchedCount,
    ): OnlineTranslationSelector.Candidate {
        return OnlineTranslationSelector.Candidate(
            source = source,
            onlineLineCount = 48,
            translatedLineCount = matchedCount,
            result = OnlineTranslationMatcher.Result(
                song = Song(lyrics = listOf(RichLyricLine(text = "line"))),
                matchedCount = matchedCount,
                averageMatchScore = averageMatchScore
            ),
            matchedContentCount = matchedContentCount,
        )
    }
}
