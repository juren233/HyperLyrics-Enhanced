/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.source.ttml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class TtmlTranslationLanguageTest {
    // Issue #77：AMLL am-lyrics/1891689523.ttml 前两行的结构（每行多语言翻译，英文行没有 en，日文行有 en）。
    private val multiLanguage = """
        <tt xmlns="http://www.w3.org/ns/ttml"
            xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
            xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
          <body><div>
            <p begin="6.720" end="9.730" itunes:key="L1"><span begin="6.720" end="9.330">your</span> <span begin="9.330" end="9.730">name</span><span ttm:role="x-translation" xml:lang="zh-Hans">重写你的名字</span><span ttm:role="x-translation" xml:lang="zh-Hant">重寫你的名字</span><span ttm:role="x-translation" xml:lang="ja">君の名前を書き換える</span><span ttm:role="x-translation" xml:lang="ko">너의 이름을 다시 써</span><span ttm:role="x-translation" xml:lang="fr">réécrivant ton nom</span></p>
            <p begin="10.260" end="13.260" itunes:key="L2"><span begin="10.260" end="10.840">零</span><span begin="10.930" end="13.260">戻り</span><span ttm:role="x-translation" xml:lang="zh-Hans">归于零</span><span ttm:role="x-translation" xml:lang="zh-Hant">歸於零</span><span ttm:role="x-translation" xml:lang="en">Returning to zero</span><span ttm:role="x-translation" xml:lang="fr">Retournant à zéro</span><span ttm:role="x-roman">modori</span></p>
          </div></body>
        </tt>
    """.trimIndent().toByteArray()

    private fun translations(bytes: ByteArray, locale: Locale): List<String?> =
        requireNotNull(TtmlLyricsParser.parseWordTimed(bytes, locale)).lrcLines.map { it.translation }

    @Test
    fun `simplified Chinese system picks zh-Hans on every line`() {
        assertEquals(listOf("重写你的名字", "归于零"), translations(multiLanguage, Locale.forLanguageTag("zh-CN")))
        assertEquals(listOf("重写你的名字", "归于零"), translations(multiLanguage, Locale.forLanguageTag("zh-Hans-CN")))
    }

    @Test
    fun `traditional Chinese system picks zh-Hant`() {
        assertEquals(listOf("重寫你的名字", "歸於零"), translations(multiLanguage, Locale.forLanguageTag("zh-TW")))
        assertEquals(listOf("重寫你的名字", "歸於零"), translations(multiLanguage, Locale.forLanguageTag("zh-Hant-HK")))
    }

    @Test
    fun `English system translates the Japanese line and leaves the English line alone`() {
        assertEquals(listOf(null, "Returning to zero"), translations(multiLanguage, Locale.forLanguageTag("en-US")))
    }

    @Test
    fun `non-Chinese system language absent from the document falls back to English`() {
        assertEquals(listOf(null, "Returning to zero"), translations(multiLanguage, Locale.forLanguageTag("ar-SA")))
    }

    @Test
    fun `without English the fallback is Chinese, simplified first`() {
        val noEnglish = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body><div><p begin="1.000" end="3.000"><span begin="1.000" end="2.000">a</span><span begin="2.000" end="3.000">b</span><span ttm:role="x-translation" xml:lang="ja">日本語</span><span ttm:role="x-translation" xml:lang="zh-Hant">繁體</span><span ttm:role="x-translation" xml:lang="zh-Hans">简体</span></p></div></body>
            </tt>
        """.trimIndent().toByteArray()

        assertEquals(listOf("简体"), translations(noEnglish, Locale.forLanguageTag("ar-SA")))
        assertEquals(listOf("简体"), translations(noEnglish, Locale.forLanguageTag("en-US")))
        assertEquals(listOf("日本語"), translations(noEnglish, Locale.JAPAN))
    }

    @Test
    fun `neither system language, English nor Chinese keeps each line's first translation`() {
        val other = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body><div><p begin="1.000" end="3.000"><span begin="1.000" end="2.000">a</span><span begin="2.000" end="3.000">b</span><span ttm:role="x-translation" xml:lang="fr">français</span><span ttm:role="x-translation" xml:lang="de">Deutsch</span></p></div></body>
            </tt>
        """.trimIndent().toByteArray()

        assertEquals(listOf("français"), translations(other, Locale.forLanguageTag("ar-SA")))
        // A Chinese system does not fall back to English.
        assertEquals(listOf("français"), translations(other, Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun `single zh-CN catalog translation is used for every system language`() {
        val single = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body><div><p begin="1.000" end="3.000"><span begin="1.000" end="2.000">遥</span><span begin="2.000" end="3.000">か</span><span ttm:role="x-translation" xml:lang="zh-CN">遥远</span></p></div></body>
            </tt>
        """.trimIndent().toByteArray()

        assertEquals(listOf("遥远"), translations(single, Locale.forLanguageTag("en-US")))
        assertEquals(listOf("遥远"), translations(single, Locale.forLanguageTag("zh-CN")))
        // Same language, other script: still the closest available.
        assertEquals(listOf("遥远"), translations(single, Locale.forLanguageTag("zh-TW")))
    }

    @Test
    fun `untagged translation does not inherit the lyric language of the document`() {
        val untagged = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xml:lang="ja">
              <body><div><p begin="1.000" end="3.000"><span begin="1.000" end="2.000">遥</span><span begin="2.000" end="3.000">か</span><span ttm:role="x-translation">遥远</span></p></div></body>
            </tt>
        """.trimIndent().toByteArray()

        assertEquals(listOf("遥远"), translations(untagged, Locale.JAPAN))
    }

    @Test
    fun `Apple head translations choose the container in the system language`() {
        val head = """
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
                itunes:timing="Word">
              <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal"><translations>
                <translation type="subtitle" xml:lang="zh-Hans"><text for="L1">你好</text></translation>
                <translation type="subtitle" xml:lang="en"><text for="L1">Hello</text></translation>
              </translations></iTunesMetadata></metadata></head>
              <body><div><p begin="1.000" end="3.000" itunes:key="L1"><span begin="1.000" end="2.000">こん</span><span begin="2.000" end="3.000">にちは</span></p></div></body>
            </tt>
        """.trimIndent().toByteArray()

        assertEquals(listOf("Hello"), translations(head, Locale.US))
        assertEquals(listOf("你好"), translations(head, Locale.SIMPLIFIED_CHINESE))
        // Korean is absent: a non-Chinese system falls back to English.
        assertEquals(listOf("Hello"), translations(head, Locale.KOREA))
    }

    @Test
    fun `main voice translation wins over background voice in the same language`() {
        val candidates = listOf(
            TtmlTranslation("背景", "en", background = true),
            TtmlTranslation("main", "en"),
        )
        val english = TtmlTranslationLanguage.plan(Locale.US, listOf("en"))
        val noMatch = TtmlTranslationLanguage.plan(Locale.US, listOf("fr"))
        assertEquals("main", english.select(candidates))
        assertEquals("main", noMatch.select(candidates))
        assertNull(english.select(emptyList()))
    }
}
