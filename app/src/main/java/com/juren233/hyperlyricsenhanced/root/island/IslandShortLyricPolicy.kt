/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.common.RootConstants

internal object IslandShortLyricPolicy {
    fun usesSongInfo(
        activeMode: Int,
        enabled: Boolean,
        contentWidth: Int,
        rightSlotWidth: Int,
    ): Boolean = activeMode == RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND && enabled &&
        contentWidth > 0 && rightSlotWidth > 0 && contentWidth <= rightSlotWidth
}
