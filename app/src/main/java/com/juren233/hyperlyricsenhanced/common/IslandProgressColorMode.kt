/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

/** Resolves the unified edge-progress color mode while preserving legacy preferences. */
object IslandProgressColorMode {
    const val UNSPECIFIED = -1

    fun resolve(
        storedMode: Int,
        legacyProgressEnabled: Boolean,
        legacyCoverEnabled: Boolean,
        legacyCoverGradient: Boolean,
    ): Int {
        if (storedMode in RootConstants.ISLAND_PROGRESS_COLOR_MODE_DISABLED..RootConstants.ISLAND_PROGRESS_COLOR_MODE_CUSTOM) {
            return storedMode
        }
        if (!legacyProgressEnabled) return RootConstants.ISLAND_PROGRESS_COLOR_MODE_DISABLED
        if (!legacyCoverEnabled) return RootConstants.ISLAND_PROGRESS_COLOR_MODE_SYSTEM_BLUE
        return if (legacyCoverGradient) {
            RootConstants.ISLAND_PROGRESS_COLOR_MODE_COVER_GRADIENT
        } else {
            RootConstants.ISLAND_PROGRESS_COLOR_MODE_COVER
        }
    }

    fun isEnabled(mode: Int): Boolean =
        mode in RootConstants.ISLAND_PROGRESS_COLOR_MODE_SYSTEM_BLUE..RootConstants.ISLAND_PROGRESS_COLOR_MODE_CUSTOM

    fun usesCover(mode: Int): Boolean =
        mode == RootConstants.ISLAND_PROGRESS_COLOR_MODE_COVER ||
            mode == RootConstants.ISLAND_PROGRESS_COLOR_MODE_COVER_GRADIENT

    fun usesCoverGradient(mode: Int): Boolean =
        mode == RootConstants.ISLAND_PROGRESS_COLOR_MODE_COVER_GRADIENT
}
