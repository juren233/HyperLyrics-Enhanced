/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import org.junit.Assert.assertEquals
import org.junit.Test

class PreparedTextRunTest {
    @Test
    fun `mixed fonts retain their measured origins without per frame measurement`() {
        var measurements = 0
        val runs = prepareTextRuns("AB中文12", 7f,
            fontAt = { if (it in 'A'..'Z' || it in '0'..'9') "narrow" else "base" },
            measure = { text, font ->
                measurements++
                text.length * if (font == "narrow") 4f else 9f
            },
        )
        assertEquals(listOf(
            PreparedTextRun("AB", 7f, "narrow"),
            PreparedTextRun("中文", 15f, "base"),
            PreparedTextRun("12", 33f, "narrow"),
        ), runs)
        repeat(60) { assertEquals("AB中文12", runs.joinToString("") { it.text }) }
        assertEquals(2, measurements)
    }

    @Test
    fun `one font preserves surrogate pairs and combining marks without measuring unused tail`() {
        val text = "A\u0301😀"
        val runs = prepareTextRuns(text, 3f, { "base" }) { _, _ -> error("No following run") }
        assertEquals(listOf(PreparedTextRun(text, 3f, "base")), runs)
        assertEquals(emptyList<PreparedTextRun<String>>(), prepareTextRuns("", 0f, { "base" }) { _, _ -> error("Empty") })
    }
}
