/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

/** Font changes and their advances are immutable for the lifetime of a moving glyph. */
internal data class PreparedTextRun<Font>(val text: String, val x: Float, val font: Font)

internal fun <Font> prepareTextRuns(
    text: String,
    x: Float,
    fontAt: (Char) -> Font,
    measure: (String, Font) -> Float,
): List<PreparedTextRun<Font>> {
    if (text.isEmpty()) return emptyList()
    val runs = ArrayList<PreparedTextRun<Font>>()
    var start = 0
    var drawX = x
    var font = fontAt(text[0])
    for (index in 1..text.length) {
        val nextFont = if (index < text.length) fontAt(text[index]) else font
        if (index == text.length || nextFont != font) {
            val run = text.substring(start, index)
            runs += PreparedTextRun(run, drawX, font)
            if (index < text.length) {
                drawX += measure(run, font)
                font = nextFont
                start = index
            }
        }
    }
    return runs
}
