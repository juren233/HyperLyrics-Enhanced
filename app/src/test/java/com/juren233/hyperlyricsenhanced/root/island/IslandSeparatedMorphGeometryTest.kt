/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.lyric.view.line.SeamOcclusionLayout
import com.juren233.hyperlyricsenhanced.lyric.view.line.SpaceGatePromotionGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandSeparatedMorphGeometryTest {
    @Test
    fun `both gap boundaries start a morph without enabling short lyric or right preview features`() {
        assertTrue(IslandSeparatedMorphGeometry.shouldStart(false, false, true, false))
        assertTrue(IslandSeparatedMorphGeometry.shouldStart(true, true, false, false))
        assertFalse(IslandSeparatedMorphGeometry.shouldStart(true, true, true, false))
        assertFalse(IslandSeparatedMorphGeometry.shouldStart(false, false, false, false))
    }

    @Test
    fun `split second row promotion uses shared morph while initial or inconsistent pairs do not`() {
        assertTrue(IslandSeparatedMorphGeometry.shouldStart(true, true, true, true))
        assertFalse(IslandSeparatedMorphGeometry.shouldStart(null, null, true, true))
        assertFalse(IslandSeparatedMorphGeometry.shouldStart(true, null, true, true))
        assertFalse(IslandSeparatedMorphGeometry.shouldStart(false, true, true, true))
    }

    @Test
    fun `full right preview splits into two exact source pieces with original font advances`() {
        val whole = SpaceGatePromotionGeometry.inIsland(endpoint("甲乙丙丁", 10f), 100f, 160f, true)
        val left = IslandSeparatedMorphGeometry.slice(whole, 0, 2)!!
        val right = IslandSeparatedMorphGeometry.slice(whole, 2, 4)!!
        assertEquals(100f, left.glyphs.first().start, 0f)
        assertEquals(120f, right.glyphs.first().start, 0f)
        assertEquals(0, right.glyphs.first().charStart)
        assertEquals(20f, right.glyphs.first().naturalStart, 0f)
        assertEquals(40f, right.glyphs.last().naturalEnd, 0f)
        assertTrue(IslandSeparatedMorphGeometry.matches(left, endpoint("甲乙", 20f)))
        assertTrue(IslandSeparatedMorphGeometry.matches(right, endpoint("丙丁", 20f)))
    }

    @Test
    fun `preview moves left and grows continuously before landing on its independent main row`() {
        val whole = SpaceGatePromotionGeometry.inIsland(endpoint("甲乙丙丁", 10f), 100f, 160f, true)
        val source = IslandSeparatedMorphGeometry.slice(whole, 0, 2)!!
        val target = SpaceGatePromotionGeometry.inIsland(endpoint("甲乙", 20f), 100f, 160f, false)
        val morph = SpaceGatePromotionGeometry(source, target)
        assertEquals(100f, morph.start(0, 0f), 0f)
        assertEquals(50f, morph.start(0, 0.5f), 0f)
        assertEquals(15f, morph.end(0, 0.5f) - morph.start(0, 0.5f), 0f)
        assertEquals(0f, morph.start(0, 1f), 0f)
        assertEquals(20f, morph.end(0, 1f), 0f)
        assertEquals(target, morph.frame(1f).copy(glyphs = morph.frame(1f).glyphs.mapIndexed { i, glyph ->
            glyph.copy(naturalStart = target.glyphs[i].naturalStart, naturalEnd = target.glyphs[i].naturalEnd)
        }))
    }

    @Test
    fun `independent target clips prevent a long left half from leaking into the right half`() {
        val source = IslandSeparatedMorphGeometry.clip(100f, 160f, true)
        val target = IslandSeparatedMorphGeometry.clip(100f, 160f, false)
        assertEquals(IslandSeparatedMorphGeometry.Clip(100f, 260f), source.towards(target, 0f))
        assertEquals(IslandSeparatedMorphGeometry.Clip(50f, 180f), source.towards(target, 0.5f))
        assertEquals(IslandSeparatedMorphGeometry.Clip(0f, 100f), source.towards(target, 1f))
        assertEquals(100f, source.towards(target, 1f).end, 0f)
    }

    @Test
    fun `entering a gap gathers both outgoing halves into the right slot without overlap`() {
        val left = SpaceGatePromotionGeometry.inIsland(endpoint("歌词", 40f), 100f, 80f, false)
        val right = SpaceGatePromotionGeometry.inIsland(endpoint("正文", 40f), 100f, 80f, true)
        val endLeft = IslandSeparatedMorphGeometry.gatherRight(left, 0f, 160f, 100f, 80f)
        val endRight = IslandSeparatedMorphGeometry.gatherRight(right, 80f, 160f, 100f, 80f)
        assertEquals(100f, endLeft.glyphs.first().start, 0f)
        assertEquals(140f, endLeft.glyphs.last().end, 0f)
        assertEquals(140f, endRight.glyphs.first().start, 0f)
        assertEquals(180f, endRight.glyphs.last().end, 0f)
        assertEquals(20f, endLeft.glyphs.first().end - endLeft.glyphs.first().start, 0f)
        assertEquals(50f, SpaceGatePromotionGeometry(left, endLeft).start(0, 0.5f), 0f)
    }

    @Test
    fun `native width changes rebase both visible glyphs and their independent clip at the camera`() {
        val before = SpaceGatePromotionGeometry.inIsland(endpoint("歌词", 20f), 100f, 160f, true)
        val after = SpaceGatePromotionGeometry.rebaseCamera(before, 320f, 140f)
        val clip = IslandSeparatedMorphGeometry.clip(100f, 160f, true).shifted(40f)
        assertEquals(before.glyphs.first().start - before.seam, after.glyphs.first().start - after.seam, 0f)
        assertEquals(140f, clip.start, 0f)
        assertEquals(300f, clip.end, 0f)
    }

    @Test
    fun `slicing cannot emit half a surrogate or combining glyph`() {
        val emoji = endpoint("😀字", floatArrayOf(20f, 0f, 20f))
        assertNull(IslandSeparatedMorphGeometry.slice(emoji, 0, 1))
        assertNull(IslandSeparatedMorphGeometry.slice(emoji, 1, 2))
        assertNotNull(IslandSeparatedMorphGeometry.slice(emoji, 0, 2))
        val accent = endpoint("e\u0301x", floatArrayOf(10f, 0f, 10f))
        assertNull(IslandSeparatedMorphGeometry.slice(accent, 0, 1))
        assertNotNull(IslandSeparatedMorphGeometry.slice(accent, 0, 2))
    }

    @Test
    fun `retargeting a flying glyph preserves its current position width and original natural advance`() {
        val source = endpoint("歌词", 10f)
        val target = SpaceGatePromotionGeometry.translated(endpoint("歌词", 20f), 100f)
        val visible = SpaceGatePromotionGeometry(source, target).frame(0.4f)
        val redirected = SpaceGatePromotionGeometry(visible, source)
        assertEquals(visible.glyphs.first().start, redirected.start(0, 0f), 0f)
        assertEquals(visible.glyphs.first().end, redirected.end(0, 0f), 0f)
        assertEquals(10f, visible.glyphs.first().naturalEnd - visible.glyphs.first().naturalStart, 0f)
        assertEquals(source.glyphs.first().start, redirected.start(0, 1f), 0f)
    }

    private fun endpoint(text: String, width: Float) = endpoint(text, FloatArray(text.length) { width })

    private fun endpoint(text: String, widths: FloatArray): SpaceGatePromotionGeometry.Endpoint =
        SpaceGatePromotionGeometry.endpoint(SeamOcclusionLayout.build(text, widths)!!, widths.sum(), 160f, 0f)
}
