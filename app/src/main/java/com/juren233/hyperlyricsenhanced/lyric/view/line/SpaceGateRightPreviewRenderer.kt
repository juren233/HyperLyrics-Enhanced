/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import com.juren233.hyperlyricsenhanced.lyric.view.line.model.LyricModel

/** An untimed suffix: no word navigator, progress animator, or second row. */
internal class SpaceGateRightPreviewRenderer {
    private var model = LyricModel(text = "", words = emptyList())
    private val paint = TextPaint()
    private val shadow = LineShadowRenderer()
    private var runs = emptyList<PreparedTextRun<Typeface>>()
    private var styleKey: List<Any?>? = null
    private var selector: ((Char) -> Typeface)? = null
    private var fontSignature = 0
    private var units: SeamOcclusionLayout? = null

    val hasText: Boolean get() = model.text.isNotBlank()
    val text: String get() = model.text
    val width: Float get() = model.width

    fun bind(text: String?) {
        if (model.text == text.orEmpty()) return
        model = LyricModel(text = text.orEmpty(), words = emptyList())
        styleKey = null
        runs = emptyList()
        units = null
        shadow.clear()
    }

    fun configure(source: TextPaint, fonts: ((Char) -> Typeface)?, colors: IntArray, signature: Int) {
        if (!hasText) return
        val key = listOf(source.textSize, source.textScaleX, source.textSkewX,
            source.isFakeBoldText, signature, colors.contentHashCode())
        if (styleKey == key) return
        styleKey = key
        selector = fonts
        fontSignature = signature
        paint.set(source)
        paint.clearShadowLayer()
        paint.color = colors.firstOrNull() ?: Color.GRAY
        model.updateSizes(paint, fonts)
        val widths = FloatArray(model.text.length)
        if (fonts != null) MixedTypefaceText.getTextWidths(paint, model.text, fonts, widths)
        else paint.getTextWidths(model.text, widths)
        units = SeamOcclusionLayout.build(model.text, widths)
        paint.shader = if (colors.size > 1) {
            LinearGradient(0f, 0f, model.width.coerceAtLeast(1f), 0f, colors, null, Shader.TileMode.CLAMP)
        } else null
        val base = source.typeface ?: Typeface.DEFAULT
        runs = prepareTextRuns(model.text, 0f, { fonts?.invoke(it) ?: base }) { text, font ->
            paint.typeface = font
            paint.measureText(text)
        }
        paint.typeface = base
    }

    fun snapshot(start: Float, seam: Float, viewWidth: Float, height: Int, fadingEdge: Float): SpaceGatePromotionSnapshot? {
        val units = units ?: return null
        val endpoint = SpaceGatePromotionGeometry.endpoint(units, model.width, viewWidth, 0f)
        val fm = paint.fontMetrics
        return SpaceGatePromotionSnapshot(
            text = model.text,
            geometry = SpaceGatePromotionGeometry.translated(endpoint, start).copy(seam = seam),
            paint = TextPaint(paint), typefaceSelector = selector,
            baseline = (height - (fm.descent - fm.ascent)) / 2f - fm.ascent,
            fadingEdgeLength = fadingEdge, fontSignature = fontSignature,
        )
    }

    fun draw(canvas: Canvas, start: Float, seam: Float, viewWidth: Int, height: Int, shadowStyle: TextPaint) {
        if (!hasText || start >= viewWidth || start + width <= seam) return
        canvas.withSave {
            clipRect(seam, 0f, viewWidth.toFloat(), height.toFloat())
            withTranslation(x = start) {
                shadow.draw(this, model, shadowStyle, selector, fontSignature,
                    viewWidth = 0, viewHeight = height, scrollOffset = 0f,
                    centerIfPossible = false, alignRight = false, ghostSpacing = 0f,
                    drawGhost = false)
                val fm = paint.fontMetrics
                val baseline = (height - (fm.descent - fm.ascent)) / 2f - fm.ascent
                val base = paint.typeface
                for (run in runs) {
                    paint.typeface = run.font
                    drawText(run.text, run.x, baseline, paint)
                }
                paint.typeface = base
            }
        }
    }
}
