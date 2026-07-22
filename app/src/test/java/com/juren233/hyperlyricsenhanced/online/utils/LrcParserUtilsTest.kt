package com.juren233.hyperlyricsenhanced.online.utils

import com.juren233.hyperlyricsenhanced.online.model.LyricsLine
import com.juren233.hyperlyricsenhanced.online.model.LyricsWord
import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserUtilsTest {
    @Test
    fun `matches translation to the nearest original timestamp instead of the next window entry`() {
        val original = listOf(
            line(47_780L, "Eating halal"),
            line(49_490L, "Told that I am sorry"),
            line(51_250L, "Bout my coins like Mario")
        )
        val translations = listOf(
            line(47_380L, "开着兰博基尼"),
            line(49_260L, "告诉那个人我很抱歉"),
            line(50_930L, "我的硬币像 Mario 一样")
        )

        val merged = LrcParserUtils.lyricsMerge(original, translations)

        assertEquals(
            listOf("开着兰博基尼", "告诉那个人我很抱歉", "我的硬币像 Mario 一样"),
            merged?.map { lyricLine -> lyricLine.words.joinToString("") { it.text } }
        )
    }

    private fun line(start: Long, text: String) = LyricsLine(
        start = start,
        end = start + 1_000L,
        words = listOf(LyricsWord(start, start + 1_000L, text))
    )
}
