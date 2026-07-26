/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.view.InterludeTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyriconDataBridgeTest {

    @After
    fun tearDown() {
        LyriconDataBridge.clearState()
    }

    @Test
    fun `refreshes when consecutive lyric lines have the same text`() {
        LyriconDataBridge.updateSong(
            Song(
                lyrics = listOf(
                    RichLyricLine(begin = 0, end = 999, text = "Repeat"),
                    RichLyricLine(begin = 1000, end = 1999, text = "Repeat")
                )
            )
        )

        assertTrue(LyriconDataBridge.updatePosition(500))
        assertEquals(0L, LyriconDataBridge.currentLyricLine?.begin)
        assertFalse(LyriconDataBridge.updatePosition(600))

        assertTrue(LyriconDataBridge.updatePosition(1500))
        assertEquals("Repeat", LyriconDataBridge.currentLyric)
        assertEquals(1000L, LyriconDataBridge.currentLyricLine?.begin)
    }

    @Test
    fun `exposes Apple style dots during an intro`() {
        LyriconDataBridge.updateSong(
            Song(
                name = "Intro song",
                lyrics = listOf(RichLyricLine(begin = 8000, end = 10_000, text = "First line"))
            )
        )

        assertTrue(LyriconDataBridge.updatePosition(1000))
        assertEquals(InterludeTracker.Type.INTRO, LyriconDataBridge.currentInterludeType)
        assertEquals("•••", LyriconDataBridge.currentLyric)
        assertTrue(
            LyriconDataBridge.currentLyricLine?.metadata
                ?.getBoolean(LyricMetadataKeys.INSTRUMENTAL) == true
        )
        assertEquals("First line", LyriconDataBridge.currentNextLyricLine?.text)

        assertTrue(LyriconDataBridge.updatePosition(8500))
        assertEquals(null, LyriconDataBridge.currentInterludeType)
        assertEquals("First line", LyriconDataBridge.currentLyric)
    }

    @Test
    fun `does not expose dots during a short intro`() {
        LyriconDataBridge.updateSong(
            Song(
                lyrics = listOf(RichLyricLine(begin = 5000, end = 7000, text = "First line"))
            )
        )

        assertTrue(LyriconDataBridge.updatePosition(1000))
        assertEquals(null, LyriconDataBridge.currentInterludeType)
        assertEquals("", LyriconDataBridge.currentLyric)
    }

    @Test
    fun `keeps the previous lyric across an ordinary multi-second gap`() {
        LyriconDataBridge.updateSong(
            Song(
                lyrics = listOf(
                    RichLyricLine(begin = 0, end = 1000, text = "Before"),
                    RichLyricLine(begin = 5000, end = 6000, text = "After")
                )
            )
        )

        assertTrue(LyriconDataBridge.updatePosition(2500))
        assertEquals(null, LyriconDataBridge.currentInterludeType)
        assertEquals("Before", LyriconDataBridge.currentLyric)
    }

    @Test
    fun `exposes Apple style dots during a seven second interlude`() {
        LyriconDataBridge.updateSong(
            Song(
                lyrics = listOf(
                    RichLyricLine(begin = 0, end = 1000, text = "Before"),
                    RichLyricLine(begin = 8000, end = 9000, text = "After")
                )
            )
        )

        assertTrue(LyriconDataBridge.updatePosition(500))
        assertTrue(LyriconDataBridge.updatePosition(2500))
        assertEquals(InterludeTracker.Type.INTERLUDE, LyriconDataBridge.currentInterludeType)
        assertEquals("•••", LyriconDataBridge.currentLyric)
        assertEquals("After", LyriconDataBridge.currentNextLyricLine?.text)
    }

    @Test
    fun `keeps simultaneous lyrics in one timeline node until both finish`() {
        val main = RichLyricLine(
            begin = 1_000,
            end = 2_000,
            text = "Main",
            isAlignedRight = false,
        )
        val overlapping = RichLyricLine(
            begin = 1_000,
            end = 2_200,
            text = "Overlapping",
            isAlignedRight = true,
        )
        LyriconDataBridge.updateSong(
            Song(
                lyrics = listOf(
                    RichLyricLine(begin = 0, end = 999, text = "Previous"),
                    main,
                    overlapping,
                    RichLyricLine(begin = 2_201, end = 3_000, text = "Next"),
                )
            )
        )

        assertTrue(LyriconDataBridge.updatePosition(500))
        assertEquals("Main", LyriconDataBridge.currentNextLyricLine?.text)
        assertEquals("Overlapping", LyriconDataBridge.currentNextLyricLine?.secondary)

        assertTrue(LyriconDataBridge.updatePosition(1_500))
        assertEquals("Main", LyriconDataBridge.currentLyricLine?.text)
        assertEquals("Overlapping", LyriconDataBridge.currentLyricLine?.secondary)
        assertTrue(
            LyriconDataBridge.currentLyricLine?.metadata?.getBoolean(
                LyricMetadataKeys.OVERLAPPING_LYRICS_GROUP
            ) == true
        )
        assertEquals("Next", LyriconDataBridge.currentNextLyricLine?.text)

        assertFalse(LyriconDataBridge.updatePosition(2_100))
        assertEquals("Main", LyriconDataBridge.currentLyricLine?.text)
        assertEquals("Overlapping", LyriconDataBridge.currentLyricLine?.secondary)

        assertTrue(LyriconDataBridge.updatePosition(2_201))
        assertEquals("Next", LyriconDataBridge.currentLyricLine?.text)
        assertEquals(null, LyriconDataBridge.currentLyricLine?.secondary)
    }

    @Test
    fun `uses the unmerged current lyric for island when next line display is disabled`() {
        val main = RichLyricLine(begin = 1_000, end = 2_000, text = "Main")
        val overlapping = RichLyricLine(begin = 1_000, end = 2_200, text = "Overlapping")
        LyriconDataBridge.updateSong(
            Song(lyrics = listOf(main, overlapping))
        )

        LyriconDataBridge.updatePosition(1_500)

        assertEquals("Main", LyriconDataBridge.currentLyricLineForIsland(true)?.text)
        assertEquals(
            "Overlapping",
            LyriconDataBridge.currentLyricLineForIsland(false)?.text
        )
        assertEquals(
            null,
            LyriconDataBridge.currentLyricLineForIsland(false)?.secondary
        )
    }

    @Test
    fun `preserves the delayed word timing of an overlapping second line`() {
        val main = RichLyricLine(
            begin = 1_000,
            end = 3_000,
            text = "Main",
            words = listOf(
                com.juren233.hyperlyricsenhanced.lyric.model.LyricWord(
                    begin = 1_000,
                    end = 3_000,
                    text = "Main"
                )
            )
        )
        val delayedSecond = RichLyricLine(
            begin = 1_800,
            end = 2_800,
            text = "Second",
            words = listOf(
                com.juren233.hyperlyricsenhanced.lyric.model.LyricWord(
                    begin = 1_800,
                    end = 2_800,
                    text = "Second"
                )
            )
        )
        LyriconDataBridge.updateSong(
            Song(
                lyrics = listOf(
                    main,
                    delayedSecond,
                    RichLyricLine(begin = 3_001, end = 4_000, text = "Next")
                )
            )
        )

        LyriconDataBridge.updatePosition(1_200)
        assertEquals("Main", LyriconDataBridge.currentLyricLine?.text)
        assertEquals("Second", LyriconDataBridge.currentLyricLine?.secondary)
        assertEquals(
            1_800L,
            LyriconDataBridge.currentLyricLine?.secondaryWords.orEmpty().single().begin
        )
        assertEquals(
            2_800L,
            LyriconDataBridge.currentLyricLine?.secondaryWords.orEmpty().single().end
        )
    }

    @Test
    fun `keeps both overlapping lyric backing vocals and translations in group metadata`() {
        val primary = RichLyricLine(
            begin = 1_000,
            end = 3_000,
            text = "First",
            translation = "第一句翻译",
            secondary = "First backing",
            metadata = com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf(
                LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION to "第一句伴唱翻译"
            )
        )
        val secondary = RichLyricLine(
            begin = 1_500,
            end = 3_200,
            text = "Second",
            translation = "第二句翻译",
            secondary = "Second backing",
            metadata = com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf(
                LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION to "第二句伴唱翻译"
            )
        )
        LyriconDataBridge.updateSong(Song(lyrics = listOf(primary, secondary)))

        LyriconDataBridge.updatePosition(2_000)
        val line = LyriconDataBridge.currentLyricLine
        val metadata = line?.metadata
        assertEquals("First", line?.text)
        assertEquals("Second", line?.secondary)
        assertEquals("第一句翻译", line?.translation)
        assertEquals(
            "第二句翻译",
            metadata?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
        )
        assertEquals(
            "First backing",
            metadata?.getString(LyricMetadataKeys.OVERLAPPING_PRIMARY_BACKING)
        )
        assertEquals(
            "第一句伴唱翻译",
            metadata?.getString(
                LyricMetadataKeys.OVERLAPPING_PRIMARY_BACKING_TRANSLATION
            )
        )
        assertEquals(
            "Second backing",
            metadata?.getString(LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING)
        )
        assertEquals(
            "第二句伴唱翻译",
            metadata?.getString(
                LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING_TRANSLATION
            )
        )
    }

    @Test
    fun `leaves ordinary sequential lyrics as separate timeline nodes`() {
        val first = RichLyricLine(begin = 0, end = 999, text = "First")
        val second = RichLyricLine(begin = 1_000, end = 1_999, text = "Second")
        val third = RichLyricLine(begin = 2_000, end = 3_000, text = "Third")
        LyriconDataBridge.updateSong(
            Song(lyrics = listOf(first, second, third))
        )

        LyriconDataBridge.updatePosition(500)
        assertEquals("First", LyriconDataBridge.currentLyricLine?.text)
        assertEquals(null, LyriconDataBridge.currentLyricLine?.secondary)
        assertEquals("Second", LyriconDataBridge.currentNextLyricLine?.text)

        LyriconDataBridge.updatePosition(1_500)
        assertEquals("Second", LyriconDataBridge.currentLyricLine?.text)
        assertEquals(null, LyriconDataBridge.currentLyricLine?.secondary)
        assertEquals("Third", LyriconDataBridge.currentNextLyricLine?.text)
    }

    @Test
    fun `ignores a stale overlapping callback after advancing to the next group`() {
        val main = RichLyricLine(begin = 1_000, end = 2_000, text = "Main")
        val overlapping = RichLyricLine(begin = 1_200, end = 2_200, text = "Overlapping")
        val next = RichLyricLine(begin = 2_201, end = 3_000, text = "Next")
        LyriconDataBridge.updateSong(
            Song(lyrics = listOf(main, overlapping, next))
        )

        LyriconDataBridge.updateLyricLine(overlapping)
        assertEquals("Main", LyriconDataBridge.currentLyricLine?.text)
        assertEquals("Overlapping", LyriconDataBridge.currentLyricLine?.secondary)

        LyriconDataBridge.updatePosition(2_201)
        assertEquals("Next", LyriconDataBridge.currentLyricLine?.text)

        LyriconDataBridge.updateLyricLine(overlapping)
        assertEquals("Next", LyriconDataBridge.currentLyricLine?.text)
    }

    @Test
    fun `does not return to older long lines after a third overlapping line starts`() {
        val first = RichLyricLine(begin = 1_000, end = 10_000, text = "A")
        val second = RichLyricLine(begin = 2_000, end = 5_000, text = "B")
        val third = RichLyricLine(begin = 3_000, end = 6_000, text = "C")
        val fourth = RichLyricLine(begin = 10_001, end = 11_000, text = "D")
        LyriconDataBridge.updateSong(
            Song(lyrics = listOf(first, second, third, fourth))
        )

        LyriconDataBridge.updatePosition(2_500)
        assertEquals("A", LyriconDataBridge.currentLyricLine?.text)
        assertEquals("B", LyriconDataBridge.currentLyricLine?.secondary)

        LyriconDataBridge.updatePosition(3_500)
        assertEquals("C", LyriconDataBridge.currentLyricLine?.text)
        assertEquals(null, LyriconDataBridge.currentLyricLine?.secondary)
        assertEquals(
            "C",
            LyriconDataBridge.currentLyricLineForIsland(false)?.text
        )

        LyriconDataBridge.updatePosition(6_500)
        assertEquals("C", LyriconDataBridge.currentLyricLine?.text)
        assertEquals(
            "C",
            LyriconDataBridge.currentLyricLineForIsland(false)?.text
        )

        LyriconDataBridge.updateLyricLine(first)
        assertEquals("C", LyriconDataBridge.currentLyricLine?.text)
        LyriconDataBridge.updateLyricLine(second)
        assertEquals("C", LyriconDataBridge.currentLyricLine?.text)

        LyriconDataBridge.updatePosition(10_001)
        assertEquals("D", LyriconDataBridge.currentLyricLine?.text)
    }

    @Test
    fun `does not merge adjacent lyric callbacks at a shared boundary`() {
        val previous = RichLyricLine(begin = 0, end = 1_000, text = "Previous")
        val next = RichLyricLine(begin = 1_000, end = 2_000, text = "Next")
        LyriconDataBridge.updateSong(Song(lyrics = listOf(previous, next)))

        LyriconDataBridge.updateLyricLine(previous)
        assertEquals("Previous", LyriconDataBridge.currentLyricLine?.text)
        assertEquals(null, LyriconDataBridge.currentLyricLine?.secondary)

        LyriconDataBridge.updateLyricLine(next)
        assertEquals("Next", LyriconDataBridge.currentLyricLine?.text)
        assertEquals(null, LyriconDataBridge.currentLyricLine?.secondary)
    }

}
