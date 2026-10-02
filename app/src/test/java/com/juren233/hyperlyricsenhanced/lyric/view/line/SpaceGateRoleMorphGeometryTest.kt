/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceGateRoleMorphGeometryTest {
    private val text = "hello world"

    @Test
    fun `independent lyric is rebased to the physical right slot without inserting camera avoidance`() {
        val local = endpoint(4f, 130f, 0f)
        val island = SpaceGatePromotionGeometry.inIsland(local, 70f, 130f, true)
        assertEquals(200f, island.viewWidth, 0f)
        assertEquals(70f, island.seam, 0f)
        local.glyphs.zip(island.glyphs).forEach { (a, b) ->
            assertEquals(a.start + 70f, b.start, 0f)
            assertEquals(a.end + 70f, b.end, 0f)
            assertEquals(a.naturalStart, b.naturalStart, 0f)
            assertEquals(a.naturalEnd, b.naturalEnd, 0f)
        }
    }

    @Test
    fun `preview moves left and grows continuously into full island main text`() {
        val source = SpaceGatePromotionGeometry.inIsland(endpoint(4f, 130f, 0f), 70f, 130f, true)
        val target = endpoint(10f, 200f, 70f)
        val morph = SpaceGatePromotionGeometry(source, target)
        assertEquals(70f, morph.start(0, 0f), 0f)
        assertEquals(35f, morph.start(0, 0.5f), 0f)
        assertEquals(0f, morph.start(0, 1f), 0f)
        assertEquals(7f, morph.end(0, 0.5f) - morph.start(0, 0.5f), 0f)
        assertContinuous(morph)
    }

    @Test
    fun `full island preview moves right into independent main text`() {
        val source = endpoint(4f, 200f, 70f)
        val target = SpaceGatePromotionGeometry.inIsland(endpoint(10f, 130f, 0f), 70f, 130f, true)
        val morph = SpaceGatePromotionGeometry(source, target)
        assertEquals(0f, morph.start(0, 0f), 0f)
        assertEquals(35f, morph.start(0, 0.5f), 0f)
        assertEquals(70f, morph.start(0, 1f), 0f)
        assertContinuous(morph)
    }

    @Test
    fun `both directions land on the real single and full layout for unequal widths and alignment`() {
        for (left in listOf(47f, 79f, 111f)) {
            for (right in listOf(120f, 190f, 300f)) {
                for (alignment in 0..2) {
                    val single = SpaceGatePromotionGeometry.inIsland(endpoint(7f, right, 0f, alignment), left, right, true)
                    val full = endpoint(11f, left + right, left, alignment)
                    for ((from, to) in listOf(single to full, full to single)) {
                        val morph = SpaceGatePromotionGeometry(from, to)
                        from.glyphs.indices.forEach { index ->
                            assertEquals(from.glyphs[index].start, morph.start(index, 0f), 0f)
                            assertEquals(to.glyphs[index].start, morph.start(index, 1f), 0f)
                            assertEquals(to.glyphs[index].end, morph.end(index, 1f), 0f)
                        }
                        assertContinuous(morph)
                    }
                }
            }
        }
    }

    @Test
    fun `reversing mid flight starts at the visible glyphs and retains original font advances`() {
        val single = SpaceGatePromotionGeometry.inIsland(endpoint(4f, 130f, 0f), 70f, 130f, true)
        val full = endpoint(10f, 200f, 70f)
        val forward = SpaceGatePromotionGeometry(single, full)
        val visible = forward.frame(0.37f)
        val reverse = SpaceGatePromotionGeometry(visible, single)
        single.glyphs.indices.forEach { index ->
            assertEquals(forward.start(index, 0.37f), reverse.start(index, 0f), 0f)
            assertEquals(forward.end(index, 0.37f), reverse.end(index, 0f), 0f)
            assertEquals(single.glyphs[index].naturalStart, visible.glyphs[index].naturalStart, 0f)
            assertEquals(single.glyphs[index].naturalEnd, visible.glyphs[index].naturalEnd, 0f)
            assertEquals(single.glyphs[index].start, reverse.start(index, 1f), 0f)
        }
        assertContinuous(reverse)
    }

    @Test
    fun `native width change keeps every visible glyph fixed relative to the camera before retargeting`() {
        val initial = SpaceGatePromotionGeometry.inIsland(endpoint(4f, 130f, 0f), 70f, 130f, true)
        val visible = SpaceGatePromotionGeometry(initial, endpoint(10f, 200f, 70f)).frame(0.37f)
        for (newSeam in listOf(43f, 105f, 190f)) {
            val rebased = SpaceGatePromotionGeometry.rebaseCamera(visible, newSeam + 150f, newSeam)
            visible.glyphs.zip(rebased.glyphs).forEach { (old, new) ->
                assertEquals(old.start - visible.seam, new.start - newSeam, 0.0001f)
                assertEquals(old.end - visible.seam, new.end - newSeam, 0.0001f)
                assertEquals(old.naturalStart, new.naturalStart, 0f)
                assertEquals(old.naturalEnd, new.naturalEnd, 0f)
            }
            val retarget = SpaceGatePromotionGeometry(rebased, endpoint(10f, newSeam + 150f, newSeam))
            assertContinuous(retarget)
        }
    }

    @Test
    fun `camera boundary stays fixed while the glyph changes sides`() {
        val single = SpaceGatePromotionGeometry.inIsland(endpoint(4f, 130f, 0f), 70f, 130f, true)
        val morph = SpaceGatePromotionGeometry(single, endpoint(10f, 200f, 70f))
        var crossings = 0
        for (step in 0..100) {
            for (index in single.glyphs.indices) {
                morph.fade(index, step / 100f)?.let {
                    crossings++
                    assertEquals(70f, it.seamLocalX, 0f)
                    assertTrue(it.alpha in 0f..1f)
                }
            }
        }
        assertTrue(crossings > 0)
    }

    private fun endpoint(advance: Float, width: Float, seam: Float, alignment: Int = 0): SpaceGatePromotionGeometry.Endpoint {
        val units = requireNotNull(SeamOcclusionLayout.build(text, FloatArray(text.length) { advance }))
        return SpaceGatePromotionGeometry.endpoint(units, text.length * advance, width, seam,
            centerIfPossible = alignment == 1, alignRight = alignment == 2)
    }

    private fun assertContinuous(morph: SpaceGatePromotionGeometry) {
        for (step in 0..100) {
            val p = step / 100f
            morph.source.glyphs.indices.forEach { index ->
                assertTrue(morph.end(index, p) > morph.start(index, p))
                if (index > 0) assertTrue(morph.start(index, p) >= morph.end(index - 1, p) - 0.001f)
            }
        }
    }
}
