/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FramePlanGeometryContractTest {
    @Test
    fun `target height is derived from player local geometry and measured lyric content`() {
        val resolver = GeometryResolver()
        val geometry = MediaCardGeometrySnapshot(
            playerWidth = 500,
            playerHeight = 400,
            cardBottom = 400,
            anchor = LocalViewBounds(20, 40, 200, 240),
            progress = LocalViewBounds(20, 320, 480, 336),
            controls = LocalViewBounds(20, 350, 480, 390),
            actions = listOf(LocalViewBounds(20, 350, 100, 390)),
            contentBottom = 390,
            safeBottomInset = 10,
            lyricTop = 240,
            valid = true,
        )
        assertEquals(660, resolver.targetCardHeight(geometry, lyricHeight = 400, topInset = 10, bottomInset = 10))
        assertNull(resolver.targetCardHeight(geometry, lyricHeight = 0))
    }
}
