/*
 * Copyright 2026 juren233
 * Licensed under the GNU General Public License v3.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HyperOsBlurRuntimeIdentifiersTest {
    @Test
    fun `runtime names match the original fan miuix dex`() {
        assertEquals(
            setOf(
                "addMiBackgroundBlendColor",
                "clearMiBackgroundBlendColor",
                "setMiBackgroundBlurMode",
                "setMiBackgroundBlurRadius",
                "setMiViewBlurMode",
            ),
            HyperOsBlurRuntimeIdentifiers.exactNames,
        )
    }

    @Test
    fun `decompiler style aliases are not accepted`() {
        assertFalse(HyperOsBlurRuntimeIdentifiers.exactNames.contains("addBackgroundBlenderColor"))
        assertFalse(HyperOsBlurRuntimeIdentifiers.exactNames.contains("setBackgroundBlurRadius"))
        assertFalse(HyperOsBlurRuntimeIdentifiers.exactNames.contains("setViewBlurMode"))
    }

    @Test
    fun `card opacity restores while the flowing background fades`() {
        assertEquals(0.40f, aboutCardContainerAlpha(0f), 0.0001f)
        assertEquals(0.70f, aboutCardContainerAlpha(0.5f), 0.0001f)
        assertEquals(1f, aboutCardContainerAlpha(1f), 0.0001f)
        assertEquals(0.40f, aboutCardContainerAlpha(-1f), 0.0001f)
        assertEquals(1f, aboutCardContainerAlpha(2f), 0.0001f)
    }
}
