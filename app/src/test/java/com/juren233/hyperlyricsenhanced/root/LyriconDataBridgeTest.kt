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

}
