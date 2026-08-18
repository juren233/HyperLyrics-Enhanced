/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.lyric

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineTranslationMatchStatsTest {
    @Test
    fun `codec preserves match counts and rounds percentage`() {
        val encoded = OnlineTranslationMatchStatsCodec.encode(
            mapOf(
                "NE" to OnlineTranslationMatchStat(matchedLines = 2, totalLines = 3),
                "QM" to OnlineTranslationMatchStat(matchedLines = 3, totalLines = 3),
            )
        )

        val decoded = OnlineTranslationMatchStatsCodec.decode(encoded)

        assertEquals(2, decoded["NE"]?.matchedLines)
        assertEquals(67, decoded["NE"]?.percentage)
        assertEquals(100, decoded["QM"]?.percentage)
    }
}
