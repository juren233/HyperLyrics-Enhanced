/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.source.lunabeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LunaBeatTtmlParserTest {
    @Test
    fun `parses Apple word timing and metadata translation`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
                xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                itunes:timing="Word">
              <head><metadata>
                <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
                  <translations><translation type="subtitle" xml:lang="zh-Hans">
                    <text for="L1">你好世界</text>
                  </translation></translations>
                </iTunesMetadata>
              </metadata></head>
              <body><div>
                <p begin="1.000" end="3.000" itunes:key="L1">
                  <span begin="1.000" end="2.000">Hello </span>
                  <span begin="2.000" end="3.000">world</span>
                </p>
              </div></body>
            </tt>
        """.trimIndent().toByteArray()

        val parsed = requireNotNull(LunaBeatTtmlParser.parseWordTimed(ttml))

        assertEquals(1, parsed.wordLines.size)
        assertEquals(2, parsed.wordLines.single().words.size)
        assertEquals(1_000L, parsed.wordLines.single().start)
        assertEquals(3_000L, parsed.wordLines.single().end)
        assertEquals("你好世界", parsed.lrcLines.single().translation)
        assertTrue(parsed.lrcLines.single().content.contains("Hello"))
    }

    @Test
    fun `rejects line timing and one-span pseudo word lyrics`() {
        val lineTimed = """
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
                itunes:timing="Line">
              <body><div><p begin="1.000" end="2.000">整行歌词</p></div></body>
            </tt>
        """.trimIndent().toByteArray()
        val pseudoWord = """
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
                itunes:timing="Word">
              <body><div><p begin="1.000" end="2.000">
                <span begin="1.000" end="2.000">整行歌词</span>
              </p></div></body>
            </tt>
        """.trimIndent().toByteArray()

        assertNull(LunaBeatTtmlParser.parseWordTimed(lineTimed))
        assertNull(LunaBeatTtmlParser.parseWordTimed(pseudoWord))
    }

    @Test
    fun `rejects documents with a doctype`() {
        val ttml = """
            <!DOCTYPE tt [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
                itunes:timing="Word"><body><div/></body></tt>
        """.trimIndent().toByteArray()

        assertNull(LunaBeatTtmlParser.parseWordTimed(ttml))
    }

    @Test
    fun `parses Apple decimal and minute timestamps`() {
        assertEquals(35_476L, LunaBeatTtmlParser.parseTimeMs("35.476"))
        assertEquals(189_955L, LunaBeatTtmlParser.parseTimeMs("3:09.955"))
    }
    @Test
    fun `preserves inline English and CJK separators between timed spans`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"
            xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
            itunes:timing="Word"><body><div>
            <p begin="1.000" end="2.000"><span begin="1.000" end="1.200">I've</span> <span begin="1.200" end="1.400">said</span> <span begin="1.400" end="1.600">it</span> <span begin="1.600" end="2.000">all</span></p>
            <p begin="2.000" end="3.000"><span begin="2.000" end="2.500">我</span> <span begin="2.500" end="3.000">是</span></p>
            </div></body></tt>""".toByteArray()

        val parsed = requireNotNull(LunaBeatTtmlParser.parseWordTimed(ttml))

        assertEquals("I've said it all", parsed.wordLines[0].words.joinToString("") { it.text })
        assertEquals("I've said it all", parsed.lrcLines[0].content)
        assertEquals("我 是", parsed.wordLines[1].words.joinToString("") { it.text })
        assertEquals("我 是", parsed.lrcLines[1].content)
    }

}
