/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.root.timeline.FallbackSwitchPolicy.Due
import com.juren233.hyperlyricsenhanced.root.timeline.FallbackSwitchPolicy.Plan
import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackSwitchPolicyTest {
    private fun line(begin: Long, end: Long, text: String) =
        RichLyricLine(begin = begin, end = end, duration = end - begin, text = text)

    private val shown = listOf(line(0, 4_000, "a"), line(4_000, 9_000, "b"), line(9_000, 14_000, "c"))

    @Test
    fun `mid line defers to the start of the next line`() {
        assertEquals(Plan.Defer(4_000), FallbackSwitchPolicy.plan(shown, 1_500))
        assertEquals(Plan.Defer(9_000), FallbackSwitchPolicy.plan(shown, 5_000))
    }

    @Test
    fun `unknown position or no later line applies at once`() {
        assertEquals(Plan.ApplyNow, FallbackSwitchPolicy.plan(shown, null))
        assertEquals(Plan.ApplyNow, FallbackSwitchPolicy.plan(emptyList(), 1_000))
    }

    @Test
    fun `due waits until the target and then applies`() {
        assertEquals(Due.WAIT, FallbackSwitchPolicy.due(3_999, 4_000, displayedIsFallback = true))
        assertEquals(Due.APPLY, FallbackSwitchPolicy.due(4_000, 4_000, displayedIsFallback = true))
        assertEquals(Due.APPLY, FallbackSwitchPolicy.due(5_200, 4_000, displayedIsFallback = true))
        assertEquals(Due.WAIT, FallbackSwitchPolicy.due(null, 4_000, displayedIsFallback = true))
    }

    @Test
    fun `pending switch is dropped once the fallback is no longer displayed`() {
        assertEquals(Due.DROP, FallbackSwitchPolicy.due(3_000, 4_000, displayedIsFallback = false))
        assertEquals(Due.DROP, FallbackSwitchPolicy.due(5_000, 4_000, displayedIsFallback = false))
    }
}
