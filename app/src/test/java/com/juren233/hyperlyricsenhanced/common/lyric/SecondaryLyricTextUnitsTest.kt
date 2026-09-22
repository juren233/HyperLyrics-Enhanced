/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.common.lyric

import org.junit.Assert.assertEquals
import org.junit.Test

class SecondaryLyricTextUnitsTest {
    @Test
    fun `English text uses complete words instead of letters`() {
        val text = "Hello, don't stop!"

        assertEquals(
            listOf("Hello, ", "don't ", "stop!"),
            SecondaryLyricTextUnits.ranges(text).map { text.substring(it) },
        )
    }

    @Test
    fun `non Latin text keeps complete grapheme boundaries`() {
        val text = "你👩‍👩‍👧‍👦好"

        assertEquals(
            listOf("你", "👩‍👩‍👧‍👦", "好"),
            SecondaryLyricTextUnits.ranges(text).map { text.substring(it) },
        )
    }

    @Test
    fun `mixed text keeps Latin word and non Latin graphemes intact`() {
        val text = "我 love 你"

        assertEquals(
            listOf("我", " love ", "你"),
            SecondaryLyricTextUnits.ranges(text).map { text.substring(it) },
        )
    }

    @Test
    fun `split index moves out of an English word`() {
        val text = "Hello brave world"

        assertEquals(
            6,
            SecondaryLyricTextUnits.adjustSplitIndex(text, 8) { false },
        )
    }

    @Test
    fun `first long English word moves as a whole when no prefix fits`() {
        val text = "Extraordinary words"

        assertEquals(
            14,
            SecondaryLyricTextUnits.adjustSplitIndex(text, 6) { false },
        )
    }

    @Test
    fun `hyphenated words stay one unit`() {
        val text = "well-known song"

        assertEquals(
            listOf("well-known ", "song"),
            SecondaryLyricTextUnits.ranges(text).map { text.substring(it) },
        )
    }

    @Test
    fun `split index never lands inside a contraction`() {
        // 左段放不下 I'm 整体时整体划入右段
        assertEquals(
            4,
            SecondaryLyricTextUnits.adjustSplitIndex("I'm here", 1) { true },
        )
    }

    @Test
    fun `attached comma never starts the right half`() {
        // 落在逗号前：含逗号的词簇只能整体移到一侧，按平衡取整体左移
        assertEquals(
            6,
            SecondaryLyricTextUnits.adjustSplitIndex("Hello world, how", 11) { true },
        )
    }
}
