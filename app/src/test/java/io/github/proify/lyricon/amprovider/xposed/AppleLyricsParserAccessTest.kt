/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.parser.AppleLyricsParserAccess
import io.github.proify.lyricon.amprovider.xposed.parser.AppleSongParser
import org.junit.Assert.assertEquals
import org.junit.Test

class AppleLyricsParserAccessTest {

    @Test
    fun `parses native lyric model through profiled runtime members`() {
        val resolver = AppleMusicHookResolver(
            version = AppleMusicVersion("6.5.1", 1583L),
            classLookup = { name ->
                if (
                    name == "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel"
                ) {
                    FakePlayerLyricsViewModel::class.java
                } else {
                    throw ClassNotFoundException(name)
                }
            },
        )

        val song = AppleSongParser.parser(
            FakeSong(
                agents = FakePointerVector(
                    listOf(FakePointer(FakeAgent(intArrayOf(1), 1L, "agent-1")))
                ),
                sections = FakePointerVector(
                    listOf(
                        FakePointer(
                            FakeSection(
                                lines = FakePointerVector(
                                    listOf(
                                        FakePointer(
                                            FakeLine(
                                                words = FakePointerVector(
                                                    listOf(
                                                        FakePointer(
                                                            FakeWord("word", 100, 200),
                                                        )
                                                    )
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
            ),
            AppleLyricsParserAccess.from(resolver),
        )

        assertEquals("1234567890", song.adamId)
        assertEquals(1, song.agents.size)
        assertEquals("agent-1", song.agents.single().id)
        assertEquals(listOf("ja-Jpan"), song.pronunciationLanguages)
        assertEquals(1, song.lyrics.size)
        assertEquals("main", song.lyrics.single().htmlLineText)
        assertEquals("translated", song.lyrics.single().htmlTranslationLineText)
        assertEquals("word", song.lyrics.single().words.single().text)
        assertEquals(100, song.lyrics.single().words.single().begin)
        assertEquals(200, song.lyrics.single().words.single().end)
    }

    private class FakePlayerLyricsViewModel {
        @Suppress("UNUSED_PARAMETER")
        fun loadLyrics(song: Any) = Unit
    }

    private class FakeSong(
        private val agents: FakePointerVector,
        private val sections: FakePointerVector,
    ) {
        fun getAdamId(): String = "1234567890"
        fun getAgents(): FakePointerVector = agents
        fun getDuration(): Int = 321
        fun getPronunciationLanguages(): FakeStringVector =
            FakeStringVector(listOf("ja-Jpan"))
        fun getSections(): FakePointerVector = sections
    }

    private class FakeSection(
        private val lines: FakePointerVector,
    ) {
        fun getAgent(): String = "section-agent"
        fun getBegin(): Int = 0
        fun getEnd(): Int = 500
        fun getDuration(): Int = 500
        fun getLines(): FakePointerVector = lines
    }

    private class FakeLine(
        private val words: FakePointerVector,
    ) {
        fun getAgent(): String = "line-agent"
        fun getBegin(): Int = 0
        fun getEnd(): Int = 500
        fun getDuration(): Int = 500
        fun getHtmlLineText(): String = "main"
        fun getWords(): FakePointerVector = words
        fun getHtmlTranslationLineText(): String = "translated"
        fun getBackgroundWords(includeEmpty: Boolean): FakePointerVector =
            FakePointerVector(emptyList())
        fun getHtmlBackgroundVocalsLineText(): String = "background"
        fun getHtmlTranslatedBackgroundVocalsLineText(): String = "translated-background"
        fun getHtmlPronunciationLineText(): String = "pronunciation"
        fun getHtmlPronunciationBackgroundVocalsLineText(): String = "pronunciation-background"
    }

    private class FakeWord(
        private val text: String,
        private val begin: Int,
        private val end: Int,
    ) {
        fun getAgent(): String = "word-agent"
        fun getBegin(): Int = begin
        fun getEnd(): Int = end
        fun getDuration(): Int = end - begin
        fun getHtmlLineText(): String = text
    }

    private class FakeAgent(
        private val nameTypes: IntArray,
        private val type: Long,
        private val id: String,
    ) {
        fun getNameTypes_(): IntArray = nameTypes
        fun getType_(): Long = type
        fun getId(): String = id
    }

    private class FakePointer(private val value: Any) {
        fun get(): Any = value
    }

    private class FakePointerVector(private val values: List<FakePointer>) {
        fun size(): Long = values.size.toLong()
        fun get(index: Long): FakePointer = values[index.toInt()]
    }

    private class FakeStringVector(private val values: List<String>) {
        fun size(): Long = values.size.toLong()
        fun get(index: Int): String = values[index]
    }
}
