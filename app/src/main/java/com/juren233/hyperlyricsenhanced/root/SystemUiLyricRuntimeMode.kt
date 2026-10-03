/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root

/** Apple page enrichment needs its direct bridge, but no shared player subscription. */
internal enum class SystemUiLyricRuntimeMode {
    DISABLED,
    APPLE_DIRECT,
    LYRIC_DISPLAY;

    val requiresCentral: Boolean get() = this == LYRIC_DISPLAY

    fun sourceId(selectedSourceId: String): String? = when (this) {
        DISABLED -> null
        APPLE_DIRECT -> "lyricon"
        LYRIC_DISPLAY -> selectedSourceId
    }

    companion object {
        fun resolve(
            hyperIsland: Boolean,
            aodLyrics: Boolean,
            dynamicIsland: Boolean,
            nativeAppleTranslation: Boolean,
            fillMissingAppleLyrics: Boolean,
            lunaBeatWordLyrics: Boolean,
        ): SystemUiLyricRuntimeMode = when {
            hyperIsland || aodLyrics || dynamicIsland -> LYRIC_DISPLAY
            nativeAppleTranslation || fillMissingAppleLyrics || lunaBeatWordLyrics -> APPLE_DIRECT
            else -> DISABLED
        }
    }
}
