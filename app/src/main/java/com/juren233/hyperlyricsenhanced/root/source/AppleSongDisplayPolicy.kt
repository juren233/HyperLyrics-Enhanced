/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.model.Song

/** Keeps display-only preprocessing from mutating the native Apple song state. */
internal object AppleSongDisplayPolicy {
    fun copyForDisplay(song: Song?): Song? = song?.deepCopy()
}
