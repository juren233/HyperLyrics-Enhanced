/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import com.juren233.hyperlyricsenhanced.lyric.view.shouldHandoffRightPreview
import org.junit.Assert.*
import org.junit.Test

class SpaceGateRightHandoffTest {
    @Test
    fun `handoff requires the previewed sentence and an actual line advance`() {
        assertTrue(shouldHandoffRightPreview("Next", "Next", true))
        assertFalse(shouldHandoffRightPreview("Next", "Next", false))
        assertFalse(shouldHandoffRightPreview("Next", "Skipped", true))
        for (text in listOf(null, "", " ")) assertFalse(shouldHandoffRightPreview(text, text, true))
    }

    @Test
    fun `preview rolls from its visible right position to full island left without a gap jump`() {
        val current = units("current line is ending now")
        val next = units("next line keeps on rolling")
        val width = 200f
        val seam = 80f
        val offset = -SpaceGateRightPreviewGeometry.travel(current.totalAdvance, width, seam)
        val old = SpaceGatePromotionGeometry.endpoint(current, current.totalAdvance, width, seam,
            scrollOffset = offset, nextLineOnRight = true)
        val previewX = SpaceGateRightPreviewGeometry.previewStart(old.glyphs.last().end, width, seam, 10f, 1f)
        val source = preview(next, previewX, width, seam)
        val target = SpaceGatePromotionGeometry.endpoint(next, next.totalAdvance, width, seam, nextLineOnRight = true)
        val moving = SpaceGatePromotionGeometry(source, target)
        val shift = target.glyphs.first().start - source.glyphs.first().start
        val outgoing = SpaceGatePromotionGeometry(old, SpaceGatePromotionGeometry.translated(old, shift))
        assertEquals(0f, moving.start(0, 1f), 0f)
        val gap = previewX - old.glyphs.last().end
        for (step in 0..100) {
            val f = step / 100f
            assertEquals(gap, moving.start(0, f) - outgoing.end(old.glyphs.lastIndex, f), 0.0001f)
            assertEquals(seam, outgoing.frame(f).seam, 0f)
        }
        assertTrue(outgoing.end(old.glyphs.lastIndex, 1f) < 0f)
    }

    @Test
    fun `short preview lands at right slot left edge for unequal widths and remains one line`() {
        val next = units("next")
        for (left in listOf(55f, 80f, 125f)) {
            val right = 130f
            val source = preview(next, left + 80f, left + right, left)
            val local = SpaceGatePromotionGeometry.endpoint(next, next.totalAdvance, right, 0f,
                nextLineOnRight = true)
            val target = SpaceGatePromotionGeometry.inIsland(local, left, right, true)
            val handoff = SpaceGatePromotionGeometry(source, target)
            assertEquals(left + 80f, handoff.start(0, 0f), 0f)
            assertEquals(left, handoff.start(0, 1f), 0f)
            assertEquals(left + next.totalAdvance, handoff.end(target.glyphs.lastIndex, 1f), 0f)
        }
    }

    @Test
    fun `right slot can keep previewing then hand off back into full island`() {
        val current = units("short line")
        val next = units("a much longer line following the short line")
        val right = 120f
        val left = 70f
        val layout = SpaceGateLineLayout(current.totalAdvance, right, null, 0f, nextLineOnRight = true)
        val offset = -(layout.scrollWidth - right)
        assertTrue(offset < 0f)
        val local = SpaceGatePromotionGeometry.endpoint(current, current.totalAdvance, right, 0f,
            scrollOffset = offset, nextLineOnRight = true)
        assertEquals(layout.textOrigin(offset), local.glyphs.first().start, 0f)
        val previewX = SpaceGateRightPreviewGeometry.previewStart(local.glyphs.last().end, right, 0f, 10f, 1f)
        val source = preview(next, left + previewX, left + right, left)
        val target = SpaceGatePromotionGeometry.endpoint(next, next.totalAdvance, left + right, left,
            nextLineOnRight = true)
        val handoff = SpaceGatePromotionGeometry(source, target)
        assertTrue(handoff.start(0, 0f) > left)
        assertEquals(0f, handoff.start(0, 1f), 0f)
    }

    @Test
    fun `resize during handoff preserves both sentence positions relative to camera`() {
        val next = units("next line")
        val source = preview(next, 150f, 200f, 80f)
        val target = SpaceGatePromotionGeometry.endpoint(next, next.totalAdvance, 200f, 80f)
        val visible = SpaceGatePromotionGeometry(source, target).frame(0.4f)
        val rebased = SpaceGatePromotionGeometry.rebaseCamera(visible, 280f, 110f)
        for ((a, b) in visible.glyphs.zip(rebased.glyphs)) {
            assertEquals(a.start - 80f, b.start - 110f, 0.001f)
            assertEquals(a.naturalStart, b.naturalStart, 0f)
            assertEquals(a.naturalEnd, b.naturalEnd, 0f)
        }
    }

    private fun units(text: String) = requireNotNull(SeamOcclusionLayout.build(text, FloatArray(text.length) { 10f }))

    private fun preview(units: SeamOcclusionLayout, start: Float, width: Float, seam: Float) =
        SpaceGatePromotionGeometry.translated(
            SpaceGatePromotionGeometry.endpoint(units, units.totalAdvance, width, 0f), start,
        ).copy(seam = seam)
}
