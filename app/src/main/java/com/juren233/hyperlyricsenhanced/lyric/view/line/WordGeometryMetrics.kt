package com.juren233.hyperlyricsenhanced.lyric.view.line

import android.graphics.Paint
import android.graphics.Typeface

/** Private, immutable native measurement snapshot for the known CJK/narrow-font policy only. */
internal class WordGeometryMetrics(paint: Paint, private val base: Typeface, private val narrow: Typeface?) {
    private val paint = Paint(paint)

    fun matches(other: Paint, base: Typeface, narrow: Typeface?): Boolean =
        this.base === base && this.narrow === narrow && paint.equalsForTextMeasurement(other) &&
            // Native measurement equality omits these edits, but text advances consume them.
            paint.startHyphenEdit == other.startHyphenEdit && paint.endHyphenEdit == other.endHyphenEdit
}
