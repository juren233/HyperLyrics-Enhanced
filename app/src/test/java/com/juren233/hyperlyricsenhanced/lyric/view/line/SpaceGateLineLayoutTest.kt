/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceGateLineLayoutTest {
    private fun units(text: String, advance: Float) =
        requireNotNull(SeamOcclusionLayout.build(text, FloatArray(text.length) { advance }))

    @Test
    fun `reported 409px lyric in 413px viewport must scroll after whole word avoidance`() {
        val text = "Now he seems bored by you"
        // Device evidence supplies the total/viewport/seam, not individual glyph
        // advances. Uniform advances are a synthetic fixture for that capacity case.
        val layout = units(text, 409f / text.length)
        val geometry = SpaceGateLineLayout(409f, 413f, layout, 164f)
        val word = requireNotNull(layout.straddlingStaticUnit(0f, 164f))
        assertEquals("seems", text.substring(word.charStart, word.charEnd))
        assertTrue(geometry.isOverflow)
        val start = requireNotNull(geometry.plan(0f))
        assertEquals(0f, start.shiftAt(0f), 0f)
        assertEquals(164f, word.start + start.shiftAt(word.start), 0.001f)
        assertEquals(start.shiftAt(word.start), start.shiftAt(word.end - 1f), 0f)
        assertNull(start.straddler)

        val followed = requireNotNull(geometry.followProgress(330f, 288.5f))
        assertTrue(followed.offset < 0f)
        assertTrue(followed.offset >= -(geometry.scrollWidth - 413f))
        val end = -(geometry.scrollWidth - 413f)
        val stopped = requireNotNull(geometry.plan(end))
        assertEquals(164f, end + word.end + stopped.shiftAt(word.end - 1f), 0.001f)
        assertEquals(413f, end + layout.totalAdvance + stopped.shiftAt(layout.totalAdvance), 0.001f)
        assertNull(stopped.straddler)
    }

    @Test
    fun `exact word fit stays static but an internal letter boundary does not hide overflow`() {
        val layout = units("I got it", 10f)
        val exact = SpaceGateLineLayout(80f, 100f, layout, 40f)
        assertFalse(exact.isOverflow)
        assertEquals(100f, 80f + requireNotNull(exact.plan(0f)).shiftAt(80f), 0f)
        assertTrue(SpaceGateLineLayout(80f, 90f, layout, 40f).isOverflow)
        assertNull(layout.straddlingUnit(0f, 40f)) // seam happens to be between letters
        assertNotNull(layout.straddlingStaticUnit(0f, 40f))
    }

    @Test
    fun `ordinary long lines retain their scroll range and follow geometry`() {
        val layout = units("aa hello world zz", 10f)
        val geometry = SpaceGateLineLayout(layout.totalAdvance, 100f, layout, 60f)
        assertEquals(layout.totalAdvance, geometry.scrollWidth, 0f)
        for (offset in listOf(0f, -1f, -30f, -70f)) {
            assertEquals(SeamStripPlan.scrollWithEndpoints(layout, offset, 60f, 0f, -70f), geometry.plan(offset))
        }
        for (progress in listOf(0f, 81f, 120f, 170f)) {
            assertEquals(
                SeamStripPlan.followProgress(layout, progress, 170f, 100f, 60f, 80f),
                geometry.followProgress(progress, 80f),
            )
        }
    }

    @Test
    fun `capacity scrolling is continuous monotonic and preserves word spacing in both directions`() {
        for (text in listOf("I got it", "你好世界再见", "aa I'm, happy zz", "aa well-known zz")) {
            val layout = units(text, 10f)
            val view = layout.totalAdvance + 4f
            var cases = 0
            for (seam in 5 until layout.totalAdvance.toInt() step 5) {
                val geometry = SpaceGateLineLayout(layout.totalAdvance, view, layout, seam.toFloat())
                if (!geometry.isOverflow) continue
                cases++
                val word = requireNotNull(layout.straddlingStaticUnit(0f, seam.toFloat()))
                var previous = FloatArray(text.length) { Float.POSITIVE_INFINITY }
                for (frame in 0..100) {
                    val offset = -word.width * frame / 100f
                    val plan = requireNotNull(geometry.plan(offset))
                    val drawn = FloatArray(text.length) { i -> offset + i * 10f + plan.shiftAt(i * 10f) }
                    for (i in text.indices) {
                        assertTrue("$text at $seam frame $frame: character reversed", drawn[i] <= previous[i] + 0.001f)
                        if (frame > 0) assertTrue(previous[i] - drawn[i] <= word.width / 100f + 0.001f)
                        if (i > 0) assertTrue("overlap", drawn[i] >= drawn[i - 1] + 10f - 0.001f)
                    }
                    for (i in word.charStart + 1 until word.charEnd) {
                        assertEquals(10f, drawn[i] - drawn[i - 1], 0.001f)
                    }
                    previous = drawn
                    // No playback/direction state: seeking back to the same offset is identical.
                    geometry.plan(-word.width * (100 - frame) / 100f)
                    assertEquals(plan, geometry.plan(offset))
                }
                val tail = requireNotNull(geometry.plan(-word.width))
                assertNull(tail.straddler)
                assertEquals(seam.toFloat(), -word.width + word.end + tail.shiftAt(word.end - 1f), 0.001f)
                val rightEdge = -word.width + layout.totalAdvance + tail.shiftAt(layout.totalAdvance)
                assertTrue(rightEdge <= view + 0.001f)
                if (word.charEnd < text.length) assertEquals(view, rightEdge, 0.001f)
            }
            assertTrue(cases > 0)
        }
    }

    @Test
    fun `progress uses actual advances and stays continuous across word boundaries`() {
        for (text in listOf("I got it", "Now he seems bored by you", "hello", "a well-known z")) {
            val layout = units(text, 10f)
            val view = layout.totalAdvance + 4f
            for (seam in 5 until layout.totalAdvance.toInt() step 5) {
                val geometry = SpaceGateLineLayout(layout.totalAdvance, view, layout, seam.toFloat())
                if (!geometry.isOverflow) continue
                val anchor = resolveSpaceGateFollowAnchor(view, seam.toFloat())
                var previous = 0f
                for (step in 0..layout.totalAdvance.toInt() * 10) {
                    val progress = step / 10f
                    val follow = requireNotNull(geometry.followProgress(progress, anchor))
                    assertTrue(follow.offset.isFinite())
                    assertTrue(follow.offset <= previous + 0.001f)
                    assertTrue("discontinuous at $text/$seam/$progress", previous - follow.offset <= 0.201f)
                    val plan = requireNotNull(geometry.plan(follow.offset))
                    val drawn = progress + follow.offset + plan.shiftAt(progress)
                    assertTrue("highlight clipped at $text/$seam/$progress: $drawn", drawn <= view + 0.001f)
                    previous = follow.offset
                }
                assertEquals(-(geometry.scrollWidth - view), previous, 0.001f)
            }
        }
    }

    @Test
    fun `alignment and secondary font sizes share the same full visibility rule`() {
        val text = "I got it"
        for (scale in listOf(0.7f, 1f, 1.4f)) {
            val layout = units(text, 10f * scale)
            for (flags in listOf(Triple(false, false, false), Triple(true, false, false), Triple(false, true, false), Triple(false, false, true))) {
                for (width in listOf(90f, 100f, 150f)) {
                    val view = width * scale
                    val geometry = SpaceGateLineLayout(
                        layout.totalAdvance, view, layout, 40f * scale,
                        flags.first, flags.second, flags.third,
                    )
                    if (width == 90f) {
                        assertTrue(geometry.isOverflow)
                        assertEquals(-5f, geometry.textOrigin(-5f), 0f)
                    } else {
                        assertFalse(geometry.isOverflow)
                        val plan = requireNotNull(geometry.plan(0f))
                        val origin = geometry.textOrigin(0f)
                        for (i in text.indices) {
                            val start = origin + i * 10f * scale + plan.shiftAt(i * 10f * scale)
                            assertTrue(start >= -0.001f)
                            assertTrue(start + 10f * scale <= view + 0.001f)
                        }
                        assertNull(plan.straddler)
                    }
                    val offset = if (geometry.isOverflow) -5f else 0f
                    for (plain in listOf(false, true)) {
                        assertEquals(
                            geometry.textOrigin(offset),
                            resolveShadowTextStartX(
                                geometry.scrollWidth, view, offset, plain,
                                flags.first, flags.second, flags.third,
                            ),
                            0.001f,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `absent seam preserves ordinary layout and late seam changes capacity`() {
        val layout = units("I got it", 10f)
        for (seam in listOf(0f, -1f, 90f, 100f, Float.NaN)) {
            val geometry = SpaceGateLineLayout(80f, 90f, layout, seam)
            assertFalse(geometry.isOverflow)
            assertNull(geometry.plan(0f))
        }
        assertFalse(SpaceGateLineLayout(80f, 90f, null, 35f).isOverflow)
        assertTrue(SpaceGateLineLayout(80f, 90f, layout, 35f).isOverflow)
        assertFalse(SpaceGateLineLayout(80f, 90f, layout, 20f).isOverflow)
        assertFalse(SpaceGateLineLayout(80f, 100f, layout, 35f).isOverflow)
    }
}
