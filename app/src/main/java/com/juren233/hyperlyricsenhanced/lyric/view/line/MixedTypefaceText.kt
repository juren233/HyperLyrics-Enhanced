package com.juren233.hyperlyricsenhanced.lyric.view.line

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * 按字符选择 Typeface 的文本测量/绘制工具。
 *
 * 用于“英数窄字体”逐字符切换：同一行里英文/数字用窄字体，CJK 用基础字体。
 */
internal object MixedTypefaceText {

    fun typefaceSelector(base: Typeface, narrow: Typeface?): ((Char) -> Typeface)? {
        narrow ?: return null
        return { ch -> if (ch.isCjk()) base else narrow }
    }

    fun measureText(
        paint: Paint,
        text: String,
        selector: (Char) -> Typeface
    ): Float {
        if (text.isEmpty()) return 0f
        var total = 0f
        val original = paint.typeface
        try {
            var start = 0
            var currentTypeface = selector(text[0])
            paint.typeface = currentTypeface
            for (i in 1..text.length) {
                if (i == text.length || selector(text[i]) != currentTypeface) {
                    total += paint.measureText(text, start, i)
                    if (i < text.length) {
                        currentTypeface = selector(text[i])
                        paint.typeface = currentTypeface
                        start = i
                    }
                }
            }
        } finally {
            paint.typeface = original
        }
        return total
    }

    fun getTextWidths(
        paint: Paint,
        text: String,
        selector: (Char) -> Typeface,
        widths: FloatArray
    ) {
        require(widths.size >= text.length)
        if (text.isEmpty()) return
        val original = paint.typeface
        try {
            var start = 0
            var currentTypeface = selector(text[0])
            paint.typeface = currentTypeface
            for (i in 1..text.length) {
                if (i == text.length || selector(text[i]) != currentTypeface) {
                    val runLength = i - start
                    val tmp = FloatArray(runLength)
                    paint.getTextWidths(text, start, i, tmp)
                    System.arraycopy(tmp, 0, widths, start, runLength)
                    if (i < text.length) {
                        currentTypeface = selector(text[i])
                        paint.typeface = currentTypeface
                        start = i
                    }
                }
            }
        } finally {
            paint.typeface = original
        }
    }

    fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: Paint,
        selector: (Char) -> Typeface
    ) {
        if (text.isEmpty()) return
        val original = paint.typeface
        try {
            var drawX = x
            var start = 0
            var currentTypeface = selector(text[0])
            paint.typeface = currentTypeface
            for (i in 1..text.length) {
                if (i == text.length || selector(text[i]) != currentTypeface) {
                    val run = text.substring(start, i)
                    canvas.drawText(run, drawX, y, paint)
                    drawX += paint.measureText(run)
                    if (i < text.length) {
                        currentTypeface = selector(text[i])
                        paint.typeface = currentTypeface
                        start = i
                    }
                }
            }
        } finally {
            paint.typeface = original
        }
    }

    private fun Char.isCjk(): Boolean {
        val block = Character.UnicodeBlock.of(this)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            block == Character.UnicodeBlock.HANGUL_JAMO ||
            block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
    }
}
