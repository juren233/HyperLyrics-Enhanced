/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollStepperTest {
    private val stepper = ScrollStepper()

    private fun offset(progress: Float, width: Float = 800f, view: Float = 400f, seam: Float? = 200f) =
        stepper.compute(progress, width, view, false, false, resolveSpaceGateFollowAnchor(view, seam))

    @Test
    fun `normal lines keep their existing midpoint follow`() {
        assertEquals(-250f, stepper.compute(450f, 800f, 400f, false, false), 0f)
    }

    @Test
    fun `whole island follows inside the actual right slot even with asymmetric widths`() {
        for (seam in listOf(100f, 200f, 300f)) {
            val target = seam + (400f - seam) / 2f
            assertEquals(target, 450f + offset(450f, seam = seam), 0f)
            assertTrue(target > seam && target < 400f)
        }
    }

    @Test
    fun `invalid or unavailable seam uses the normal midpoint`() {
        for (seam in listOf(null, -1f, 0f, 400f, 500f, Float.NaN)) {
            assertEquals(200f, resolveSpaceGateFollowAnchor(400f, seam), 0f)
        }
    }

    @Test
    fun `scroll speed tracks progress advance and rests between words`() {
        assertEquals(-2f, offset(352f) - offset(350f), 0f)
        assertEquals(-20f, offset(372f) - offset(352f), 0f)
        assertEquals(offset(372f), offset(372f), 0f)
    }

    @Test
    fun `head and tail clamp continuously without forcing short lines to scroll`() {
        assertEquals(0f, offset(299.9f), 0f)
        assertEquals(0f, offset(300f), 0f)
        assertEquals(-0.1f, offset(300.1f), 0.0001f)
        assertEquals(-399.9f, offset(699.9f), 0.0001f)
        assertEquals(-400f, offset(700f), 0f)
        assertEquals(-400f, offset(800f), 0f)
        assertEquals(-400f, stepper.compute(800f, 800f, 400f, true, false, 300f), 0f)
        assertEquals(0f, offset(200f, width = 250f), 0f)
    }

    @Test
    fun `seek is deterministic and not locked to a previous scroll finish`() {
        val expected = offset(450f)
        offset(800f)
        assertEquals(expected, offset(450f), 0f)
        assertEquals(expected, stepper.compute(450f, 800f, 400f, false, true, 300f), 0f)
    }

    @Test
    fun `right following must keep the drawn highlight visible during word avoidance`() {
        val text = "aa hello world zz"
        val layout = requireNotNull(SeamOcclusionLayout.build(text, FloatArray(text.length) { 10f }))
        val viewWidth = 100f
        val seam = 60f
        val progress = 81f
        val followed = SeamStripPlan.followProgress(
            layout, progress, layout.totalAdvance, viewWidth, seam,
            resolveSpaceGateFollowAnchor(viewWidth, seam),
        )
        val offset = followed.offset
        val plan = SeamStripPlan.scrollWithEndpoints(layout, offset, seam, 0f, -(layout.totalAdvance - viewWidth))
        val drawnProgress = progress + offset + plan.shiftAt(progress)
        assertTrue("drawnProgress=$drawnProgress", drawnProgress > seam && drawnProgress <= viewWidth)
        assertEquals(followed.anchor, drawnProgress, 0.0001f)
    }

    @Test
    fun `word gap closing slows the scroll to keep the actual highlight anchored`() {
        val text = "aa hello world zz"
        val layout = requireNotNull(SeamOcclusionLayout.build(text, FloatArray(text.length) { 10f }))
        fun followed(progress: Float) = SeamStripPlan.followProgress(layout, progress, 170f, 100f, 60f, 80f)
        // 进度前进 2px，间隙收拢也移动文字，故条带只需左移 1px。
        assertEquals(-1f, followed(62f).offset - followed(60f).offset, 0.0001f)
        // 间隙合拢后恢复与进度同速。
        assertEquals(-2f, followed(114f).offset - followed(112f).offset, 0.0001f)
        assertEquals(followed(62f), followed(62f))
        assertEquals(-70f, followed(170f).offset, 0f)
    }

    @Test
    fun `drawn progress stays right and visible through normal endpoint transitions`() {
        val text = "aa hello world zz"
        val layout = requireNotNull(SeamOcclusionLayout.build(text, FloatArray(text.length) { 10f }))
        for ((viewWidth, seam) in listOf(100f to 40f, 100f to 60f, 100f to 80f, 120f to 40f)) {
            val end = -(layout.totalAdvance - viewWidth)
            for (step in 0..680) {
                val progress = step * 0.25f
                val follow = SeamStripPlan.followProgress(
                    layout, progress, layout.totalAdvance, viewWidth, seam,
                    resolveSpaceGateFollowAnchor(viewWidth, seam),
                )
                if (follow.offset == 0f) continue
                val plan = SeamStripPlan.scrollWithEndpoints(layout, follow.offset, seam, 0f, end)
                val drawn = progress + follow.offset + plan.shiftAt(progress)
                assertTrue("vw=$viewWidth seam=$seam progress=$progress drawn=$drawn", drawn > seam)
                assertTrue("progress=$progress drawn=$drawn", drawn <= viewWidth + 0.001f)
            }
        }
    }

    @Test
    fun `follow remains continuous monotone bounded and reversible for narrow slots and tiny overflow`() {
        for (text in listOf("aa hello bb", "a extraordinary b", "你好世界你好世界")) {
            val layout = requireNotNull(SeamOcclusionLayout.build(text, FloatArray(text.length) { 10f }))
            for (travel in listOf(0.01f, 1f, 20f, 50f)) {
                val viewWidth = layout.totalAdvance - travel
                for (ratio in listOf(0.3f, 0.5f, 0.8f)) {
                    val seam = viewWidth * ratio
                    var previous = 0f
                    for (i in 0..(text.length * 40)) {
                        val progress = i * 0.25f
                        val follow = SeamStripPlan.followProgress(
                            layout, progress, layout.totalAdvance, viewWidth, seam,
                            resolveSpaceGateFollowAnchor(viewWidth, seam),
                        )
                        assertTrue(follow.offset in -travel - 0.0001f..0f)
                        assertTrue("forward jump $text travel=$travel progress=$progress", follow.offset <= previous + 0.0001f)
                        assertTrue("discontinuity $text travel=$travel progress=$progress", previous - follow.offset <= 0.2501f)
                        previous = follow.offset
                    }
                    assertEquals(-travel, previous, 0.0001f)
                    val progress = layout.totalAdvance * 0.65f
                    val beforeSeek = SeamStripPlan.followProgress(layout, progress, layout.totalAdvance, viewWidth, seam, resolveSpaceGateFollowAnchor(viewWidth, seam))
                    SeamStripPlan.followProgress(layout, layout.totalAdvance, layout.totalAdvance, viewWidth, seam, resolveSpaceGateFollowAnchor(viewWidth, seam))
                    assertEquals(beforeSeek, SeamStripPlan.followProgress(layout, progress, layout.totalAdvance, viewWidth, seam, resolveSpaceGateFollowAnchor(viewWidth, seam)))
                }
            }
        }
    }
}
