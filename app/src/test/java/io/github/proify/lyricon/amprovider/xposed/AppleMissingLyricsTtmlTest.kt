/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMissingLyricsTtmlTest {

    @Test
    fun `seconds formats decimal seconds like Apple ttml`() {
        assertEquals("0.000", AppleMissingLyricsTtml.seconds(0L))
        assertEquals("12.345", AppleMissingLyricsTtml.seconds(12_345L))
        assertEquals("62.003", AppleMissingLyricsTtml.seconds(62_003L))
    }

    @Test
    fun `duration formats minutes seconds millis`() {
        assertEquals("0:00.000", AppleMissingLyricsTtml.duration(0L))
        assertEquals("0:12.345", AppleMissingLyricsTtml.duration(12_345L))
        assertEquals("1:02.003", AppleMissingLyricsTtml.duration(62_003L))
        assertEquals("62:03.004", AppleMissingLyricsTtml.duration(3_723_004L))
    }

    @Test
    fun `build emits word spans when word timeline exists`() {
        val lines = listOf(
            AppleMissingLyricsLine(
                begin = 12_000L,
                end = 14_000L,
                text = "第一句",
                words = listOf(
                    AppleMissingLyricsWord(begin = 12_000L, end = 12_800L, text = "第一"),
                    AppleMissingLyricsWord(begin = 12_800L, end = 13_900L, text = "句"),
                ),
            ),
        )
        val ttml = AppleMissingLyricsTtml.build(lines, durationMs = 20_000L)
        assertTrue(
            ttml.startsWith(
                "<tt xmlns=\"http://www.w3.org/ns/ttml\" " +
                    "xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" " +
                    "xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" " +
                    "itunes:timing=\"Word\" xml:lang=\"zh-Hans\" " +
                    "xml:space=\"preserve\">"
            )
        )
        assertTrue(ttml.contains("<ttm:agent type=\"person\" xml:id=\"v1\"/>"))
        assertTrue(ttml.contains("<body dur=\"0:20.000\">"))
        assertTrue(ttml.contains("<div begin=\"12.000\" end=\"14.000\">"))
        assertTrue(
            ttml.contains(
                "<p begin=\"12.000\" end=\"14.000\" " +
                    "ttm:agent=\"v1\" itunes:key=\"L1\">"
            )
        )
        assertTrue(
            ttml.contains(
                "<span begin=\"12.000\" end=\"12.800\">第一</span>" +
                    "<span begin=\"12.800\" end=\"13.900\">句</span>"
            )
        )
    }

    @Test
    fun `build preserves english spaces inside word spans`() {
        val lines = listOf(
            AppleMissingLyricsLine(
                begin = 0L,
                end = 2_000L,
                text = "Hotel California",
                words = listOf(
                    AppleMissingLyricsWord(0L, 800L, "Hotel "),
                    AppleMissingLyricsWord(800L, 1_800L, "California"),
                ),
            ),
        )

        val ttml = AppleMissingLyricsTtml.build(lines, durationMs = 2_000L)

        assertTrue(ttml.contains(">Hotel </span>"))
        assertTrue(ttml.contains(">California</span>"))
        assertTrue(ttml.contains("xml:space=\"preserve\""))
    }

    @Test
    fun `build uses line timing and plain paragraph text without word timeline`() {
        val lines = listOf(
            AppleMissingLyricsLine(
                begin = 1_000L,
                end = 3_000L,
                text = "无逐字行 & <特殊> \"字符\"",
                words = emptyList(),
            ),
        )
        val ttml = AppleMissingLyricsTtml.build(lines, durationMs = 5_000L)
        assertTrue(ttml.contains("itunes:timing=\"Line\""))
        assertTrue(
            ttml.contains(
                "<p begin=\"1.000\" end=\"3.000\" " +
                    "ttm:agent=\"v1\" itunes:key=\"L1\">" +
                    "无逐字行 &amp; &lt;特殊&gt; &quot;字符&quot;</p>"
            )
        )
        assertTrue(!ttml.contains("<span"))
    }

    @Test
    fun `single full-line pseudo word still uses line timing`() {
        val text = "I've been up on the pedestal"
        val lines = listOf(
            AppleMissingLyricsLine(
                begin = 1_000L,
                end = 4_000L,
                text = text,
                words = listOf(
                    AppleMissingLyricsWord(
                        begin = 1_000L,
                        end = 4_000L,
                        text = text,
                    )
                ),
            ),
        )

        val ttml = AppleMissingLyricsTtml.build(lines, durationMs = 5_000L)

        assertTrue(ttml.contains("itunes:timing=\"Line\""))
        assertTrue(ttml.contains(">$text</p>"))
        assertTrue(!ttml.contains("<span"))
    }
}
