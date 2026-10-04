/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleLyricDiagnosticSummaryTest {
    @Test
    fun `summarizes translation text without retaining any content`() {
        assertEquals(
            "originalLength=8, selectedLength=10, resultLength=9",
            appleLyricTextLengthSummary("original", "translated", "displayed"),
        )
    }

    @Test
    fun `handles absent and empty text`() {
        assertEquals(
            "originalLength=0, selectedLength=0, resultLength=0",
            appleLyricTextLengthSummary(null, "", null),
        )
    }

    @Test
    fun `never stringifies an unexpected hook result`() {
        val unexpected = object {
            override fun toString(): String = error("must not inspect payload")
        }
        assertEquals(
            "originalLength=0, selectedLength=0, resultLength=0",
            appleLyricTextLengthSummary(null, null, unexpected),
        )
    }

    @Test
    fun `word timing diagnostics retain timing without lyric samples`() {
        assertEquals(
            "7@1-42:sampleChars=6",
            appleWordTimingDiagnosticSummary(7, 1, 42, "hidden"),
        )
        assertEquals(
            "8@42-90:sampleChars=0",
            appleWordTimingDiagnosticSummary(8, 42, 90, null),
        )
    }
}
