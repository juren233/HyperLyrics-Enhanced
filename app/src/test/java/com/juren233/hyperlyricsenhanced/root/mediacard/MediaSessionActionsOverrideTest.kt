/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionActionsOverrideTest {
    @Test
    fun `forces session actions only for listed packages the system left on notification actions`() {
        assertTrue(MediaSessionActionsOverride.shouldForce("com.tencent.freemusic", original = false))
        assertFalse(MediaSessionActionsOverride.shouldForce("com.tencent.freemusic", original = true))
        assertFalse(MediaSessionActionsOverride.shouldForce("com.tencent.qqmusic", original = false))
        assertFalse(MediaSessionActionsOverride.shouldForce(null, original = false))
    }
}
