/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view.line

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.text.TextPaint
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.lyric.view.line.model.LyricModel
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import kotlin.math.ceil

/**
 * Draws one cached, progress-independent text shadow behind the normal lyric renderer.
 * No business color Paint, gradient Shader, highlight mask, or progress clip is modified.
 */
internal class LineShadowRenderer {
    private companion object {
        const val TAG = "LineShadowRenderer"
    }

    private data class CacheKey(
        val text: String,
        val textWidthBits: Int,
        val textSizeBits: Int,
        val textScaleXBits: Int,
        val textSkewXBits: Int,
        val fakeBold: Boolean,
        val typefaceIdentity: Int,
        val fontSignature: Int,
        val shadowRadiusBits: Int,
    )

    private var cacheKey: CacheKey? = null
    private var shadowBitmap: Bitmap? = null
    private var maskPadding = 0
    private var extractedOffsetX = 0
    private var extractedOffsetY = 0
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmapPaintColor: Int? = null

    fun draw(
        canvas: Canvas,
        model: LyricModel,
        sourcePaint: TextPaint,
        typefaceSelector: ((Char) -> Typeface)?,
        fontSignature: Int,
        viewWidth: Int,
        viewHeight: Int,
        scrollOffset: Float,
        centerIfPossible: Boolean,
        alignRight: Boolean,
        ghostSpacing: Float,
    ) {
        val shadowRadius = sourcePaint.getShadowLayerRadius()
        val text = if (model.isPlainText) model.text else model.wordText
        if (shadowRadius <= 0f || text.isEmpty() || model.width <= 0f) return

        val bitmap = ensureShadowBitmap(
            text = text,
            textWidth = model.width,
            sourcePaint = sourcePaint,
            typefaceSelector = typefaceSelector,
            fontSignature = fontSignature,
            shadowRadius = shadowRadius,
        ) ?: return

        val startX = resolveShadowTextStartX(
            textWidth = model.width,
            viewWidth = viewWidth.toFloat(),
            scrollOffset = scrollOffset,
            isPlainText = model.isPlainText,
            isAlignedRight = model.isAlignedRight,
            centerIfPossible = centerIfPossible,
            alignRight = alignRight,
        )
        val baselineY = resolveTextBaseline(sourcePaint, viewHeight)
        val textTop = baselineY + sourcePaint.fontMetrics.ascent
        val shadowColor = sourcePaint.getShadowLayerColor()
        if (bitmapPaintColor != shadowColor) {
            bitmapPaintColor = shadowColor
            bitmapPaint.color = shadowColor
            bitmapPaint.alpha = 255
            bitmapPaint.colorFilter = PorterDuffColorFilter(shadowColor, PorterDuff.Mode.SRC_IN)
        }

        drawShadowBitmap(
            canvas = canvas,
            bitmap = bitmap,
            textStartX = startX,
            textTop = textTop,
            shadowDx = sourcePaint.getShadowLayerDx(),
            shadowDy = sourcePaint.getShadowLayerDy(),
        )
        resolveShadowGhostStartX(
            primaryStartX = startX,
            textWidth = model.width,
            viewWidth = viewWidth.toFloat(),
            ghostSpacing = ghostSpacing,
            isPlainText = model.isPlainText,
        )?.let { ghostStartX ->
            drawShadowBitmap(
                canvas = canvas,
                bitmap = bitmap,
                textStartX = ghostStartX,
                textTop = textTop,
                shadowDx = sourcePaint.getShadowLayerDx(),
                shadowDy = sourcePaint.getShadowLayerDy(),
            )
        }
    }

    fun clear() {
        shadowBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        shadowBitmap = null
        cacheKey = null
        maskPadding = 0
        extractedOffsetX = 0
        extractedOffsetY = 0
    }

    private fun ensureShadowBitmap(
        text: String,
        textWidth: Float,
        sourcePaint: TextPaint,
        typefaceSelector: ((Char) -> Typeface)?,
        fontSignature: Int,
        shadowRadius: Float,
    ): Bitmap? {
        val key = CacheKey(
            text = text,
            textWidthBits = textWidth.toBits(),
            textSizeBits = sourcePaint.textSize.toBits(),
            textScaleXBits = sourcePaint.textScaleX.toBits(),
            textSkewXBits = sourcePaint.textSkewX.toBits(),
            fakeBold = sourcePaint.isFakeBoldText,
            typefaceIdentity = System.identityHashCode(sourcePaint.typeface),
            fontSignature = fontSignature,
            shadowRadiusBits = shadowRadius.toBits(),
        )
        shadowBitmap?.takeIf { cacheKey == key && !it.isRecycled }?.let { return it }

        clear()
        return runCatching {
            val metrics = sourcePaint.fontMetrics
            val padding = ceil(shadowRadius * 2f).toInt().coerceAtLeast(2) + 2
            val width = ceil(textWidth).toInt().coerceAtLeast(1) + padding * 2
            val height = ceil(metrics.descent - metrics.ascent).toInt().coerceAtLeast(1) + padding * 2
            val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            val maskPaint = TextPaint(sourcePaint).apply {
                shader = null
                clearShadowLayer()
                color = Color.WHITE
                alpha = 255
            }
            val maskCanvas = Canvas(mask)
            val baseline = padding - metrics.ascent
            if (typefaceSelector != null) {
                MixedTypefaceText.drawText(maskCanvas, text, padding.toFloat(), baseline, maskPaint, typefaceSelector)
            } else {
                maskCanvas.drawText(text, padding.toFloat(), baseline, maskPaint)
            }

            val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(shadowRadius, BlurMaskFilter.Blur.NORMAL)
            }
            val offset = IntArray(2)
            val blurred = mask.extractAlpha(blurPaint, offset)
            mask.recycle()

            cacheKey = key
            shadowBitmap = blurred
            maskPadding = padding
            extractedOffsetX = offset[0]
            extractedOffsetY = offset[1]
            if (BuildConfig.DEBUG) {
                HookLogger.i(
                    TAG,
                    "[GradientShadowDiag] mask_built textHash=" +
                        text.hashCode().toUInt().toString(16) +
                        ",textWidth=$textWidth,mask=${blurred.width}x${blurred.height}," +
                        "radius=$shadowRadius,font=$fontSignature",
                )
            }
            blurred
        }.getOrNull()
    }

    private fun drawShadowBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        textStartX: Float,
        textTop: Float,
        shadowDx: Float,
        shadowDy: Float,
    ) {
        canvas.drawBitmap(
            bitmap,
            textStartX - maskPadding + extractedOffsetX + shadowDx,
            textTop - maskPadding + extractedOffsetY + shadowDy,
            bitmapPaint,
        )
    }
}

internal fun resolveShadowTextStartX(
    textWidth: Float,
    viewWidth: Float,
    scrollOffset: Float,
    isPlainText: Boolean,
    isAlignedRight: Boolean,
    centerIfPossible: Boolean,
    alignRight: Boolean,
): Float {
    if (isPlainText) {
        return resolvePlainTextOffset(
            textWidth = textWidth,
            viewWidth = viewWidth,
            scrollOffset = scrollOffset,
            isAlignedRight = isAlignedRight,
            centerIfPossible = centerIfPossible,
            alignRight = alignRight,
        )
    }
    return when {
        textWidth > viewWidth -> scrollOffset
        alignRight -> viewWidth - textWidth
        centerIfPossible -> (viewWidth - textWidth) / 2f
        isAlignedRight -> viewWidth - textWidth
        else -> 0f
    }
}

internal fun resolveShadowGhostStartX(
    primaryStartX: Float,
    textWidth: Float,
    viewWidth: Float,
    ghostSpacing: Float,
    isPlainText: Boolean,
): Float? {
    if (!isPlainText || textWidth <= viewWidth) return null
    val rightEdge = primaryStartX + textWidth
    if (rightEdge >= viewWidth) return null
    return (rightEdge + ghostSpacing).takeIf { it < viewWidth }
}

private fun resolveTextBaseline(paint: TextPaint, viewHeight: Int): Float {
    val metrics = paint.fontMetrics
    return (viewHeight - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
}

internal inline fun TextPaint.withoutShadowLayer(draw: () -> Unit) {
    val radius = getShadowLayerRadius()
    if (radius <= 0f) {
        draw()
        return
    }
    val dx = getShadowLayerDx()
    val dy = getShadowLayerDy()
    val color = getShadowLayerColor()
    clearShadowLayer()
    try {
        draw()
    } finally {
        setShadowLayer(radius, dx, dy, color)
    }
}
