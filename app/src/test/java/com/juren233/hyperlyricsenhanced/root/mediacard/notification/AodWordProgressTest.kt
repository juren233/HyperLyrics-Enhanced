/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
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

    @Test
    fun `line level lyric sweeps evenly across the line span`() {
        val line = RichLyricLine(begin = 10_000, end = 14_000, text = "我们的歌")
        val span = AodWordProgress.lineSpanWordsFor(line, "我们的歌")!!
        assertEquals(1, span.size)
        assertEquals(0f, AodWordProgress.progressChars("我们的歌", span, 9_000L)!!, 0.001f)
        // 4 个字铺满 4 秒：过 1 秒正好 1 个字，过 3 秒 3 个字。
        assertEquals(1f, AodWordProgress.progressChars("我们的歌", span, 11_000L)!!, 0.001f)
        assertEquals(3f, AodWordProgress.progressChars("我们的歌", span, 13_000L)!!, 0.001f)
        assertEquals(4f, AodWordProgress.progressChars("我们的歌", span, 15_000L)!!, 0.001f)
    }

    @Test
    fun `line span ignores spaces when spreading english text`() {
        val line = RichLyricLine(begin = 0, end = 1_000, text = "ab cd")
        val span = AodWordProgress.lineSpanWordsFor(line, "ab cd")!!
        // 4 个非空白字符，过半 -> 前 2 个字母 "ab" 填满，空格不占时间，紧接着的空格一并算进去。
        assertEquals(3f, AodWordProgress.progressChars("ab cd", span, 500L)!!, 0.001f)
    }

    @Test
    fun `line span is reused for the same line and text`() {
        val line = RichLyricLine(begin = 0, end = 2_000, text = "hello")
        val first = AodWordProgress.lineSpanWordsFor(line, "hello")
        assertSame(first, AodWordProgress.lineSpanWordsFor(line, "hello"))
    }

    @Test
    fun `line without usable timing gets no synthesized sweep`() {
        assertNull(AodWordProgress.lineSpanWordsFor(RichLyricLine(begin = 2_000, end = 2_000, text = "x"), "x"))
        assertNull(AodWordProgress.lineSpanWordsFor(RichLyricLine(begin = 0, end = 2_000, text = " "), " "))
    }

    @Test
    fun `out of order words are sorted like the island before progress`() {
        val raw = listOf(
            LyricWord(begin = 2_000, end = 2_400, text = "world"),
            LyricWord(begin = 1_000, end = 1_400, text = "Hel"),
            LyricWord(begin = 1_400, end = 1_800, text = "lo "),
        )
        val normalized = AodWordProgress.wordsOf(raw)!!
        assertEquals(listOf(1_000L, 1_400L, 2_000L), normalized.map { it.begin })
        assertEquals(1.5f, AodWordProgress.progressChars("Hello world", normalized, 1_200L)!!, 0.001f)
        // 同一原始列表复用同一实例，不触发重绘
        assertTrue(normalized === AodWordProgress.wordsOf(raw))
        // 原始数据不被改动
        assertEquals(2_000L, raw.first().begin)
    }

    @Test
    fun `instant words sweep until the next word starts`() {
        val words = listOf(
            LyricWord(begin = 6_698, end = 6_699, text = "you're "),
            LyricWord(begin = 7_068, end = 7_069, text = "the "),
            LyricWord(begin = 7_448, end = 7_900, text = "one"),
        )
        val stretched = AodWordProgress.normalized(words)
        assertEquals(listOf(7_068L, 7_448L, 7_900L), stretched.map { it.end })
        assertEquals(6_699L, words.first().end)
    }
}
