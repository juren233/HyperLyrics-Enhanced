/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSourceRaceSelectionTest {
    private data class Result(val source: String, val wordTimed: Boolean, val score: Int)

    private fun selection() = SearchSourceRaceSelection<Result>(
        wordTimed = Result::wordTimed,
        score = Result::score,
    )

    @Test
    fun `first line result is taken then replaced by the first word result`() {
        val race = selection()
        assertTrue(race.offer(Result("KUWO", wordTimed = false, score = 95)))
        assertTrue(race.offer(Result("QM", wordTimed = true, score = 90)))
        assertFalse(race.offer(Result("KUGOU", wordTimed = false, score = 125)))
        assertEquals("QM", race.selected?.source)
    }

    @Test
    fun `a later word result with a higher score replaces the earlier one`() {
        val race = selection()
        assertTrue(race.offer(Result("NE", wordTimed = true, score = 95)))
        assertTrue(race.offer(Result("QM", wordTimed = true, score = 105)))
        assertFalse(race.offer(Result("KUWO", wordTimed = false, score = 125)))
        assertEquals("QM", race.selected?.source)
    }

    @Test
    fun `equal or lower word scores keep the first arrival`() {
        val race = selection()
        assertTrue(race.offer(Result("NE", wordTimed = true, score = 95)))
        assertFalse(race.offer(Result("QM", wordTimed = true, score = 95)))
        assertFalse(race.offer(Result("KUGOU", wordTimed = true, score = 90)))
        assertEquals("NE", race.selected?.source)
    }

    @Test
    fun `a later line result with a higher score replaces the earlier line`() {
        val race = selection()
        assertTrue(race.offer(Result("KUGOU", wordTimed = false, score = 90)))
        assertFalse(race.offer(Result("NE", wordTimed = false, score = 90)))
        assertTrue(race.offer(Result("KUWO", wordTimed = false, score = 125)))
        assertEquals("KUWO", race.selected?.source)
    }
}
