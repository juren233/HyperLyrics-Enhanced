/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

import org.junit.Assert.assertTrue
import org.junit.Test

class LyricTextPaintOwnerTest {
    @Test
    fun `standard and split lyric lines expose their text paint for host decorations`() {
        assertTrue(LyricTextPaintOwner::class.java.isAssignableFrom(LyricLineView::class.java))
        assertTrue(
            LyricTextPaintOwner::class.java.isAssignableFrom(SpaceGateLyricLineView::class.java)
        )
    }
}
