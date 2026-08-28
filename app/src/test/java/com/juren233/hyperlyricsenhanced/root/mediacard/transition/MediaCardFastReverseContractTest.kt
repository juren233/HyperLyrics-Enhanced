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

class MediaCardFastReverseContractTest {
    @Test
    fun `reverse starts at the last rendered frame instead of an endpoint`() {
        val current = MediaCardFramePlan.interpolate(
            fraction = 0.4f,
            targetFullAod = true,
            mode = MediaCardFullAodTransitionMode.DEFAULT,
            startCardHeight = 800,
            targetCardHeight = 500,
            keepSecondLyric = true,
            secondaryTextSizeSp = 14f,
            secondaryTopOffsetPx = 8,
            secondaryAlpha = 220,
            secondaryVisible = true,
            startSecondaryTextSizeSp = 18f,
        )
        val target = MediaCardFramePlan.stable(
            targetFullAod = false,
            mode = MediaCardFullAodTransitionMode.DEFAULT,
            cardHeight = 800,
            keepSecondLyric = true,
        )
        val reverseStart = MediaCardFramePlan.interpolateFrom(current, target, 0f)
        val reverseEnd = MediaCardFramePlan.interpolateFrom(current, target, 1f)

        assertEquals(current.rootAlpha, reverseStart.rootAlpha, 0.0001f)
        assertEquals(current.progressAlpha, reverseStart.progressAlpha, 0.0001f)
        assertEquals(current.targetCardHeight, reverseStart.targetCardHeight)
        assertTrue(reverseEnd.stableAfterCommit)
        assertEquals(target.progressAlpha, reverseEnd.progressAlpha, 0.0001f)
        assertEquals(target.targetCardHeight, reverseEnd.targetCardHeight)
    }

    @Test
    fun `new token invalidates old token without releasing the lease`() {
        val leaseCloseCount = intArrayOf(0)
        val coordinator = MediaCardTransitionCoordinator(MediaCardControllerIdentity(11, 12))
        coordinator.attachHeightLease(object : com.juren233.hyperlyricsenhanced.root.mediacard.host.NativeHeightLease {
            override val classLoader: ClassLoader = javaClass.classLoader!!
            override val originalHeights: IntArray = intArrayOf(100)
            override fun setTargetHeight(index: Int, height: Int): Boolean = true
            override fun restore(): Boolean {
                leaseCloseCount[0]++
                return true
            }
        })
        coordinator.attach(1L)
        val first = coordinator.begin(Any(), true, MediaCardFullAodTransitionMode.DEFAULT, 1L).token!!
        assertTrue(coordinator.update(first, 0.4f).accepted)
        val second = coordinator.begin(Any(), false, MediaCardFullAodTransitionMode.DEFAULT, 1L).token!!
        assertTrue(coordinator.update(second, 0f).accepted)
        assertEquals(0, leaseCloseCount[0])
        assertTrue(coordinator.cancel(second).accepted)
        assertEquals(1, leaseCloseCount[0])
    }
}
