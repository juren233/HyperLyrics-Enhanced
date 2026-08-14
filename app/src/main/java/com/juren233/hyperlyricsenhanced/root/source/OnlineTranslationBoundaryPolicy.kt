/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine

/** Chooses a real lyric boundary instead of guessing with a fixed delay. */
internal object OnlineTranslationBoundaryPolicy {
    fun nextCommitPosition(
        lines: List<RichLyricLine>,
        currentPosition: Long,
    ): Long? {
        val meaningfulLines = lines
            .asSequence()
            .filter { !it.text.isNullOrBlank() }
            .sortedBy(RichLyricLine::begin)
            .toList()
        if (meaningfulLines.isEmpty()) return null

        if (meaningfulLines.any { it.begin == currentPosition }) return currentPosition
        meaningfulLines.firstOrNull { it.begin > currentPosition }?.let { return it.begin }
        val currentLine = meaningfulLines.lastOrNull { line ->
            line.begin <= currentPosition && (line.end <= line.begin || currentPosition < line.end)
        }
        return currentLine?.end?.takeIf { it > currentPosition }
    }
}
