/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.common.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RichLyricLineSplitterTest {
    @Test
    fun `attached punctuation stays with the word on its left`() {
        // 词簇为 world,（6..11）：安全边界是词簇前 6 与逗号后 12
        assertEquals(
            6 to 12,
            RichLyricLineSplitter.nearestSafeSplitIndexes("Hello world, how are you", 11),
        )
    }

    @Test
    fun `contractions are never split at the apostrophe`() {
        assertEquals(0 to 3, RichLyricLineSplitter.nearestSafeSplitIndexes("I'm here", 1))
        assertEquals(0 to 3, RichLyricLineSplitter.nearestSafeSplitIndexes("I'm here", 2))
    }

    @Test
    fun `hyphenated words are never split at the hyphen`() {
        assertEquals(0 to 10, RichLyricLineSplitter.nearestSafeSplitIndexes("well-known song", 5))
    }

    @Test
    fun `mid word split snaps to the nearest word boundary`() {
        assertEquals(6 to 11, RichLyricLineSplitter.nearestSafeSplitIndexes("Hello world", 8))
    }

    @Test
    fun `space boundaries and non Latin text need no adjustment`() {
        assertNull(RichLyricLineSplitter.nearestSafeSplitIndexes("Hello world", 6))
        assertNull(RichLyricLineSplitter.nearestSafeSplitIndexes("你好世界", 2))
        assertNull(RichLyricLineSplitter.nearestSafeSplitIndexes("你好world", 2))
    }
}
