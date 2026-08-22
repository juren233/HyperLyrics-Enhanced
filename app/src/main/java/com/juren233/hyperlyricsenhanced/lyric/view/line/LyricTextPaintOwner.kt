/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

import android.text.TextPaint

/** Exposes the paint used by custom lyric views so host styles can decorate every renderer. */
interface LyricTextPaintOwner {
    val textPaint: TextPaint

    fun forEachDrawingTextPaint(action: (TextPaint) -> Unit) {
        action(textPaint)
    }
}
