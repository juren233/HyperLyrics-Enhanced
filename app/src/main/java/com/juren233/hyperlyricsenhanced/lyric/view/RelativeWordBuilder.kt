/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.ILyricTiming

internal class RelativeWordBuilder {
    fun build(
        timing: ILyricTiming,
        text: String?,
        words: List<LyricWord>?
    ): List<LyricWord>? {
        if (words.isNullOrEmpty() && !text.isNullOrBlank()
            && timing.begin < timing.end && timing.begin >= 0
        ) {
            return listOf(
                LyricWord(
                    text = text,
                    begin = timing.begin,
                    end = timing.end,
                    duration = timing.duration
                )
            )
        }
        return words
    }
}


