/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongRepositoryTest {

    @Test
    fun textlessTimingCacheIsTreatedAsMissingLyrics() {
        val normalized = SongRepository.normalizeRenderableLyrics(
            Song(
                id = "1810905308",
                lyrics = listOf(
                    RichLyricLine(
                        begin = 1_000L,
                        end = 2_000L,
                        text = "",
                        words = listOf(
                            LyricWord(begin = 1_000L, end = 1_500L, text = ""),
                            LyricWord(begin = 1_500L, end = 2_000L, text = null),
                        ),
                    )
                ),
            )
        )

        assertTrue(normalized.lyrics.isNullOrEmpty())
    }

    @Test
    fun wordOnlyCacheRecoversMainAndSecondaryText() {
        val normalized = SongRepository.normalizeRenderableLyrics(
            Song(
                id = "song",
                lyrics = listOf(
                    RichLyricLine(
                        begin = 0L,
                        end = 1_000L,
                        words = listOf(
                            LyricWord(text = "Shape"),
                            LyricWord(text = "shifter"),
                        ),
                        secondaryWords = listOf(
                            LyricWord(text = "(Ooh)"),
                        ),
                    )
                ),
            )
        )

        val line = normalized.lyrics.orEmpty().single()
        assertEquals("Shapeshifter", line.text)
        assertEquals("(Ooh)", line.secondary)
    }

    @Test
    fun invalidLinesAreRemovedWithoutDroppingValidCachedLyrics() {
        val normalized = SongRepository.normalizeRenderableLyrics(
            Song(
                id = "song",
                lyrics = listOf(
                    RichLyricLine(begin = 0L, end = 1_000L, text = "valid"),
                    RichLyricLine(begin = 1_000L, end = 2_000L, text = " "),
                ),
            )
        )

        assertEquals(listOf("valid"), normalized.lyrics.orEmpty().map { it.text })
    }
}
