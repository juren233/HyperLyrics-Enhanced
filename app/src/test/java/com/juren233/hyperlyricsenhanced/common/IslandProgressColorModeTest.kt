/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandProgressColorModeTest {
    @Test
    fun `explicit mode wins over legacy switches`() {
        assertEquals(
            RootConstants.ISLAND_PROGRESS_COLOR_MODE_MONET,
            IslandProgressColorMode.resolve(
                storedMode = RootConstants.ISLAND_PROGRESS_COLOR_MODE_MONET,
                legacyProgressEnabled = false,
                legacyCoverEnabled = true,
                legacyCoverGradient = true,
            )
        )
    }

    @Test
    fun `legacy progress settings migrate to equivalent color modes`() {
        assertEquals(
            RootConstants.ISLAND_PROGRESS_COLOR_MODE_DISABLED,
            IslandProgressColorMode.resolve(
                storedMode = IslandProgressColorMode.UNSPECIFIED,
                legacyProgressEnabled = false,
                legacyCoverEnabled = true,
                legacyCoverGradient = true,
            )
        )
        assertEquals(
            RootConstants.ISLAND_PROGRESS_COLOR_MODE_SYSTEM_BLUE,
            IslandProgressColorMode.resolve(
                storedMode = IslandProgressColorMode.UNSPECIFIED,
                legacyProgressEnabled = true,
                legacyCoverEnabled = false,
                legacyCoverGradient = false,
            )
        )
        assertEquals(
            RootConstants.ISLAND_PROGRESS_COLOR_MODE_COVER,
            IslandProgressColorMode.resolve(
                storedMode = IslandProgressColorMode.UNSPECIFIED,
                legacyProgressEnabled = true,
                legacyCoverEnabled = true,
                legacyCoverGradient = false,
            )
        )
        assertEquals(
            RootConstants.ISLAND_PROGRESS_COLOR_MODE_COVER_GRADIENT,
            IslandProgressColorMode.resolve(
                storedMode = IslandProgressColorMode.UNSPECIFIED,
                legacyProgressEnabled = true,
                legacyCoverEnabled = true,
                legacyCoverGradient = true,
            )
        )
    }

    @Test
    fun `enabled and cover helpers follow the unified mode`() {
        assertFalse(IslandProgressColorMode.isEnabled(RootConstants.ISLAND_PROGRESS_COLOR_MODE_DISABLED))
        assertTrue(IslandProgressColorMode.isEnabled(RootConstants.ISLAND_PROGRESS_COLOR_MODE_CUSTOM))
        assertFalse(IslandProgressColorMode.usesCover(RootConstants.ISLAND_PROGRESS_COLOR_MODE_MONET))
        assertTrue(IslandProgressColorMode.usesCover(RootConstants.ISLAND_PROGRESS_COLOR_MODE_COVER))
        assertTrue(
            IslandProgressColorMode.usesCoverGradient(
                RootConstants.ISLAND_PROGRESS_COLOR_MODE_COVER_GRADIENT
            )
        )
    }
}
