/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

import com.juren233.hyperlyricsenhanced.lyric.view.line.model.LyricModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure policy and source-contract checks; no Android Paint, View or real playback is exercised. */
class SwitchProgressCatchUpTest {

    @Test
    fun `window is bounded and only used for a recent switch lag`() {
        assertNull(SwitchProgressCatchUp.windowMs(0L))
        assertNull(SwitchProgressCatchUp.windowMs(-20L))
        assertNull(SwitchProgressCatchUp.windowMs(SwitchProgressCatchUp.MAX_LAG_MS + 1))
        assertEquals(160L, SwitchProgressCatchUp.windowMs(33L))
        assertEquals(250L, SwitchProgressCatchUp.windowMs(250L))
        assertEquals(320L, SwitchProgressCatchUp.windowMs(406L))
        assertEquals(320L, SwitchProgressCatchUp.windowMs(SwitchProgressCatchUp.MAX_LAG_MS))
        // Device evidence: a mid-line entry from song info landed 1005ms late and must hard-sync.
        assertNull(SwitchProgressCatchUp.windowMs(1005L))
    }

    @Test
    fun `interpolation keeps the previous word formula bit for bit`() {
        fun previous(pos: Long, begin: Long, end: Long, duration: Long, start: Float, endPos: Float): Float {
            val span = (end - begin).takeIf { it > 0 } ?: duration
            if (span <= 0L) return endPos
            val progress = ((pos - begin).toFloat() / span.toFloat()).coerceIn(0f, 1f)
            return start + (endPos - start) * progress
        }
        data class Case(val pos: Long, val begin: Long, val end: Long, val duration: Long, val start: Float, val endPos: Float)
        val cases = listOf(
            Case(1_130L, 1_000L, 1_173L, 173L, 12.37f, 31.91f),
            Case(990L, 1_000L, 1_173L, 173L, 12.37f, 31.91f),
            Case(1_400L, 1_000L, 1_173L, 173L, 12.37f, 31.91f),
            Case(1_050L, 1_000L, 1_000L, 97L, 0.3f, 7.77f),
            Case(1_050L, 1_000L, 1_000L, 0L, 0.3f, 7.77f),
        )
        cases.forEach { c ->
            val expected = previous(c.pos, c.begin, c.end, c.duration, c.start, c.endPos)
            val actual = SwitchProgressCatchUp.interpolate(c.pos, c.begin, c.end, c.duration, c.start, c.endPos)
            assertEquals(expected.toRawBits(), actual.toRawBits())
        }
    }

    @Test
    fun `catch up sweeps continuously from zero and lands on the window target`() {
        val animator = ProgressAnimator()
        val target = 120f
        val window = 300L
        animator.animateTo(target, window)
        val frameNanos = 16_666_667L
        var previous = animator.currentWidth
        var frames = 0
        while (animator.step(frameNanos)) {
            val current = animator.currentWidth
            assertTrue("progress must not move backwards", current >= previous)
            assertTrue("no frame may jump past a linear frame share", current - previous <= target * frameNanos / (window * 1_000_000f) + 0.001f)
            previous = current
            frames++
        }
        assertEquals(target, animator.currentWidth, 0f)
        assertTrue("sweep must span several frames, not a hard cut", frames >= 15)
    }

    @Test
    fun `both renderers arm catch up only for the first unseeked progress`() {
        listOf("WordSyncRenderer.kt", "SpaceGateWordSyncRenderer.kt").forEach { file ->
            val code = source(file)
            val update = block(code, "override fun update(")
            assertFalse("$file must not jump unconditionally on zero progress",
                compact(update).contains("if (word != null && progressAnimator.currentWidth == 0f)"))
            assertBefore(update, "if (posMs < catchUpUntilMs)", "val target = animationTargetWidth(")
            assertBefore(update, "if (startSwitchCatchUp(posMs, model))",
                "if (word != null) progressAnimator.jumpTo(exactTargetWidth(posMs, model, word))")

            val seek = block(code, "override fun seek(")
            assertBefore(seek, "cancelSwitchCatchUp()", "progressAnimator.jumpTo(target)")
            assertTrue(block(code, "fun freeze(").contains("cancelSwitchCatchUp()"))
            val reset = compact(block(code, "override fun reset(state: LineState)"))
            assertTrue(reset.contains("catchUpArmed = true catchUpUntilMs = Long.MIN_VALUE"))

            val start = block(code, "private fun startSwitchCatchUp(")
            assertBefore(start, "if (!catchUpArmed) return false", "catchUpArmed = false")
            assertBefore(start, "SwitchProgressCatchUp.syncWidthAt(posMs, model) <= 0f", "catchUpArmed = false")
            assertTrue(start.contains("SwitchProgressCatchUp.windowMs(posMs - firstBegin) ?: return false"))
            assertTrue(start.contains("progressAnimator.animateTo(target, window)"))
            assertTrue(compact(block(start, "if (BuildConfig.DEBUG")).contains("HookLogger.d("))
        }
    }

    @Test
    fun `same line rebind is distinguished from a real line switch`() {
        fun line(begin: Long, end: Long, text: String) = LyricModel(begin = begin, end = end, text = text, words = emptyList())
        val current = line(33_423L, 36_900L, "是从何时起 我再也无法控制自己")
        assertTrue(SwitchProgressCatchUp.isSameLine(current, line(33_423L, 36_900L, "是从何时起 我再也无法控制自己")))
        // A repeated chorus line has the same text but its own time window.
        assertFalse(SwitchProgressCatchUp.isSameLine(current, line(52_010L, 55_480L, "是从何时起 我再也无法控制自己")))
        assertFalse(SwitchProgressCatchUp.isSameLine(current, line(33_423L, 36_900L, "下一句")))
        assertFalse(SwitchProgressCatchUp.isSameLine(current, line(33_423L, 37_000L, "是从何时起 我再也无法控制自己")))
        // The first bind after an empty/reset row is a real landing.
        assertFalse(SwitchProgressCatchUp.isSameLine(line(0L, 0L, ""), line(0L, 0L, "")))
    }

    @Test
    fun `both views cancel catch up only for a same line rebind after the new bind`() {
        listOf("LyricLineView.kt", "SpaceGateLyricLineView.kt").forEach { file ->
            val bind = block(source(file), "fun setLyric(rawLine: LyricLine?)")
            assertBefore(bind, "val previousModel = _model", "reset()")
            assertBefore(bind, "geometryReuse.bind(_model)",
                "if (SwitchProgressCatchUp.isSameLine(previousModel, _model)) syncRenderer.cancelSwitchCatchUp()")
            assertBefore(bind, "syncRenderer.cancelSwitchCatchUp()", "refreshSizes(GeometryReason.BIND)")
        }
    }

    @Test
    fun `host morph lets only word progress through while content stays deferred`() {
        val renderer = source("../../../root/island/renderer/BaseIslandRenderer.kt")
        val tick = block(renderer, "private fun updatePositionForActiveViews(")
        val deferred = block(tick, "if (IslandContentUpdateCoordinator.deferContent(cv, isSeek = isSeek))")
        assertTrue(compact(deferred).contains(
            "if (!isSeek && IslandContentUpdateCoordinator.isRealContent(cv)) { advanceWordProgress(cv, indexedViews, position) } return@post"))
        listOf("updateViewPosition(", "updateProgressGlow(", "updateEndOfSongPreview(", "triggerLyricContentRelayout(")
            .forEach { assertFalse("deferred tick must not run $it", deferred.contains(it)) }
        val advance = block(renderer, "private fun advanceWordProgress(")
        listOf("setPosition(", "seekTo(", "refreshInjectedViews(").forEach { assertFalse(advance.contains(it)) }

        listOf("../RichLyricLineView.kt", "../SpaceGateRichLyricLineView.kt").forEach { file ->
            val rich = compact(block(source(file), "fun advanceWordProgress(position: Long)"))
            assertEquals("$file must not record lastPosition or touch content",
                "if (animationTransition) return main.updateWordProgress(position) secondary.updateWordProgress(position)", rich)
        }
        listOf("LyricLineView.kt", "SpaceGateLyricLineView.kt").forEach { file ->
            assertEquals("if (isWordSync && !isInterludeIndicator) updatePosition(posMs)",
                compact(block(source(file), "fun updateWordProgress(posMs: Long)")))
        }
    }

    private fun source(name: String): String {
        val relative = java.io.File("src/main/java/com/juren233/hyperlyricsenhanced/lyric/view/line/$name").normalize().path
        val candidates = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .flatMap { sequenceOf(File(it, relative), File(it, "app/$relative")) }
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Cannot locate source contract input: $name")
    }

    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun assertBefore(source: String, first: String, second: String) {
        val a = source.indexOf(first)
        val b = source.indexOf(second)
        assertTrue("Expected '$first' before '$second'", a >= 0 && b > a)
    }

    private fun block(source: String, marker: String): String {
        val start = source.indexOf(marker)
        check(start >= 0) { "Missing source contract marker: $marker" }
        val opening = source.indexOf('{', start + marker.length)
        check(opening >= 0) { "Missing block for: $marker" }
        var depth = 1
        for (index in opening + 1 until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(opening + 1, index)
            }
        }
        error("Unclosed source contract block: $marker")
    }
}
