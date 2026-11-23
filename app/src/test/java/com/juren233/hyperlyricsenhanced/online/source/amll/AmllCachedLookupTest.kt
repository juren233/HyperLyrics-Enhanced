/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.online.source.amll

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AmllCachedLookupTest {
    @get:Rule val folder = TemporaryFolder()

    private val wordTimed = """
        <tt xmlns="http://www.w3.org/ns/ttml"
            xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
            itunes:timing="Word">
          <body><div>
            <p begin="1.000" end="3.000">
              <span begin="1.000" end="2.000">Hello </span>
              <span begin="2.000" end="3.000">world</span>
            </p>
          </div></body>
        </tt>
    """.trimIndent().toByteArray()

    @Test fun `a cached file that builds a match is used and kept`() {
        val file = File(folder.root, "am-lyrics_1.ttml").apply { writeBytes(wordTimed) }
        val result = AmllTtmlRepository.cachedLookup(file, "am-lyrics/1")
        assertNotNull(result?.match)
        assertTrue(file.isFile)
    }

    /** A whole TTML file whose spans carry no timing: line-timed only. */
    private val lineTimed = """
        <tt xmlns="http://www.w3.org/ns/ttml">
          <body><div>
            <p begin="1.000" end="3.000"><span>Hello </span><span>world</span></p>
          </div></body>
        </tt>
    """.trimIndent().toByteArray()

    @Test fun `a complete TTML file without word timing is kept and answers no match`() {
        val file = File(folder.root, "am-lyrics_5.ttml").apply { writeBytes(lineTimed) }
        val result = AmllTtmlRepository.cachedLookup(file, "am-lyrics/5")
        assertNotNull(result)
        assertNull(result!!.match)
        assertTrue(result.searched)
        assertTrue(file.isFile)
    }

    @Test fun `a cut-short line-timed file is not complete TTML and is deleted`() {
        val file = File(folder.root, "am-lyrics_6.ttml").apply { writeBytes(lineTimed.copyOf(lineTimed.size - 6)) }
        assertNull(AmllTtmlRepository.cachedLookup(file, "am-lyrics/6"))
        assertFalse(file.exists())
    }

    @Test fun `well-formed XML that is not TTML is deleted`() {
        val file = File(folder.root, "am-lyrics_7.ttml").apply { writeText("<html><body>Not Found</body></html>") }
        assertNull(AmllTtmlRepository.cachedLookup(file, "am-lyrics/7"))
        assertFalse(file.exists())
    }

    @Test fun `a file that is not strict UTF-8 is deleted`() {
        val bytes = lineTimed.copyOf().also { it[it.indexOf('H'.code.toByte())] = 0xC3.toByte() }
        val file = File(folder.root, "am-lyrics_8.ttml").apply { writeBytes(bytes) }
        assertNull(AmllTtmlRepository.cachedLookup(file, "am-lyrics/8"))
        assertFalse(file.exists())
    }

    /** The rule [AmllTtmlRepository.findWordTimed] applies before writing a fetched file. */
    @Test fun `a fetched file is written when complete TTML, with or without word timing, never when broken`() {
        fun keeps(bytes: ByteArray) = AmllTtmlRepository.worthKeeping(
            bytes,
            com.juren233.hyperlyricsenhanced.online.source.ttml.TtmlLookupResult(
                com.juren233.hyperlyricsenhanced.online.source.ttml.TtmlCatalogIo.buildMatch(
                    com.juren233.hyperlyricsenhanced.online.model.Source.AMLL, "id", "sha", bytes,
                ),
                searched = true,
            ),
        )
        assertTrue(keeps(wordTimed))
        assertTrue(keeps(lineTimed))
        assertFalse(keeps(lineTimed.copyOf(lineTimed.size - 6)))
        assertFalse(keeps(wordTimed.copyOf(wordTimed.size / 2)))
        assertFalse(keeps("<html><body>Not Found</body></html>".toByteArray()))
        assertFalse(keeps(ByteArray(0)))
    }

    @Test fun `a cut-short cached file is deleted so the lookup fetches again`() {
        val file = File(folder.root, "am-lyrics_2.ttml").apply { writeBytes(wordTimed.copyOf(wordTimed.size / 2)) }
        assertNull(AmllTtmlRepository.cachedLookup(file, "am-lyrics/2"))
        assertFalse(file.exists())
    }

    @Test fun `an empty cached file is deleted too`() {
        val file = File(folder.root, "am-lyrics_3.ttml").apply { writeBytes(ByteArray(0)) }
        assertNull(AmllTtmlRepository.cachedLookup(file, "am-lyrics/3"))
        assertFalse(file.exists())
    }

    @Test fun `no cached file means fetch`() {
        assertNull(AmllTtmlRepository.cachedLookup(File(folder.root, "missing.ttml"), "am-lyrics/4"))
    }
}
