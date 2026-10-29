/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.online

import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogCandidateSanitizingTest {
    @Test
    fun `kana reading of English lyrics is not a usable pronunciation`() {
        val lines = listOf(
            LrcLine(62_035L, "Looking the blitz", translation = "看着闪电战", romanization = "ルッキング ザ ブリッツ"),
        ).sanitizedForOnlineCandidate()
        assertNull(lines.single().romanization)
        assertEquals("看着闪电战", lines.single().translation)
    }

    @Test
    fun `latin romanization of non Latin lyrics is kept`() {
        val lines = listOf(
            LrcLine(1_000L, "この能力が光散らす", romanization = "kono chikara ga hikari chirasu"),
        ).sanitizedForOnlineCandidate()
        assertEquals("kono chikara ga hikari chirasu", lines.single().romanization)
    }
}
