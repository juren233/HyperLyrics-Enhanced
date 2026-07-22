/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine

data class RichLyricLineModel(private val source: IRichLyricLine) : IRichLyricLine by source {
    var previous: RichLyricLineModel? = null
    var next: RichLyricLineModel? = null
}

