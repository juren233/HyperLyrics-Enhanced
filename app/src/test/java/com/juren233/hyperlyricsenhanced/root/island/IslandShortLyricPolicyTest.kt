/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.view.LyricLineAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandShortLyricPolicyTest {
    @Test
    fun `only enabled full island mode permits song info`() {
        for (mode in listOf(RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE,
            RootConstants.HOOK_LYRIC_MODE_SEPARATED)) {
            assertFalse(IslandShortLyricPolicy.usesSongInfo(mode, true, 50, 100))
        }
        assertFalse(IslandShortLyricPolicy.usesSongInfo(
            RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND, false, 50, 100))
        assertTrue(fits(50, 100))
    }

    @Test
    fun `exact fit stays on right but even one pixel overflow restores whole island`() {
        assertTrue(fits(200, 200))
        assertFalse(fits(201, 200))
        assertFalse(fits(500, 200))
    }

    @Test
    fun `unlaid out slots and empty content cannot select short lyric mode`() {
        assertFalse(fits(50, 0))
        assertFalse(fits(0, 200))
        assertFalse(fits(-1, 200))
    }

    @Test
    fun `line changes seek and viewport resize never retain previous choice`() {
        assertEquals(listOf(true, false, true, false, true), listOf(
            fits(150, 200), fits(350, 200), fits(150, 200),
            fits(150, 140), fits(150, 200),
        ))
    }

    @Test
    fun `visible translation can force full island even when main lyric fits`() {
        val assembler = LyricLineAssembler(displayMode = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION)
        val line = RichLyricLine(text = "Hi", translation = "A much longer translation")
        val width = assembler.measureVisibleContentWidth(line, { 30 }, { 230 })
        assertEquals(230, width)
        assertFalse(fits(width, 200))
    }

    @Test
    fun `hidden translation is excluded and each row uses its own font metrics`() {
        val line = RichLyricLine(text = "Hi", translation = "A much longer translation")
        val hidden = LyricLineAssembler(displayMode = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF)
        assertEquals(30, hidden.measureVisibleContentWidth(line, { 30 }, { error("Hidden row measured") }))
        val visible = LyricLineAssembler(displayMode = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION)
        assertTrue(fits(visible.measureVisibleContentWidth(line, { 170 }, { 160 }), 180))
        assertFalse(fits(visible.measureVisibleContentWidth(line, { 190 }, { 160 }), 180))
    }

    @Test
    fun `backing vocals remain included when translation display is off`() {
        val assembler = LyricLineAssembler(displayMode = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF)
        val line = RichLyricLine(text = "Hi", secondary = "Long backing vocals")
        assertFalse(fits(assembler.measureVisibleContentWidth(line, { 30 }, { 230 }), 200))
    }

    @Test
    fun `pronunciation fallback uses exactly the row that will be bound`() {
        val assembler = LyricLineAssembler(
            displayMode = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION,
            fallback = true,
        )
        val line = RichLyricLine(text = "Hi", roma = "pronunciation")
        val width = assembler.measureVisibleContentWidth(line, { 30 }, {
            assertEquals("pronunciation", it.text)
            230
        })
        assertFalse(fits(width, 200))
    }

    private fun fits(content: Int, viewport: Int) = IslandShortLyricPolicy.usesSongInfo(
        RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND, true, content, viewport,
    )
}
