/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import org.junit.Assert.*
import org.junit.Test

class SpaceGateRightPreviewGeometryTest {
    @Test
    fun `long line keeps a fixed focus with clearance before the camera`() {
        val text = "one two three four five six seven eight"
        val units = requireNotNull(SeamOcclusionLayout.build(text, FloatArray(text.length) { 10f }))
        for (seam in listOf(35f, 60f, 75f)) {
            val anchor = seam * 0.85f
            val layout = SpaceGateLineLayout(units.totalAdvance, 100f, units, seam, nextLineOnRight = true)
            val oldStop = -(units.totalAdvance - 100f)
            val end = -(layout.scrollWidth - 100f)
            assertTrue(end < oldStop)
            var sawPreview = false
            for (step in 0..1000) {
                val progress = units.totalAdvance * step / 1000f
                val followed = requireNotNull(layout.followProgress(progress, 50f))
                val plan = requireNotNull(layout.plan(followed.offset))
                val tail = units.totalAdvance + followed.offset + plan.shiftAt(units.totalAdvance)
                val preview = SpaceGateRightPreviewGeometry.previewStart(tail, 100f, seam, 10f, 1f)
                if (tail >= 100f) assertTrue(preview >= 100f)
                if (followed.offset < 0f) {
                    val focus = progress + followed.offset + plan.shiftAt(progress)
                    assertTrue("focus must not overshoot anchor $anchor: $focus", focus >= anchor - 0.001f)
                    assertTrue("head avoidance keeps focus left of camera", focus <= seam + 0.001f)
                    if (plan.shiftAt(progress) == 0f) {
                        assertEquals("fixed focus after head avoidance", anchor, focus, 0.001f)
                    }
                    assertTrue(focus <= 100.001f)
                    // A static word gap can be crossed before it has fully collapsed.
                    // The scrolling offset itself must remain continuous (covered below).
                    if (preview < 100f) sawPreview = true
                    if (sawPreview) assertTrue(preview > seam)
                }
            }
            assertTrue(sawPreview)
            val followed = requireNotNull(layout.followProgress(units.totalAdvance, 50f))
            assertEquals(end, followed.offset, 0.001f)
            val tail = units.totalAdvance + end
            assertEquals(anchor, tail, 0.001f)
            val endpoint = SpaceGatePromotionGeometry.endpoint(units, units.totalAdvance, 100f, seam,
                scrollOffset = end, nextLineOnRight = true)
            assertTrue(endpoint.glyphs.last().start < seam)
            assertEquals(anchor, endpoint.glyphs.last().end, 0.001f)
            assertNull(requireNotNull(layout.plan(end)).straddler)
            assertEquals(0f, SpaceGateFadingEdges.resolve(units.totalAdvance, layout.scrollWidth,
                100f, seam, end, 10f, layout.plan(end)).camera, 0.001f)
            assertTrue(SpaceGateRightPreviewGeometry.previewStart(tail, 100f, seam, 10f, 1f) < 100f)
        }
    }

    @Test
    fun `current motion is continuous monotonic and seek reversible`() {
        for (text in listOf("aa hello world zz", "Now he seems bored by you", "你好世界这是下一行前的歌词")) {
            val units = requireNotNull(SeamOcclusionLayout.build(text, FloatArray(text.length) { 10f }))
            for (view in listOf(80f, units.totalAdvance + 4f)) {
                val seam = view * 0.4f
                val layout = SpaceGateLineLayout(units.totalAdvance, view, units, seam, nextLineOnRight = true)
                if (!layout.isOverflow) continue
                var previous = 0f
                for (step in 0..1000) {
                    val p = units.totalAdvance * step / 1000f
                    val followed = requireNotNull(layout.followProgress(p, 0f))
                    val offset = followed.offset
                    assertTrue(offset <= previous + 0.001f)
                    // Scrolling follows real lyric advance without a second focus migration.
                    assertTrue("jump in $text, view=$view, progress=$p: $previous -> $offset",
                        previous - offset <= units.totalAdvance / 1000f + 0.001f)
                    layout.followProgress(units.totalAdvance - p, 0f)
                    assertEquals(offset, requireNotNull(layout.followProgress(p, 0f)).offset, 0f)
                    previous = offset
                }
            }
        }
    }

    @Test
    fun `short and nearly fitting lines stop at or before camera edge without a finish snap`() {
        for (text in listOf("Hi", "hello", "I got it", "你好世界", "aa hello world zz")) {
            for (scale in listOf(0.7f, 1f, 1.4f)) {
                val units = requireNotNull(SeamOcclusionLayout.build(text, FloatArray(text.length) { 10f * scale }))
                val view = 100f * scale
                for (seam in listOf(25f, 50f, 75f).map { it * scale }) {
                    val layout = SpaceGateLineLayout(units.totalAdvance, view, units, seam, nextLineOnRight = true)
                    val stop = -(layout.scrollWidth - view).coerceAtLeast(0f)
                    val followed = layout.followProgress(units.totalAdvance, 0f)
                    assertEquals(stop, followed?.offset ?: 0f, 0.001f)
                    val tail = layout.textOrigin(stop) + units.totalAdvance + (layout.plan(stop)?.shiftAt(units.totalAdvance) ?: 0f)
                    assertEquals(minOf(units.totalAdvance, seam * 0.85f), tail, 0.001f)
                    assertTrue(tail <= seam + 0.001f)
                    if (followed != null) {
                        val almostDone = requireNotNull(layout.followProgress(units.totalAdvance - 0.001f, 0f))
                        assertTrue("no late finish jump", almostDone.offset - stop < 0.01f)
                    }
                    val preview = SpaceGateRightPreviewGeometry.previewStart(tail, view, seam, 10f * scale, 1f)
                    assertTrue(preview > seam && preview < view)
                    val endpoint = SpaceGatePromotionGeometry.endpoint(units, units.totalAdvance, view, seam,
                        scrollOffset = stop, nextLineOnRight = true)
                    assertEquals(tail, endpoint.glyphs.last().end, 0.001f)
                }
            }
        }
    }

    @Test
    fun `focus stays fixed for early middle and late progress without tail acceleration`() {
        val text = "one two three four five six seven eight"
        val units = requireNotNull(SeamOcclusionLayout.build(text, FloatArray(text.length) { 10f }))
        for (seam in listOf(35f, 80f, 140f)) {
            val anchor = seam * 0.85f
            val layout = SpaceGateLineLayout(units.totalAdvance, 200f, units, seam, nextLineOnRight = true)
            var previousOffset = 0f
            for (progress in 0..units.totalAdvance.toInt()) {
                val followed = requireNotNull(layout.followProgress(progress.toFloat(), 180f))
                assertEquals(anchor, followed.anchor, 0f)
                assertTrue("no extra acceleration near tail", previousOffset - followed.offset <= 1.001f)
                if (followed.offset < 0f) {
                    val plan = requireNotNull(layout.plan(followed.offset))
                    val drawnFocus = progress + followed.offset + plan.shiftAt(progress.toFloat())
                    if (plan.shiftAt(progress.toFloat()) == 0f) assertEquals(anchor, drawnFocus, 0.001f)
                    else assertTrue(drawnFocus <= seam + 0.001f)
                }
                previousOffset = followed.offset
            }
        }
    }

    @Test
    fun `camera clearance remains modest across different left slot widths`() {
        for (seam in listOf(60f, 120f, 160f, 240f)) {
            val focus = SpaceGateRightPreviewGeometry.anchor(seam + 180f, seam)
            assertEquals(seam * 0.15f, seam - focus, 0.001f)
            assertTrue(focus > seam / 2f)
            assertTrue(focus < seam)
        }
    }

    @Test
    fun `independent right slot keeps the existing focus beside song information`() {
        val view = 120f
        val text = 100f
        assertEquals(40f, SpaceGateRightPreviewGeometry.travel(text, view, 0f), 0f)
        assertEquals(60f, SpaceGateRightPreviewGeometry.anchor(view, 0f), 0f)
    }

    @Test
    fun `short line preview slides from right and never overlaps the current line or camera`() {
        for (tail in listOf(25f, 65f, 110f)) {
            var previous = Float.POSITIVE_INFINITY
            for (step in 0..100) {
                val start = SpaceGateRightPreviewGeometry.previewStart(tail, 100f, 45f, 20f, step / 100f)
                assertTrue(start >= tail)
                assertTrue(start > 45f)
                assertTrue(start <= previous)
                previous = start
            }
            if (tail < 100f) assertEquals(100f,
                SpaceGateRightPreviewGeometry.previewStart(tail, 100f, 45f, 20f, 0f), 0f)
        }
    }

    @Test
    fun `disabled preview and missing seam preserve the old layout`() {
        val text = "aa hello world zz"
        val units = requireNotNull(SeamOcclusionLayout.build(text, FloatArray(text.length) { 10f }))
        val old = SpaceGateLineLayout(units.totalAdvance, 100f, units, 50f)
        assertEquals(units.totalAdvance, old.scrollWidth, 0f)
        for (seam in listOf(0f, 100f, Float.NaN)) {
            val layout = SpaceGateLineLayout(units.totalAdvance, 100f, units, seam, nextLineOnRight = true)
            assertEquals(old.scrollWidth, layout.scrollWidth, 0f)
            assertNull(layout.plan(-10f))
        }
    }
}
