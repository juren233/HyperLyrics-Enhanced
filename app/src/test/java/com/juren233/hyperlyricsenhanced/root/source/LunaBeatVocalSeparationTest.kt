/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceInfo
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.view.LyricLineAssembler
import com.juren233.hyperlyricsenhanced.online.source.lunabeat.LunaBeatTtmlParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LunaBeatVocalSeparationTest {
    @Test
    fun `background descendants never enter the main word stream`() {
        val song = map("""
            <p begin="1.000" end="6.000">
              <span ttm:role="x-bg"><span begin="1.000" end="2.500">echo </span><span begin="2.500" end="5.000">again</span></span>
              <span begin="2.000" end="3.000">lead </span><span begin="3.000" end="4.000">voice</span>
            </p>
        """)
        // Both cross-process serialization and later view normalization must retain the split.
        val line = Json.decodeFromString<Song>(Json.encodeToString(song)).normalize().lyrics!!.single()
        assertEquals("lead voice", line.text)
        assertEquals("echo again", line.secondary)
        assertEquals(listOf(2_000L, 3_000L), line.words!!.map { it.begin })
        assertEquals(listOf(1_000L, 2_500L), line.secondaryWords!!.map { it.begin })
        assertEquals(listOf(2_500L, 5_000L), line.secondaryWords!!.map { it.end })
        val second = LyricLineAssembler().buildSecondary(line)
        assertTrue(second.alwaysShow)
        assertFalse(second.isNextLinePreview)
        assertEquals("echo again", second.line.text)
    }

    @Test
    fun `timed containers do not duplicate their timed children`() {
        val line = map("""
            <p begin="1.000" end="6.000">
              <span begin="1.000" end="4.000"><span begin="1.000" end="2.000">主</span><span begin="2.000" end="4.000">句</span></span>
              <span ttm:role="x-bg" begin="2.000" end="6.000"><span begin="2.000" end="3.000">伴</span><span begin="3.000" end="6.000">唱</span></span>
            </p>
        """).lyrics!!.single()
        assertEquals("主句", line.text)
        assertEquals("伴唱", line.secondary)
        assertEquals(2, line.words!!.size)
        assertEquals(2, line.secondaryWords!!.size)
    }

    @Test
    fun `inline spaces and raw TTML survive vocal separation`() {
        val song = map("""
            <p begin="1.000" end="6.000"><span begin="1.000" end="2.000">I've</span> <span begin="2.000" end="3.000">said</span> <span ttm:role="x-bg"><span begin="2.500" end="3.500">back</span> <span begin="3.500" end="5.000">up</span></span><span begin="3.000" end="4.000">it</span> <span begin="4.000" end="6.000">all</span></p>
        """)
        val line = song.lyrics!!.single()
        assertEquals("I've said it all", line.text)
        assertEquals("back up", line.secondary)
        assertTrue(song.metadata!!.getString(LyricMetadataKeys.LUNA_BEAT_RAW_TTML)!!.contains("ttm:role=\"x-bg\""))
    }

    @Test
    fun `identical words in both voices are retained by role`() {
        val line = map("""
            <p begin="1.000" end="4.000"><span begin="1.000" end="2.000">same </span><span begin="2.000" end="4.000">word</span><span ttm:role="x-bg"><span begin="1.000" end="2.000">same </span><span begin="2.000" end="4.000">word</span></span></p>
        """).lyrics!!.single()
        assertEquals("same word", line.text)
        assertEquals("same word", line.secondary)
        assertEquals(2, line.words!!.size)
        assertEquals(2, line.secondaryWords!!.size)
    }

    @Test
    fun `untimed inline markup belongs to its timed word`() {
        val line = map("""
            <p begin="1.000" end="4.000"><span begin="1.000" end="2.000">first <span>word</span></span> <span begin="2.000" end="4.000">last</span></p>
        """).lyrics!!.single()
        assertEquals("first word last", line.text)
        assertEquals(2, line.words!!.size)
        assertNull(line.secondary)
    }

    @Test
    fun `simultaneous paragraphs retain their own main text`() {
        val song = map("""
            <p begin="1.000" end="4.000"><span begin="1.000" end="2.000">first </span><span begin="2.000" end="4.000">voice</span></p>
            <p begin="1.000" end="5.000"><span begin="1.000" end="3.000">second </span><span begin="3.000" end="5.000">voice</span></p>
        """)
        assertEquals(listOf("first voice", "second voice"), song.lyrics!!.map { it.text })
        assertEquals(listOf(4_000L, 5_000L), song.lyrics!!.map { it.end })
    }

    @Test
    fun `simultaneous paragraphs retain their own translation`() {
        val song = map(
            body = """
                <p begin="1.000" end="4.000" itunes:key="first"><span begin="1.000" end="2.000">first </span><span begin="2.000" end="4.000">voice</span></p>
                <p begin="1.000" end="5.000" itunes:key="second"><span begin="1.000" end="3.000">second </span><span begin="3.000" end="5.000">voice</span></p>
            """,
            metadata = """<itunes:translation><itunes:text for="first">第一声部</itunes:text><itunes:text for="second">第二声部</itunes:text></itunes:translation>""",
        )
        assertEquals(listOf("第一声部", "第二声部"), song.lyrics!!.map { it.translation })
    }

    @Test
    fun `a timed background leaf preserves its own interval`() {
        val line = map("""
            <p begin="1.000" end="6.000"><span begin="1.000" end="2.000">主</span><span begin="2.000" end="4.000">句</span><span ttm:role="x-bg" begin="3.000" end="6.000">伴唱</span></p>
        """).lyrics!!.single()
        assertEquals("主句", line.text)
        assertEquals("伴唱", line.secondary)
        assertEquals(3_000L, line.secondaryWords!!.single().begin)
        assertEquals(6_000L, line.secondaryWords!!.single().end)
    }

    @Test
    fun `a standalone backing paragraph stays visible without leaking to the next line`() {
        val song = map("""
            <p begin="1.000" end="4.000"><span ttm:role="x-bg"><span begin="1.000" end="2.000">back </span><span begin="2.000" end="4.000">up</span></span></p>
            <p begin="5.000" end="7.000"><span begin="5.000" end="6.000">next </span><span begin="6.000" end="7.000">line</span></p>
        """)
        assertEquals(listOf("back up", "next line"), song.lyrics!!.map { it.text })
        assertTrue(song.lyrics!!.all { it.secondary.isNullOrBlank() && it.secondaryWords.isNullOrEmpty() })
    }

    private fun map(body: String, metadata: String = ""): Song {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word"><head><metadata>$metadata</metadata></head><body><div>$body</div></body></tt>"""
        val parsed = requireNotNull(LunaBeatTtmlParser.parseWordTimed(ttml.toByteArray()))
        return requireNotNull(AppleMissingLyricsSongMapper.map(
            baseSong = Song(id = "vocal-fixture", duration = 10_000L),
            wordLines = parsed.wordLines,
            lrcLines = parsed.lrcLines,
            sourceInfo = AppleMissingLyricsSourceInfo("LB", emptyList()),
            rawAppleTtml = ttml,
        )).also {
            assertEquals(ttml, it.metadata!!.getString(LyricMetadataKeys.LUNA_BEAT_RAW_TTML))
        }
    }
}
