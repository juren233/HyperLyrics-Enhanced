/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.transition

import com.juren233.hyperlyricsenhanced.root.mediacard.MediaCardFullAodTransitionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCardFramePlanTest {
    @Test
    fun `native fraction is the only continuous clock and card height is monotonic`() {
        val fractions = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val plans = fractions.map { fraction ->
            MediaCardFramePlan.interpolate(
                fraction = fraction,
                targetFullAod = true,
                mode = MediaCardFullAodTransitionMode.DEFAULT,
                startCardHeight = 600,
                targetCardHeight = 420,
                keepSecondLyric = true,
                secondaryTextSizeSp = null,
                secondaryTopOffsetPx = null,
                secondaryAlpha = null,
                secondaryVisible = true,
            )
        }
        assertEquals(3, plans.first().groupAlphas.size)
        plans.zipWithNext().forEach { (before, after) ->
            assertTrue(after.fraction >= before.fraction)
            assertTrue(after.targetCardHeight!! <= before.targetCardHeight!!)
            after.groupAlphas.forEach { assertTrue(it in 0f..1f) }
        }
        assertEquals(420, plans.last().targetCardHeight)
        assertEquals(0f, plans.last().progressAlpha, 0.0001f)
    }

    @Test
    fun `paused restore native keeps actions and retracts lyric root`() {
        val plan = MediaCardFramePlan.interpolate(
            fraction = 1f,
            targetFullAod = true,
            mode = MediaCardFullAodTransitionMode.PAUSED_RESTORE_NATIVE,
            startCardHeight = 500,
            targetCardHeight = 300,
            keepSecondLyric = false,
            secondaryTextSizeSp = null,
            secondaryTopOffsetPx = null,
            secondaryAlpha = null,
            secondaryVisible = false,
        )
        assertEquals(0f, plan.rootAlpha, 0.0001f)
        assertEquals(1f, plan.actionsAlpha, 0.0001f)
        assertEquals(listOf(0f, 0f, 0f), plan.groupAlphas)
    }
}
