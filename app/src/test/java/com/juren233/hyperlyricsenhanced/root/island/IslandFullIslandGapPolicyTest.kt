/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.view.LyricLineAssembler
import com.juren233.hyperlyricsenhanced.lyric.view.isTitleLine
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandFullIslandGapPolicyTest {
    @After
    fun tearDown() {
        LyriconDataBridge.clearState()
    }

    @Test
    fun `short intro keeps title artist placeholder on right until exact first begin`() {
        loadSong(firstBegin = 5_000)
        LyriconDataBridge.updatePosition(4_999)

        assertIndependentSlots()
        assertTrue(LyriconDataBridge.currentLyricLine.isTitleLine())
        assertEquals("Song - Artist", LyriconDataBridge.currentLyricLine?.text)

        LyriconDataBridge.updatePosition(5_000)
        assertContinuousLyrics()
        assertEquals("First lyric", LyriconDataBridge.currentLyricLine?.text)
    }

    @Test
    fun `long intro keeps timed dots on right then restores whole island`() {
        loadSong(firstBegin = 8_000)
        LyriconDataBridge.updatePosition(0)
        assertIndependentSlots()
        val indicator = LyriconDataBridge.currentLyricLine!!
        assertEquals("•••", indicator.text)
        assertTrue(indicator.metadata!!.getBoolean(LyricMetadataKeys.INSTRUMENTAL))
        assertEquals(0L, indicator.begin)
        assertEquals(7_999L, indicator.end)

        LyriconDataBridge.updatePosition(7_999)
        assertIndependentSlots()
        LyriconDataBridge.updatePosition(8_000)
        assertContinuousLyrics()
    }

    @Test
    fun `interlude enters independent slots and exits at next lyric`() {
        LyriconDataBridge.updateSong(
            Song(name = "Song", artist = "Artist", lyrics = listOf(
                RichLyricLine(begin = 0, end = 1_000, text = "Before"),
                RichLyricLine(begin = 8_000, end = 10_000, text = "After")
            ))
        )
        LyriconDataBridge.updatePosition(1_000)
        assertContinuousLyrics()
        LyriconDataBridge.updatePosition(1_001)
        assertIndependentSlots()
        assertEquals("•••", LyriconDataBridge.currentLyricLine?.text)
        assertEquals("interlude", LyriconDataBridge.currentLyricLine?.metadata
            ?.getString(LyricMetadataKeys.INSTRUMENTAL_TYPE))
        LyriconDataBridge.updatePosition(8_000)
        assertContinuousLyrics()
        assertEquals("After", LyriconDataBridge.currentLyricLine?.text)
    }

    @Test
    fun `backward seek and switching tracks reevaluate gap without retained state`() {
        loadSong(firstBegin = 5_000)
        LyriconDataBridge.updatePosition(6_000)
        assertContinuousLyrics()
        LyriconDataBridge.updatePosition(0)
        assertIndependentSlots()
        loadSong(firstBegin = 0)
        LyriconDataBridge.updatePosition(0)
        assertContinuousLyrics()
        loadSong(firstBegin = 8_000)
        LyriconDataBridge.updatePosition(0)
        assertIndependentSlots()
    }

    @Test
    fun `waiting for lyrics still reserves left for song information`() {
        LyriconDataBridge.clearState()
        assertIndependentSlots()
        loadSong(firstBegin = 0)
        LyriconDataBridge.updatePosition(0)
        assertContinuousLyrics()
    }

    @Test
    fun `ordinary short gaps and literal title lyrics remain continuous`() {
        LyriconDataBridge.updateSong(
            Song(name = "Song", artist = "Artist", lyrics = listOf(
                RichLyricLine(begin = 0, end = 1_000, text = "Song - Artist"),
                RichLyricLine(begin = 5_000, end = 6_000, text = "After")
            ))
        )
        LyriconDataBridge.updatePosition(0)
        assertContinuousLyrics()
        LyriconDataBridge.updatePosition(3_000)
        assertContinuousLyrics()
    }

    @Test
    fun `single side mode keeps its existing gap presentation`() {
        loadSong(firstBegin = 8_000)
        LyriconDataBridge.updatePosition(0)
        val mode = RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE
        assertFalse(IslandFullIslandGapPolicy.usesIndependentSlots(
            mode, LyriconDataBridge.currentLyricLine))
        assertFalse(IslandFullIslandGapPolicy.usesIndependentSlots(mode, null))
    }

    @Test
    fun `left song information retains artist even when translations are off`() {
        val line = IslandSlotContentAssembler.buildMetadataLine(5, "Song", "Artist", "Album")!!
        val assembler = LyricLineAssembler(
            displayMode = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
            hideSecondaryContent = true
        )
        assertEquals("Song", assembler.buildMain(line).line.text)
        assertEquals("Artist", assembler.buildSecondary(line).line.text)
        assertTrue(assembler.buildSecondary(line).alwaysShow)
    }

    private fun loadSong(firstBegin: Long) {
        LyriconDataBridge.updateSong(Song(
            name = "Song", artist = "Artist", lyrics = listOf(
                RichLyricLine(begin = firstBegin, end = firstBegin + 2_000, text = "First lyric")
            )
        ))
    }

    private fun assertIndependentSlots() {
        for (mode in dualSlotModes) {
            val independent = independentSlots(mode)
            assertTrue("mode=$mode must show song information during gaps", independent)
            assertEquals(5, IslandFullIslandGapPolicy.contentMode(7, true, independent))
            assertEquals(7, IslandFullIslandGapPolicy.contentMode(7, false, independent))
        }
    }

    private fun assertContinuousLyrics() {
        for (mode in dualSlotModes) {
            val independent = independentSlots(mode)
            assertFalse("mode=$mode must restore lyrics on both sides", independent)
            assertEquals(7, IslandFullIslandGapPolicy.contentMode(7, true, independent))
            assertEquals(7, IslandFullIslandGapPolicy.contentMode(7, false, independent))
        }
    }

    private val dualSlotModes = listOf(
        RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND,
        RootConstants.HOOK_LYRIC_MODE_SEPARATED,
    )

    private fun independentSlots(mode: Int) = IslandFullIslandGapPolicy.usesIndependentSlots(
        mode,
        LyriconDataBridge.currentLyricLine
    )
}
