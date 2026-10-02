/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceGatePromotionGeometryTest {
    @Test
    fun `surrogate tails and combining marks stay with their base glyph`() {
        val text = "A\u0301😀B"
        val units = requireNotNull(SeamOcclusionLayout.build(text, floatArrayOf(10f, 0f, 20f, 0f, 10f)))
        val endpoint = SpaceGatePromotionGeometry.endpoint(units, 40f, 100f, 60f)
        assertEquals(listOf("A\u0301", "😀", "B"), endpoint.glyphs.map { text.substring(it.charStart, it.charEnd) })
    }

    @Test
    fun `camera avoidance cannot be preserved by uniformly scaling the preview`() {
        val text = "one two three"
        val source = endpoint(text, 6f, 160f, 50f)
        val target = endpoint(text, 10f, 160f, 50f)
        val geometry = SpaceGatePromotionGeometry(source, target)
        assertTrue(source.glyphs.indices.any { index ->
            kotlin.math.abs(source.glyphs[index].start * (10f / 6f) - target.glyphs[index].start) > 5f
        })
        assertEndpointsAndContinuity(geometry)
    }

    @Test
    fun `landing exactly matches normal layout for alignment sizes and asymmetric slots`() {
        for (text in listOf("一起去看星星吧", "Now he seems bored by you", "hello, world!")) {
            for (seam in listOf(47f, 79f, 111f)) {
                for (width in listOf(130f, 240f, 400f)) {
                    for (alignment in 0..2) {
                        val source = endpoint(text, 7.1f, width, seam, alignment)
                        val target = endpoint(text, 11.7f, width, seam, alignment)
                        assertEndpointsAndContinuity(SpaceGatePromotionGeometry(source, target))
                    }
                }
            }
        }
    }

    @Test
    fun `capacity overflow at main size still lands on the normal initial scroll plan`() {
        val text = "Now he seems bored by you"
        val widths = FloatArray(text.length) { 409f / text.length }
        val units = requireNotNull(SeamOcclusionLayout.build(text, widths))
        val mainLayout = SpaceGateLineLayout(409f, 413f, units, 164f)
        assertTrue(mainLayout.isOverflow)
        val source = endpoint(text, 10f, 413f, 164f)
        val target = SpaceGatePromotionGeometry.endpoint(units, 409f, 413f, 164f)
        val geometry = SpaceGatePromotionGeometry(source, target)
        val plan = requireNotNull(mainLayout.plan(0f))
        units.drawingUnits.forEachIndexed { index, unit ->
            assertEquals(unit.start + plan.shiftAt(unit.start), geometry.start(index, 1f), 0f)
        }
        assertEndpointsAndContinuity(geometry)
    }

    @Test
    fun `camera crossing fades to zero before changing visible side`() {
        val source = endpoint("甲乙丙", 10f, 100f, 45f)
        val target = endpoint("甲乙丙", 30f, 100f, 45f)
        val geometry = SpaceGatePromotionGeometry(source, target)
        var left = false
        var right = false
        var minimum = 1f
        for (step in 0..1000) {
            val fraction = step / 1000f
            for (index in source.glyphs.indices) {
                val fade = geometry.fade(index, fraction) ?: continue
                assertEquals(45f, fade.seamLocalX, 0f)
                assertTrue(fade.alpha in 0f..1f)
                if (fade.majorityLeft) left = true else right = true
                minimum = minOf(minimum, fade.alpha)
            }
        }
        assertTrue(left)
        assertTrue(right)
        assertTrue(minimum < 0.01f)
    }

    @Test
    fun `promotion neither changes the steady plans nor adds overlap during interpolation`() {
        val source = endpoint("Don't stop the music", 7f, 200f, 68f)
        val target = endpoint("Don't stop the music", 12f, 200f, 68f)
        val geometry = SpaceGatePromotionGeometry(source, target)
        assertEndpointsAndContinuity(geometry)
        assertEquals(source, endpoint("Don't stop the music", 7f, 200f, 68f))
        assertEquals(target, endpoint("Don't stop the music", 12f, 200f, 68f))
        assertFalse(source.glyphs.isEmpty())
    }

    private fun endpoint(text: String, advance: Float, width: Float, seam: Float, alignment: Int = 0): SpaceGatePromotionGeometry.Endpoint {
        val units = requireNotNull(SeamOcclusionLayout.build(text, FloatArray(text.length) { advance }))
        val layout = SpaceGateLineLayout(units.totalAdvance, width, units, seam,
            centerIfPossible = alignment == 1, alignRight = alignment == 2)
        val endpoint = SpaceGatePromotionGeometry.endpoint(units, units.totalAdvance, width, seam,
            centerIfPossible = alignment == 1, alignRight = alignment == 2)
        val origin = layout.textOrigin(0f)
        val plan = layout.plan(0f)
        units.drawingUnits.forEachIndexed { index, unit ->
            assertEquals(origin + unit.start + (plan?.shiftAt(unit.start) ?: 0f), endpoint.glyphs[index].start, 0.0001f)
        }
        return endpoint
    }

    private fun assertEndpointsAndContinuity(geometry: SpaceGatePromotionGeometry) {
        for (index in geometry.source.glyphs.indices) {
            assertEquals(geometry.source.glyphs[index].start, geometry.start(index, 0f), 0f)
            assertEquals(geometry.target.glyphs[index].start, geometry.start(index, 1f), 0f)
            assertEquals(geometry.target.glyphs[index].end, geometry.end(index, 1f), 0f)
            assertEquals(geometry.start(index, 1f), geometry.start(index, 0.99999f), 0.01f)
            for (step in 0..100) {
                val f = step / 100f
                assertTrue(geometry.end(index, f) >= geometry.start(index, f))
                if (index > 0) assertTrue(geometry.start(index, f) + 0.0001f >= geometry.end(index - 1, f))
            }
        }
    }
}
