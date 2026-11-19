/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AodWordProgressTest {

    private val words = listOf(
        LyricWord(begin = 1_000, end = 1_400, text = "Hel"),
        LyricWord(begin = 1_400, end = 1_800, text = "lo "),
        LyricWord(begin = 2_000, end = 2_400, text = "world"),
    )

    @Test
    fun `before first word nothing is revealed`() {
        assertEquals(0f, AodWordProgress.progressChars("Hello world", words, 500L)!!, 0.001f)
    }

    @Test
    fun `finished words are fully revealed`() {
        assertEquals(6f, AodWordProgress.progressChars("Hello world", words, 1_900L)!!, 0.001f)
    }

    @Test
    fun `current word advances continuously by time fraction`() {
        // "world" 5 个字符，进度 50% -> 2.5 个字符
        assertEquals(8.5f, AodWordProgress.progressChars("Hello world", words, 2_200L)!!, 0.001f)
    }

    @Test
    fun `after last word everything is revealed`() {
        assertEquals(11f, AodWordProgress.progressChars("Hello world", words, 3_000L)!!, 0.001f)
    }

    @Test
    fun `trimmed display text offsets leading whitespace`() {
        val padded = listOf(
            LyricWord(begin = 0, end = 100, text = " "),
            LyricWord(begin = 100, end = 300, text = "Hi"),
        )
        assertEquals(1f, AodWordProgress.progressChars("Hi", padded, 200L)!!, 0.001f)
        assertEquals(0f, AodWordProgress.progressChars("Hi", padded, 50L)!!, 0.001f)
    }

    @Test
    fun `whitespace differences between text and words do not break alignment`() {
        // 展示文本里有双空格，逐字文本只有单空格：按非空白字符对齐（第 8 个非空白字符 'r' 在下标 9）
        assertEquals(9.5f, AodWordProgress.progressChars("Hello  world", words, 2_200L)!!, 0.001f)
        // 全部唱完回到文本末尾
        assertEquals(12f, AodWordProgress.progressChars("Hello  world", words, 3_000L)!!, 0.001f)
    }

    @Test
    fun `display text that differs from word text is unavailable`() {
        assertNull(AodWordProgress.progressChars("HelloWorld", words, 2_160L))
    }
}
