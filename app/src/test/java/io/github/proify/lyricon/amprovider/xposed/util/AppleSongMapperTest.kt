/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.util

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import io.github.proify.lyricon.amprovider.xposed.model.AppleSong
import io.github.proify.lyricon.amprovider.xposed.model.LyricAgent
import io.github.proify.lyricon.amprovider.xposed.model.LyricLine
import io.github.proify.lyricon.amprovider.xposed.model.LyricWord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleSongMapperTest {

    @Test
    fun `builds secondary text from background words when line text is blank`() {
        val appleSong = AppleSong(
            lyrics = mutableListOf(
                LyricLine(
                    htmlLineText = "Main lyric",
                    htmlTranslationLineText = "主歌词翻译",
                    htmlBackgroundVocalsLineText = "",
                    backgroundWords = mutableListOf(
                        LyricWord(text = "(Ooh, "),
                        LyricWord(text = "yeah)")
                    )
                )
            )
        )

        val mappedLine = AppleSongMapper.map(appleSong).lyrics.orEmpty().single()

        assertEquals("(Ooh, yeah)", mappedLine.secondary)
        assertEquals("主歌词翻译", mappedLine.translation)
    }

    @Test
    fun `keeps the background vocals line text when it is available`() {
        val appleSong = AppleSong(
            lyrics = mutableListOf(
                LyricLine(
                    htmlLineText = "Main lyric",
                    htmlBackgroundVocalsLineText = "Formatted background vocal",
                    backgroundWords = mutableListOf(LyricWord(text = "Fallback"))
                )
            )
        )

        val mappedLine = AppleSongMapper.map(appleSong).lyrics.orEmpty().single()

        assertEquals("Formatted background vocal", mappedLine.secondary)
    }

    @Test
    fun `carries translated background vocals in lyric metadata`() {
        val appleSong = AppleSong(
            lyrics = mutableListOf(
                LyricLine(
                    htmlLineText = "Main lyric",
                    htmlTranslationLineText = "主句翻译",
                    htmlBackgroundVocalsLineText = "Backing vocal",
                    htmlTranslatedBackgroundVocalsLineText = "伴唱翻译"
                )
            )
        )

        val mappedLine = AppleSongMapper.map(appleSong).lyrics.orEmpty().single()

        assertEquals("主句翻译", mappedLine.translation)
        assertEquals("Backing vocal", mappedLine.secondary)
        assertEquals(
            "伴唱翻译",
            mappedLine.metadata?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
        )
    }

    @Test
    fun `preserves delayed background word timing without synthetic anchors`() {
        val appleSong = AppleSong(
            lyrics = mutableListOf(
                LyricLine(
                    begin = 16540,
                    end = 23076,
                    htmlLineText = "Main lyric",
                    htmlBackgroundVocalsLineText = "Yeah",
                    backgroundWords = mutableListOf(
                        LyricWord(begin = 22025, end = 23076, text = "Yeah")
                    )
                )
            )
        )

        val mappedLine = AppleSongMapper.map(appleSong).lyrics.orEmpty().single()
        val secondaryWords = mappedLine.secondaryWords.orEmpty()

        assertEquals("Yeah", mappedLine.secondary)
        assertEquals(1, secondaryWords.size)
        assertEquals("Yeah", secondaryWords[0].text)
        assertEquals(22025L, secondaryWords[0].begin)
        assertEquals(23076L, secondaryWords[0].end)

        val normalizedWords = mappedLine.normalize().secondaryWords.orEmpty()
        assertEquals(1, normalizedWords.size)
        assertEquals(22025L, normalizedWords[0].begin)
    }

    @Test
    fun `keeps background word timing when vocals start with the main line`() {
        val appleSong = AppleSong(
            lyrics = mutableListOf(
                LyricLine(
                    begin = 1000,
                    end = 4000,
                    htmlLineText = "Main lyric",
                    htmlBackgroundVocalsLineText = "Yeah",
                    backgroundWords = mutableListOf(
                        LyricWord(begin = 1200, end = 1800, text = "Yeah")
                    )
                )
            )
        )

        val mappedLine = AppleSongMapper.map(appleSong).lyrics.orEmpty().single()

        assertEquals(1, mappedLine.secondaryWords.orEmpty().size)
        assertEquals(1200L, mappedLine.secondaryWords.orEmpty().single().begin)
    }

    @Test
    fun `maps the first referenced agent left regardless of id and declaration order`() {
        val appleSong = AppleSong(
            agents = mutableListOf(
                LyricAgent(id = "v1", type = LyricAgent.Type.PERSON.type),
                LyricAgent(id = "v2", type = LyricAgent.Type.PERSON.type)
            ),
            lyrics = mutableListOf(
                LyricLine(agent = "v2", htmlLineText = "come here"),
                LyricLine(agent = "v1", htmlLineText = "say it, spit it out")
            )
        )

        val mappedLines = AppleSongMapper.map(appleSong).lyrics.orEmpty()

        assertFalse(mappedLines[0].isAlignedRight)
        assertTrue(mappedLines[1].isAlignedRight)
    }

    @Test
    fun `maps non-person secondary agents to the right`() {
        val appleSong = AppleSong(
            agents = mutableListOf(
                LyricAgent(id = "v1", type = LyricAgent.Type.PERSON.type),
                LyricAgent(id = "v2", type = LyricAgent.Type.OTHER.type)
            ),
            lyrics = mutableListOf(
                LyricLine(agent = "v1", htmlLineText = "Left"),
                LyricLine(agent = "v2", htmlLineText = "Right")
            )
        )

        val mappedLines = AppleSongMapper.map(appleSong).lyrics.orEmpty()

        assertFalse(mappedLines[0].isAlignedRight)
        assertTrue(mappedLines[1].isAlignedRight)
    }

    @Test
    fun `keeps a song with one referenced agent on the left`() {
        val appleSong = AppleSong(
            agents = mutableListOf(
                LyricAgent(id = "v1", type = LyricAgent.Type.PERSON.type),
                LyricAgent(id = "v2", type = LyricAgent.Type.PERSON.type)
            ),
            lyrics = mutableListOf(
                LyricLine(agent = "v2", htmlLineText = "Only voice used")
            )
        )

        val mappedLine = AppleSongMapper.map(appleSong).lyrics.orEmpty().single()

        assertFalse(mappedLine.isAlignedRight)
    }

    @Test
    fun `keeps group lines neutral and resumes duet direction after them`() {
        val appleSong = AppleSong(
            agents = mutableListOf(
                LyricAgent(id = "v1", type = LyricAgent.Type.PERSON.type),
                LyricAgent(id = "v2", type = LyricAgent.Type.PERSON.type),
                LyricAgent(id = "v1000", type = LyricAgent.Type.GROUP.type)
            ),
            lyrics = mutableListOf(
                LyricLine(agent = "v1", htmlLineText = "Thought I found a way"),
                LyricLine(agent = "v1000", htmlLineText = "Oh, I hope someday"),
                LyricLine(agent = "v2", htmlLineText = "Walking out of time"),
                LyricLine(agent = "v1000", htmlLineText = "Isn't it lovely"),
                LyricLine(agent = "v1", htmlLineText = "Heart made of glass")
            )
        )

        val mappedLines = AppleSongMapper.map(appleSong).lyrics.orEmpty()

        assertFalse(mappedLines[0].isAlignedRight)
        assertFalse(mappedLines[1].isAlignedRight)
        assertTrue(mappedLines[1].metadata?.getBoolean(LyricMetadataKeys.GROUP_VOCALS) == true)
        assertTrue(mappedLines[2].isAlignedRight)
        assertFalse(mappedLines[3].isAlignedRight)
        assertTrue(mappedLines[3].metadata?.getBoolean(LyricMetadataKeys.GROUP_VOCALS) == true)
        assertFalse(mappedLines[4].isAlignedRight)
        assertFalse(mappedLines[4].metadata?.getBoolean(LyricMetadataKeys.GROUP_VOCALS) == true)
    }

    @Test
    fun `alternates direction for each agent change instead of assigning agents globally`() {
        val appleSong = AppleSong(
            agents = mutableListOf(
                LyricAgent(id = "v1", type = LyricAgent.Type.PERSON.type),
                LyricAgent(id = "v2", type = LyricAgent.Type.PERSON.type),
                LyricAgent(id = "v3", type = LyricAgent.Type.PERSON.type)
            ),
            lyrics = mutableListOf(
                LyricLine(agent = "v1", htmlLineText = "First"),
                LyricLine(agent = "v2", htmlLineText = "Second"),
                LyricLine(agent = "v3", htmlLineText = "Third")
            )
        )

        val mappedLines = AppleSongMapper.map(appleSong).lyrics.orEmpty()

        assertFalse(mappedLines[0].isAlignedRight)
        assertTrue(mappedLines[1].isAlignedRight)
        assertFalse(mappedLines[2].isAlignedRight)
    }

    @Test
    fun `starts an other agent on the right like Apple Music`() {
        val appleSong = AppleSong(
            agents = mutableListOf(
                LyricAgent(id = "v1", type = LyricAgent.Type.PERSON.type),
                LyricAgent(id = "v2", type = LyricAgent.Type.OTHER.type)
            ),
            lyrics = mutableListOf(
                LyricLine(agent = "v2", htmlLineText = "Spoken opening"),
                LyricLine(agent = "v1", htmlLineText = "Sung response")
            )
        )

        val mappedLines = AppleSongMapper.map(appleSong).lyrics.orEmpty()

        assertTrue(mappedLines[0].isAlignedRight)
        assertFalse(mappedLines[1].isAlignedRight)
    }

    @Test
    fun `preserves visual side when lyric direction changes between agents`() {
        val appleSong = AppleSong(
            agents = mutableListOf(
                LyricAgent(id = "v1", type = LyricAgent.Type.PERSON.type),
                LyricAgent(id = "v2", type = LyricAgent.Type.PERSON.type)
            ),
            lyrics = mutableListOf(
                LyricLine(agent = "v1", htmlLineText = "English line"),
                LyricLine(agent = "v2", htmlLineText = "\u05e9\u05dc\u05d5\u05dd")
            )
        )

        val mappedLines = AppleSongMapper.map(appleSong).lyrics.orEmpty()

        assertFalse(mappedLines[0].isAlignedRight)
        assertFalse(mappedLines[1].isAlignedRight)
    }
}
