/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

internal class LineState {
    var scrollOffset: Float = 0f
    var isScrollFinished: Boolean = false

    fun reset() {
        scrollOffset = 0f
        isScrollFinished = false
    }
}
