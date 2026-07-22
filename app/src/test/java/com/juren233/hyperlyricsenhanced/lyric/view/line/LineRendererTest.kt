/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

import org.junit.Assert.assertEquals
import org.junit.Test

class LineRendererTest {

    @Test
    fun `right aligns short plain text`() {
        assertEquals(
            60f,
            resolvePlainTextOffset(40f, 100f, 0f, isAlignedRight = true, centerIfPossible = false)
        )
    }

    @Test
    fun `centers short plain text when requested`() {
        assertEquals(
            30f,
            resolvePlainTextOffset(40f, 100f, 0f, isAlignedRight = false, centerIfPossible = true)
        )
    }

    @Test
    fun `keeps the scroll offset for overflowing text`() {
        assertEquals(
            -12f,
            resolvePlainTextOffset(140f, 100f, -12f, isAlignedRight = true, centerIfPossible = true)
        )
    }

    @Test
    fun `interlude dots follow normal line alignment rules`() {
        assertEquals(
            30f,
            resolveInterludeDotsStartX(40f, 100f, isAlignedRight = false, centerIfPossible = true)
        )
        assertEquals(
            60f,
            resolveInterludeDotsStartX(40f, 100f, isAlignedRight = true, centerIfPossible = false)
        )
        assertEquals(
            0f,
            resolveInterludeDotsStartX(40f, 100f, isAlignedRight = false, centerIfPossible = false)
        )
    }

    @Test
    fun `interlude dot size follows the configured lyric text size`() {
        assertEquals(10.8f, resolveInterludeDotSize(24f), 0.001f)
        assertEquals(14.4f, resolveInterludeDotSize(32f), 0.001f)
    }

    @Test
    fun `interlude dots reveal once across the actual instrumental duration`() {
        val initial = resolveInterludeDotsFrame(positionMs = 0L, beginMs = 0L, endMs = 10_000L)
        assertEquals(listOf(49, 49, 49), initial.dotAlphas)
        assertEquals(1f, initial.groupScale, 0.001f)

        val firstComplete = resolveInterludeDotsFrame(
            positionMs = 3_000L,
            beginMs = 0L,
            endMs = 10_000L
        )
        assertEquals(listOf(255, 49, 49), firstComplete.dotAlphas)

        val secondComplete = resolveInterludeDotsFrame(
            positionMs = 6_000L,
            beginMs = 0L,
            endMs = 10_000L
        )
        assertEquals(listOf(255, 255, 49), secondComplete.dotAlphas)

        val revealEnd = resolveInterludeDotsFrame(
            positionMs = 8_250L,
            beginMs = 0L,
            endMs = 10_000L
        )
        assertEquals(203, revealEnd.dotAlphas[2])

        val breathPeak = resolveInterludeDotsFrame(
            positionMs = 2_000L,
            beginMs = 0L,
            endMs = 10_000L
        )
        assertEquals(1.4f, breathPeak.groupScale, 0.001f)

        val finishedBeforeNextLyric = resolveInterludeDotsFrame(
            positionMs = 10_000L,
            beginMs = 0L,
            endMs = 10_000L
        )
        assertEquals(0, finishedBeforeNextLyric.groupAlpha)
    }

    @Test
    fun `interlude exit completes the third dot then grows and collapses`() {
        val start = InterludeDotsFrame(listOf(255, 255, 203), groupScale = 1f)

        val thirdComplete = resolveInterludeExitFrame(start, 750L)
        assertEquals(listOf(255, 255, 255), thirdComplete.dotAlphas)
        assertEquals(1f, thirdComplete.groupScale, 0.001f)

        val expanded = resolveInterludeExitFrame(start, 1_500L)
        assertEquals(1.4f, expanded.groupScale, 0.001f)
        assertEquals(255, expanded.groupAlpha)

        val collapsed = resolveInterludeExitFrame(start, 1_750L)
        assertEquals(0.5f, collapsed.groupScale, 0.001f)
        assertEquals(0, collapsed.groupAlpha)
    }
}
