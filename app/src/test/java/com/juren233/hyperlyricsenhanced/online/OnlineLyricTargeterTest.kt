package com.juren233.hyperlyricsenhanced.online

import com.juren233.hyperlyricsenhanced.online.model.LyricsLine
import com.juren233.hyperlyricsenhanced.online.model.LyricsResult
import com.juren233.hyperlyricsenhanced.online.model.LyricsWord
import com.juren233.hyperlyricsenhanced.online.model.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineLyricTargeterTest {

    @Test
    fun `netease preference searches netease first`() {
        assertEquals(
            listOf(Source.NE, Source.QM),
            OnlineLyricTargeter.resolveSourceOrder("com.apple.android.music", Source.NE)
        )
    }

    @Test
    fun `qq preference searches qq first`() {
        assertEquals(
            listOf(Source.QM, Source.NE),
            OnlineLyricTargeter.resolveSourceOrder("com.apple.android.music", Source.QM)
        )
    }

    @Test
    fun `strict source lookup does not fall back to the other provider`() {
        assertEquals(
            listOf(Source.QM),
            OnlineLyricTargeter.resolveSourceOrder(
                pkgName = "com.apple.android.music",
                preferredSource = Source.QM,
                fallbackToOtherSources = false
            )
        )
    }

    @Test
    fun `automatic order keeps the playing app preference`() {
        assertEquals(
            listOf(Source.NE, Source.QM),
            OnlineLyricTargeter.resolveSourceOrder("com.netease.cloudmusic", null)
        )
        assertEquals(
            listOf(Source.QM, Source.NE),
            OnlineLyricTargeter.resolveSourceOrder("com.tencent.qqmusic", null)
        )
    }

    @Test
    fun `retries when Apple internal metadata differs`() {
        assertEquals(
            true,
            OnlineLyricTargeter.shouldRetryWithOriginalMetadata(
                "Kawakiwoameku",
                "Minami",
                "カワキヲアメク",
                "美波"
            )
        )
        assertEquals(
            false,
            OnlineLyricTargeter.shouldRetryWithOriginalMetadata(
                "カワキヲアメク",
                "美波",
                "カワキヲアメク",
                "美波"
            )
        )
    }

    @Test
    fun `searches Apple original metadata first when correction is enabled`() {
        assertEquals(
            listOf(true, false),
            OnlineLyricTargeter.resolveMetadataSearchOrder(
                preferOriginalMetadata = true,
                hasDistinctOriginalMetadata = true,
            )
        )
        assertEquals(
            listOf(false, true),
            OnlineLyricTargeter.resolveMetadataSearchOrder(
                preferOriginalMetadata = false,
                hasDistinctOriginalMetadata = true,
            )
        )
        assertEquals(
            listOf(false),
            OnlineLyricTargeter.resolveMetadataSearchOrder(
                preferOriginalMetadata = true,
                hasDistinctOriginalMetadata = false,
            )
        )
    }

    @Test
    fun `normalizes internal spaces in Apple original artist names`() {
        assertEquals(
            "藤井風",
            OnlineLyricTargeter.compactWhitespace("藤井 風")
        )
    }

    @Test
    fun `accepts catalog duration drift up to five seconds`() {
        assertEquals(10, OnlineLyricTargeter.durationScore(315_000L, 310_000L))
        assertEquals(15, OnlineLyricTargeter.durationScore(315_000L, 315_386L))
        assertEquals(-30, OnlineLyricTargeter.durationScore(315_000L, 309_999L))
    }

    @Test
    fun `keeps timestamp-aligned translation in fallback lines`() {
        val result = LyricsResult(
            tags = emptyMap(),
            original = listOf(
                lyricLine(1_000L, "First"),
                lyricLine(4_000L, "Second")
            ),
            translated = listOf(
                lyricLine(1_000L, "第一句"),
                lyricLine(4_000L, "")
            ),
            romanization = null
        )

        val lines = OnlineLyricTargeter.toLrcLines(result)

        assertEquals("第一句", lines[0].translation)
        assertEquals(null, lines[1].translation)
    }

    @Test
    fun `drops slash only translation placeholders from provider results`() {
        val result = LyricsResult(
            tags = emptyMap(),
            original = listOf(
                lyricLine(1_000L, "First"),
                lyricLine(4_000L, "Second"),
                lyricLine(7_000L, "Third"),
            ),
            translated = listOf(
                lyricLine(1_000L, "// //"),
                lyricLine(4_000L, "///"),
                lyricLine(7_000L, "真实 / 译文"),
            ),
            romanization = null,
        )

        val lines = OnlineLyricTargeter.toLrcLines(result)

        assertEquals(listOf(null, null, "真实 / 译文"), lines.map { it.translation })
    }

    @Test
    fun `does not attach translation from a different timestamp`() {
        val result = LyricsResult(
            tags = emptyMap(),
            original = listOf(lyricLine(1_000L, "First")),
            translated = listOf(lyricLine(1_500L, "Wrong line")),
            romanization = null
        )

        assertEquals(null, OnlineLyricTargeter.toLrcLines(result).single().translation)
    }

    @Test
    fun `keeps timestamp aligned romanization in online lines`() {
        val result = LyricsResult(
            tags = emptyMap(),
            original = listOf(
                lyricLine(1_000L, "君の名は"),
                lyricLine(4_000L, "次の行"),
            ),
            translated = null,
            romanization = listOf(
                lyricLine(1_000L, "Kimi no na wa"),
                lyricLine(4_500L, "Wrong timestamp"),
            ),
        )

        val lines = OnlineLyricTargeter.toLrcLines(result)

        assertEquals("Kimi no na wa", lines[0].romanization)
        assertEquals(null, lines[1].romanization)
    }

    @Test
    fun `drops non Roman and copied pronunciation in online lines`() {
        val result = LyricsResult(
            tags = emptyMap(),
            original = listOf(
                lyricLine(1_000L, "Getting washed"),
                lyricLine(4_000L, "Home"),
            ),
            translated = null,
            romanization = listOf(
                lyricLine(1_000L, "ゲッティング ウォッシュト"),
                lyricLine(4_000L, "HOME"),
            ),
        )

        val lines = OnlineLyricTargeter.toLrcLines(result)

        assertEquals(null, lines[0].romanization)
        assertEquals(null, lines[1].romanization)
    }

    private fun lyricLine(start: Long, text: String): LyricsLine {
        return LyricsLine(
            start = start,
            end = start + 1_000L,
            words = listOf(LyricsWord(start, start + 1_000L, text))
        )
    }
}
