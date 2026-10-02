/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.IslandNextLineMode
import com.juren233.hyperlyricsenhanced.common.lyric.RichLyricLineSplitter
import com.juren233.hyperlyricsenhanced.lyric.view.METADATA_NEXT_LINE_RIGHT_TEXT
import com.juren233.hyperlyricsenhanced.lyric.view.METADATA_NEXT_LINE_PREVIEW_CENTERED
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.view.LyricLineAssembler
import com.juren233.hyperlyricsenhanced.lyric.view.canAnimateNextLinePromotion
import com.juren233.hyperlyricsenhanced.lyric.view.hasLyricLineAdvanced
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandNextLinePreviewTest {
    @Test
    fun `new installations default off and legacy enabled migrates to second row`() {
        assertEquals(IslandNextLineMode.OFF, IslandNextLineMode.resolve(-1, false))
        assertEquals(IslandNextLineMode.SECOND_LINE, IslandNextLineMode.resolve(-1, true))
        assertEquals(IslandNextLineMode.OFF, IslandNextLineMode.resolve(99, true))
    }

    @Test
    fun `right preview keeps current timing and one row and refreshes without advancing`() {
        val prefs = preferences()
        val current = RichLyricLine(begin = 1000, end = 2000, duration = 1000, text = "Current",
            words = listOf(LyricWord(begin = 1000, end = 2000, duration = 1000, text = "Current")))
        LyriconDataBridge.currentLyricLine = current
        LyriconDataBridge.currentNextLyricLine = RichLyricLine(begin = 2000, end = 4000, text = "Next")
        var signature = IslandSlotRuntimeConfig.from(prefs).styleSignature
        for (mode in listOf(IslandNextLineMode.RIGHT, IslandNextLineMode.OFF, IslandNextLineMode.SECOND_LINE)) {
            IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_ISLAND_NEXT_LINE_MODE, mode)
            val config = IslandSlotRuntimeConfig.from(prefs)
            assertTrue(signature != config.styleSignature)
            signature = config.styleSignature
            val line = IslandSlotContentAssembler.processedRawLine(prefs, config)!!
            val main = LyricLineAssembler().buildMain(line).line
            assertEquals(current.begin, main.begin)
            assertEquals(current.end, main.end)
            assertEquals(current.text, main.text)
            assertEquals(current.words, main.words)
            if (mode == IslandNextLineMode.RIGHT) {
                assertEquals("Next", main.metadata?.get(METADATA_NEXT_LINE_RIGHT_TEXT))
                assertFalse(secondary(prefs).alwaysShow)
            } else {
                assertTrue(main.metadata?.get(METADATA_NEXT_LINE_RIGHT_TEXT).isNullOrBlank())
                assertEquals(mode == IslandNextLineMode.SECOND_LINE, secondary(prefs).alwaysShow)
            }
        }
    }

    @Test
    fun `right preview clears on missing next and yields to arriving translation`() {
        val prefs = preferences()
        IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_ISLAND_NEXT_LINE_MODE, IslandNextLineMode.RIGHT)
        fun preview() = IslandSlotContentAssembler.processedRawLine(prefs, IslandSlotRuntimeConfig.from(prefs))
            ?.metadata?.get(METADATA_NEXT_LINE_RIGHT_TEXT)
        LyriconDataBridge.currentLyricLine = RichLyricLine(text = "Current")
        LyriconDataBridge.currentNextLyricLine = RichLyricLine(text = "Next")
        assertEquals("Next", preview())
        LyriconDataBridge.currentNextLyricLine = null
        assertTrue(preview().isNullOrBlank())
        LyriconDataBridge.currentNextLyricLine = RichLyricLine(text = "Following")
        assertEquals("Following", preview())
        LyriconDataBridge.currentLyricLine = RichLyricLine(text = "Current", translation = "Translation")
        assertTrue(preview().isNullOrBlank())
        assertEquals("Translation", secondary(prefs).line.text)
        LyriconDataBridge.currentLyricLine = RichLyricLine(text = "Current")
        assertEquals("Following", secondary(preferences(mode = RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE)).line.text)
    }

    @After
    fun tearDown() {
        LyriconDataBridge.clearState()
        IslandRuntimePreferenceOverrides.clear()
    }

    @Test
    fun `right preview follows the CJK space preference without changing the source`() {
        IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_ISLAND_NEXT_LINE_MODE, IslandNextLineMode.RIGHT)
        LyriconDataBridge.currentLyricLine = RichLyricLine(text = "Current")
        LyriconDataBridge.currentNextLyricLine = RichLyricLine(text = "下 一 句")
        for (removeSpaces in listOf(false, true)) {
            val prefs = preferences(removeSpaces = removeSpaces)
            val line = IslandSlotContentAssembler.processedRawLine(prefs, IslandSlotRuntimeConfig.from(prefs))!!
            assertEquals(if (removeSpaces) "下一句" else "下 一 句", line.metadata?.get(METADATA_NEXT_LINE_RIGHT_TEXT))
            assertEquals("下 一 句", LyriconDataBridge.currentNextLyricLine?.text)
        }
    }

    @Test
    fun `right style lands left aligned while second row preserves the source duet alignment`() {
        val source = RichLyricLine(text = "Current", isAlignedRight = true)
        val next = RichLyricLine(text = "Next")
        assertFalse(source.withNextLinePreview(next, false, onRight = true).isAlignedRight)
        assertTrue(source.withNextLinePreview(next, false, onRight = false).isAlignedRight)
        assertTrue(source.isAlignedRight)
    }

    @Test
    fun `all island modes render an untimed second row preview`() {
        for (mode in listOf(RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND,
            RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE, RootConstants.HOOK_LYRIC_MODE_SEPARATED)) {
            for (source in listOf("lyricon", "lyricinfo")) {
                val prefs = preferences(mode = mode, source = source)
                LyriconDataBridge.currentLyricLine = RichLyricLine(begin = 1_000, end = 2_000, text = "Current")
                LyriconDataBridge.currentNextLyricLine = RichLyricLine(begin = 2_000, end = 3_000, text = "Next")

                val secondary = secondary(prefs)

                assertEquals("Next", secondary.line.text)
                assertTrue(secondary.alwaysShow)
                assertTrue(secondary.isNextLinePreview)
                assertTrue(secondary.line.words.isNullOrEmpty())
            }
        }
    }

    @Test
    fun `translation arrival and line switching replace preview then restore it`() {
        val prefs = preferences()
        val untranslated = RichLyricLine(text = "Current")
        LyriconDataBridge.currentNextLyricLine = RichLyricLine(text = "Next")
        LyriconDataBridge.currentLyricLine = untranslated
        assertEquals("Next", secondary(prefs).line.text)

        LyriconDataBridge.currentLyricLine = RichLyricLine(text = "Current", translation = "Translation")
        assertEquals("Translation", secondary(prefs).line.text)
        assertFalse(secondary(prefs).isNextLinePreview)

        LyriconDataBridge.currentLyricLine = RichLyricLine(text = "Next")
        LyriconDataBridge.currentNextLyricLine = RichLyricLine(text = "Following")
        assertEquals("Following", secondary(prefs).line.text)
        assertTrue(secondary(prefs).isNextLinePreview)
    }

    @Test
    fun `backing vocals keep their second row in full island mode`() {
        val prefs = preferences()
        LyriconDataBridge.currentLyricLine = RichLyricLine(text = "Current", secondary = "Backing")
        LyriconDataBridge.currentNextLyricLine = RichLyricLine(text = "Next")
        assertEquals("Backing", secondary(prefs).line.text)
        assertFalse(secondary(prefs).isNextLinePreview)
    }

    @Test
    fun `live toggle removes and restores full island preview without advancing line`() {
        val prefs = preferences()
        LyriconDataBridge.currentLyricLine = RichLyricLine(text = "Current")
        LyriconDataBridge.currentNextLyricLine = RichLyricLine(text = "Next")
        assertTrue(secondary(prefs).alwaysShow)
        IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_NEXT_LYRIC_LINE, false)
        assertFalse(secondary(prefs).alwaysShow)
        IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_NEXT_LYRIC_LINE, true)
        assertEquals("Next", secondary(prefs).line.text)
    }

    @Test
    fun `missing or blank next line leaves no stale preview`() {
        val prefs = preferences()
        LyriconDataBridge.currentLyricLine = RichLyricLine(text = "Current")
        LyriconDataBridge.currentNextLyricLine = RichLyricLine(text = "Next")
        assertTrue(secondary(prefs).alwaysShow)
        for (next in listOf(null, RichLyricLine(text = " "))) {
            LyriconDataBridge.currentNextLyricLine = next
            assertFalse(secondary(prefs).alwaysShow)
        }
    }

    @Test
    fun `unsupported source and text mode do not show preview`() {
        LyriconDataBridge.currentLyricLine = RichLyricLine(text = "Current")
        LyriconDataBridge.currentNextLyricLine = RichLyricLine(text = "Next")
        assertFalse(secondary(preferences(source = "text")).alwaysShow)
        assertFalse(secondary(preferences(mode = RootConstants.HOOK_LYRIC_MODE_SEPARATED, source = "text")).alwaysShow)
        LyriconDataBridge.isTextMode = true
        assertFalse(secondary(preferences()).alwaysShow)
        assertFalse(secondary(preferences(mode = RootConstants.HOOK_LYRIC_MODE_SEPARATED)).alwaysShow)
    }

    @Test
    fun `separated toggle always selects second row regardless of stored full island style`() {
        val prefs = preferences(mode = RootConstants.HOOK_LYRIC_MODE_SEPARATED)
        LyriconDataBridge.currentLyricLine = RichLyricLine(text = "Current")
        LyriconDataBridge.currentNextLyricLine = RichLyricLine(text = "Next")
        for (fullStyle in listOf(IslandNextLineMode.OFF, IslandNextLineMode.SECOND_LINE, IslandNextLineMode.RIGHT)) {
            IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_ISLAND_NEXT_LINE_MODE, fullStyle)
            var previousSignature: String? = null
            for (enabled in listOf(true, false, true)) {
                IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_NEXT_LYRIC_LINE, enabled)
                val config = IslandSlotRuntimeConfig.from(prefs)
                val line = IslandSlotContentAssembler.processedRawLine(prefs, config)!!
                assertEquals(enabled, config.nextLineEnabled)
                assertFalse(config.nextLineOnRight)
                assertEquals(fullStyle, config.fullIslandNextLineMode)
                assertTrue(line.metadata?.get(METADATA_NEXT_LINE_RIGHT_TEXT).isNullOrBlank())
                assertEquals(enabled, secondary(prefs).alwaysShow)
                assertEquals(enabled, secondary(prefs).isNextLinePreview)
                assertTrue(previousSignature != config.styleSignature)
                previousSignature = config.styleSignature
            }
        }
    }

    @Test
    fun `separated preview yields to real secondary content and handles late arrival and switching`() {
        val prefs = preferences(mode = RootConstants.HOOK_LYRIC_MODE_SEPARATED, translationFallback = true)
        LyriconDataBridge.currentLyricLine = RichLyricLine(text = "Current")
        LyriconDataBridge.currentNextLyricLine = null
        assertFalse(secondary(prefs).alwaysShow)
        LyriconDataBridge.currentNextLyricLine = RichLyricLine(text = "Next")
        assertEquals("Next", secondary(prefs).line.text)
        for (line in listOf(
            RichLyricLine(text = "Current", translation = "Translation"),
            RichLyricLine(text = "Current", roma = "Pronunciation"),
            RichLyricLine(text = "Current", secondary = "Backing vocals"),
        )) {
            LyriconDataBridge.currentLyricLine = line
            assertFalse(secondary(prefs).isNextLinePreview)
            assertTrue(secondary(prefs).alwaysShow)
        }
        LyriconDataBridge.currentLyricLine = RichLyricLine(text = "Next")
        LyriconDataBridge.currentNextLyricLine = RichLyricLine(text = "Following")
        assertEquals("Following", secondary(prefs).line.text)
        assertTrue(secondary(prefs).isNextLinePreview)
        LyriconDataBridge.currentNextLyricLine = RichLyricLine(text = " ")
        assertFalse(secondary(prefs).alwaysShow)
    }

    @Test
    fun `separated preview halves match their promoted main text and remain untimed`() {
        val current = RichLyricLine(begin = 1000, end = 2000, text = "当前歌词")
        val next = RichLyricLine(begin = 2000, end = 4000, text = "下一句歌词更长")
        val currentParts = RichLyricLineSplitter.SplitLineResult(
            RichLyricLine(begin = 1000, end = 1500, text = "当前"),
            RichLyricLine(begin = 1500, end = 2000, text = "歌词"),
        )
        val nextParts = RichLyricLineSplitter.SplitLineResult(
            RichLyricLine(begin = 2000, end = 3000, text = "下一句"),
            RichLyricLine(begin = 3000, end = 4000, text = "歌词更长"),
        )
        val measured = mutableListOf<String?>()
        val previewParts = IslandSlotContentAssembler.splitSeparatedLyricLine(
            current.withNextLinePreview(next, false),
        ) { line ->
            measured += line.text
            assertTrue(line.secondary.isNullOrEmpty())
            when (line.text) {
                current.text -> currentParts
                next.text -> nextParts
                else -> error("Unexpected split input")
            }
        }
        assertEquals(listOf(current.text, next.text), measured)
        val assembler = LyricLineAssembler(secondaryTextUnitProgress = true)
        for ((preview, target) in listOf(previewParts.left to nextParts.left, previewParts.right to nextParts.right)) {
            val secondary = assembler.buildSecondary(preview)
            assertEquals(target.text, secondary.line.text)
            assertTrue(secondary.line.words.isNullOrEmpty())
            assertTrue(canAnimateNextLinePromotion(
                wasPreview = secondary.isNextLinePreview,
                currentMainText = preview.text,
                previewText = secondary.line.text,
                nextMainText = target.text,
                lineAdvanced = hasLyricLineAdvanced(preview, target),
                attached = true, mainHeight = 30, secondaryHeight = 20,
            ))
        }
        assertEquals(currentParts.left.begin, previewParts.left.begin)
        assertEquals(currentParts.left.end, previewParts.left.end)
        assertEquals(currentParts.right.begin, previewParts.right.begin)
        assertEquals(currentParts.right.end, previewParts.right.end)
    }

    @Test
    fun `separated preview preserves each side position through splitting and promotion`() {
        val prefs = preferences(mode = RootConstants.HOOK_LYRIC_MODE_SEPARATED)
        LyriconDataBridge.currentLyricLine = RichLyricLine(begin = 1000, end = 2000, text = "当前歌词")
        LyriconDataBridge.currentNextLyricLine = RichLyricLine(begin = 2000, end = 4000, text = "下句歌词")
        for ((leftPosition, rightPosition) in listOf(
            RootConstants.ISLAND_LYRIC_POSITION_CENTER to RootConstants.ISLAND_LYRIC_POSITION_RIGHT,
            RootConstants.ISLAND_LYRIC_POSITION_RIGHT to RootConstants.ISLAND_LYRIC_POSITION_CENTER,
            RootConstants.ISLAND_LYRIC_POSITION_DEFAULT to RootConstants.ISLAND_LYRIC_POSITION_DEFAULT,
        )) {
            IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_ISLAND_LEFT_LYRIC_POSITION, leftPosition)
            IslandRuntimePreferenceOverrides.put(RootConstants.KEY_HOOK_ISLAND_RIGHT_LYRIC_POSITION, rightPosition)
            val config = IslandSlotRuntimeConfig.from(prefs)
            for (isLeft in listOf(true, false)) {
                val raw = IslandSlotContentAssembler.processedRawLine(prefs, config, isLeft)!!
                val parts = IslandSlotContentAssembler.splitSeparatedLyricLine(raw) { line ->
                    RichLyricLineSplitter.SplitLineResult(
                        RichLyricLine(text = line.text!!.take(2)),
                        RichLyricLine(text = line.text!!.drop(2)),
                    )
                }
                val slot = if (isLeft) parts.left else parts.right
                val position = if (isLeft) leftPosition else rightPosition
                val centered = slot.metadata?.getBoolean(METADATA_NEXT_LINE_PREVIEW_CENTERED) == true
                assertEquals(position == RootConstants.ISLAND_LYRIC_POSITION_CENTER, centered)
                assertEquals(config.centerLyric(isLeft), centered)
                assertEquals(position == RootConstants.ISLAND_LYRIC_POSITION_RIGHT,
                    config.rightAlignLyric(isLeft) && !centered)
                val secondary = LyricLineAssembler(secondaryTextUnitProgress = true).buildSecondary(slot)
                assertEquals(if (isLeft) "下句" else "歌词", secondary.line.text)
                assertTrue(secondary.isNextLinePreview)
                assertTrue(secondary.line.words.isNullOrEmpty())
            }
        }
    }

    @Test
    fun `unsplit next word preserves its eventual alignment and leaves right preview empty`() {
        val current = RichLyricLine(text = "Current")
        val next = RichLyricLine(text = "Unbreakable", isAlignedRight = true)
        val result = IslandSlotContentAssembler.splitSeparatedLyricLine(
            current.withNextLinePreview(next, false),
        ) { line -> RichLyricLineSplitter.SplitLineResult(line, RichLyricLine()) }
        val assembler = LyricLineAssembler(secondaryTextUnitProgress = true)
        val left = assembler.buildSecondary(result.left)
        assertEquals(next.text, left.line.text)
        assertEquals(next.isAlignedRight, left.line.isAlignedRight)
        assertTrue(left.line.words.isNullOrEmpty())
        assertFalse(assembler.buildSecondary(result.right).alwaysShow)
    }

    @Test
    fun `separated missing preview clears both slots without changing ordinary secondary splitting`() {
        val ordinary = RichLyricLine(text = "Current", secondary = "Backing vocals")
        val parts = RichLyricLineSplitter.SplitLineResult(ordinary, RichLyricLine(text = "Right"))
        assertSame(parts, IslandSlotContentAssembler.splitSeparatedLyricLine(ordinary) { parts })

        val withoutNext = ordinary.withNextLinePreview(null, false)
        var splitCount = 0
        val result = IslandSlotContentAssembler.splitSeparatedLyricLine(withoutNext) {
            splitCount++
            parts
        }
        assertEquals(1, splitCount)
        for (slot in listOf(result.left, result.right)) {
            assertTrue(slot.secondary.isNullOrEmpty())
            assertFalse(LyricLineAssembler().buildSecondary(slot).alwaysShow)
        }
    }

    private fun secondary(prefs: SharedPreferences): LyricLineAssembler.SecondaryResult {
        val config = IslandSlotRuntimeConfig.from(prefs)
        val left = IslandSlotContentAssembler.processedRawLine(prefs, config, isLeft = true)
        val right = IslandSlotContentAssembler.processedRawLine(prefs, config, isLeft = false)
        assertEquals(left?.secondary, right?.secondary)
        val options = IslandSlotContentAssembler.resolveLyricDisplayOptions(
            translationDisplayMode = config.translationDisplayMode,
            translationFallback = config.translationFallback,
            translationOnly = config.translationOnly,
            nextLinePreview = IslandSlotContentAssembler.isNextLinePreviewEnabled(prefs, config),
        )
        return LyricLineAssembler(
            displayMode = options.displayMode,
            fallback = options.fallback,
            hideSecondaryContent = options.hideSecondaryContent,
            secondaryTextUnitProgress = config.usesBothLyricSlots,
        ).buildSecondary(right)
    }

    private fun preferences(
        mode: Int = RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND,
        source: String = "lyricon",
        removeSpaces: Boolean = false,
        translationFallback: Boolean = false,
    ): SharedPreferences {
        val values = mapOf(
            RootConstants.KEY_HOOK_REMOVE_CJK_LYRIC_SPACES to removeSpaces,
            RootConstants.KEY_HOOK_LYRIC_MODE to mode,
            RootConstants.KEY_HOOK_LYRIC_SOURCE to source,
            RootConstants.KEY_HOOK_NEXT_LYRIC_LINE to true,
            RootConstants.KEY_HOOK_TRANSLATION_DISPLAY to RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION,
            RootConstants.KEY_HOOK_TRANSLATION_FALLBACK to translationFallback,
        )
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getAll" -> values
                "contains" -> values.containsKey(args!![0])
                "getInt", "getBoolean", "getString", "getStringSet", "getFloat", "getLong" -> values[args!![0]] ?: args[1]
                "edit" -> error("Runtime preferences are read only")
                else -> null
            }
        } as SharedPreferences
    }
}
