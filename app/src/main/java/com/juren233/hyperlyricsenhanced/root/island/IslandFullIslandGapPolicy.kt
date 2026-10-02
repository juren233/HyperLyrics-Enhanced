/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.view.isTitleLine

internal object IslandFullIslandGapPolicy {
    // Read the source line before presentation adds a fallback title or translation.
    // Null also covers the interval before the first lyric/placeholder has arrived.
    fun usesIndependentSlots(activeMode: Int, sourceLine: IRichLyricLine?): Boolean =
        (activeMode == RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND ||
            activeMode == RootConstants.HOOK_LYRIC_MODE_SEPARATED) && (
            sourceLine == null || sourceLine.isTitleLine() ||
                sourceLine.metadata?.getBoolean(LyricMetadataKeys.INSTRUMENTAL) == true
            )

    fun contentMode(mode: Int, isLeft: Boolean, independentSlots: Boolean): Int =
        if (mode == 7 && isLeft && independentSlots) 5 else mode
}
