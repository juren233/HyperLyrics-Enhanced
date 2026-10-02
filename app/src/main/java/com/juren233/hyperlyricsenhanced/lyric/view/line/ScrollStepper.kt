/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

internal class ScrollStepper {
    fun compute(
        highlightWidth: Float,
        lineWidth: Float,
        viewWidth: Float,
        isFinished: Boolean,
        isScrollFinished: Boolean,
        followAnchor: Float = viewWidth / 2f,
    ): Float {
        if (lineWidth <= viewWidth) return 0f
        val minScroll = -(lineWidth - viewWidth)
        if (isFinished) return minScroll
        val anchor = followAnchor.coerceIn(0f, viewWidth.coerceAtLeast(0f))
        return if (highlightWidth > anchor) {
            (anchor - highlightWidth).coerceIn(minScroll, 0f)
        } else 0f
    }
}

/** 全岛按实际右槽定位跟随点；无有效接缝时保留普通逐字行的中点语义。 */
internal fun resolveSpaceGateFollowAnchor(viewWidth: Float, seamX: Float?): Float {
    if (seamX == null || !seamX.isFinite() || seamX <= 0f || seamX >= viewWidth) {
        return viewWidth / 2f
    }
    return seamX + (viewWidth - seamX) / 2f
}
