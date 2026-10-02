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
import android.graphics.Rect
import android.graphics.RectF
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
    private val promotionSlice = SpaceGatePromotionShadowSlice()
    private val promotionSource = Rect()
    private val promotionDestination = RectF()

    /** Warm the immutable target-font mask before an animation starts; retargets reuse its key. */
    fun preparePromotion(target: SpaceGatePromotionSnapshot, shadowStyle: TextPaint): Bitmap? {
        val radius = shadowStyle.getShadowLayerRadius()
        val width = target.geometry.glyphs.lastOrNull()?.naturalEnd ?: return null
        if (radius <= 0f || width <= 0f || target.text.isEmpty()) return null
        return ensureShadowBitmap(target.text, width, target.paint, target.typefaceSelector,
            target.fontSignature, radius)
    }

    /** The animation has no progress clip: shadow and unplayed glyphs share only the camera fade. */
    fun drawPromotion(
        canvas: Canvas, target: SpaceGatePromotionSnapshot, geometry: SpaceGatePromotionGeometry,
        fraction: Float, baseline: Float, scaleY: Float, shadowStyle: TextPaint, alpha: Float,
    ) {
        if (alpha <= 0f) return
        val bitmap = preparePromotion(target, shadowStyle) ?: return
        val color = shadowStyle.getShadowLayerColor()
        if (bitmapPaintColor != color) {
            bitmapPaintColor = color
            bitmapPaint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        bitmapPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        val origin = maskPadding - extractedOffsetX
        val top = baseline + (target.paint.fontMetrics.ascent - maskPadding + extractedOffsetY) * scaleY +
            shadowStyle.getShadowLayerDy()
        val bottom = top + bitmap.height * scaleY
        for ((index, glyph) in target.geometry.glyphs.withIndex()) {
            if (!promotionSlice.update(
                    glyph.naturalStart, glyph.naturalEnd, geometry.start(index, fraction), geometry.end(index, fraction),
                    origin, bitmap.width, index == 0, index == target.geometry.glyphs.lastIndex,
                    shadowStyle.getShadowLayerDx(),
                )) continue
            promotionSource.set(promotionSlice.sourceLeft, 0, promotionSlice.sourceRight, bitmap.height)
            promotionDestination.set(promotionSlice.left, top, promotionSlice.right, bottom)
            canvas.withSeamFade(geometry.fade(index, fraction), bitmapPaint) {
                canvas.drawBitmap(bitmap, promotionSource, promotionDestination, bitmapPaint)
            }
        }
    }

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
        seamPlan: SeamStripPlan? = null,
        seamLayout: SeamOcclusionLayout? = null,
        layoutWidth: Float = model.width,
        drawGhost: Boolean = true,
    ) {
        val shadowRadius = sourcePaint.getShadowLayerRadius()
        val text = if (model.isPlainText) model.text else model.wordText
        if (shadowRadius <= 0f || text.isEmpty() || model.width <= 0f) return

        val laidWidth = layoutWidth
        val bitmap = ensureShadowBitmap(
            text = text,
            textWidth = model.width,
            sourcePaint = sourcePaint,
            typefaceSelector = typefaceSelector,
            fontSignature = fontSignature,
            shadowRadius = shadowRadius,
        ) ?: return

        val startX = resolveShadowTextStartX(
            textWidth = laidWidth,
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

        drawSeamSlices(
            canvas = canvas,
            bitmap = bitmap,
            layout = seamLayout,
            plan = seamPlan,
            startX = startX,
            textTop = textTop,
            shadowDx = sourcePaint.getShadowLayerDx(),
            shadowDy = sourcePaint.getShadowLayerDy(),
        )
        if (!drawGhost) return
        resolveShadowGhostStartX(
            primaryStartX = startX,
            textWidth = laidWidth,
            viewWidth = viewWidth.toFloat(),
            ghostSpacing = ghostSpacing,
            isPlainText = model.isPlainText,
        )?.let { ghostStartX ->
            // ghost 只在滚动中出现：纯过缝方案，跨缝渐隐与正文同源。
            val ghostPlan = if (seamPlan == null) null else
                seamLayout?.let { SeamStripPlan.transit(it, ghostStartX, seamPlan.seamX) }
            drawSeamSlices(
                canvas = canvas,
                bitmap = bitmap,
                layout = seamLayout,
                plan = ghostPlan,
                startX = ghostStartX,
                textTop = textTop,
                shadowDx = sourcePaint.getShadowLayerDx(),
                shadowDy = sourcePaint.getShadowLayerDy(),
            )
        }
    }

    /**
     * 按接缝方案切片绘制阴影位图（与正文同源）：每段带一片、平移量与正文
     * 一致；跨缝单元单独一片，按渐隐 alpha 与多数侧裁剪（裁剪用绝对缝坐
     * 标）。各片保留自己字形的完整模糊边缘，段界处不重不漏。
     */
    private fun drawSeamSlices(
        canvas: Canvas,
        bitmap: Bitmap,
        layout: SeamOcclusionLayout?,
        plan: SeamStripPlan?,
        startX: Float,
        textTop: Float,
        shadowDx: Float,
        shadowDy: Float,
    ) {
        if (plan == null || layout == null) {
            drawShadowBitmap(canvas, bitmap, startX, textTop, shadowDx, shadowDy)
            return
        }
        // 位图 x 与条带文本 x 的换算：text x=0 位于 maskPadding − extractedOffsetX。
        val textOriginInBitmap = maskPadding - extractedOffsetX
        val dstTop = textTop - maskPadding + extractedOffsetY + shadowDy
        val dstBottom = dstTop + bitmap.height
        val totalAdvance = layout.totalAdvance

        for (i in plan.bands.indices) {
            val band = plan.bands[i]
            if (band.charEnd <= band.charStart) continue
            val naturalEnd = if (i + 1 < plan.bands.size) plan.bands[i + 1].startAdvance else totalAdvance
            val st = plan.straddler
            if (st != null && st.unit.charStart >= band.charStart && st.unit.charEnd <= band.charEnd) {
                drawSlice(canvas, bitmap, band.startAdvance, st.unit.start, band.delta, startX, dstTop, dstBottom, shadowDx, textOriginInBitmap, null)
                // 跨缝切片：多数侧裁剪（绝对缝坐标）＋渐隐，与正文同 alpha。
                val fade = SeamStripPlan.SeamFade(st.alpha, plan.seamX, st.majorityLeft)
                drawSlice(canvas, bitmap, st.unit.start, st.unit.end, band.delta, startX, dstTop, dstBottom, shadowDx, textOriginInBitmap, fade)
                drawSlice(canvas, bitmap, st.unit.end, naturalEnd, band.delta, startX, dstTop, dstBottom, shadowDx, textOriginInBitmap, null)
            } else {
                drawSlice(canvas, bitmap, band.startAdvance, naturalEnd, band.delta, startX, dstTop, dstBottom, shadowDx, textOriginInBitmap, null)
            }
        }
    }

    private fun drawSlice(
        canvas: Canvas,
        bitmap: Bitmap,
        naturalStart: Float,
        naturalEnd: Float,
        delta: Float,
        startX: Float,
        dstTop: Float,
        dstBottom: Float,
        shadowDx: Float,
        textOriginInBitmap: Int,
        fade: SeamStripPlan.SeamFade?,
    ) {
        if (naturalEnd <= naturalStart) return
        val srcLeft = (naturalStart + textOriginInBitmap).toInt().coerceIn(0, bitmap.width)
        val srcRight = (naturalEnd + textOriginInBitmap).toInt().coerceIn(0, bitmap.width)
        if (srcRight <= srcLeft) return
        val drawX = startX + delta + naturalStart + shadowDx
        canvas.withSeamFade(fade, bitmapPaint) {
            val src = Rect(srcLeft, 0, srcRight, bitmap.height)
            val dst = RectF(drawX, dstTop, drawX + (srcRight - srcLeft), dstBottom)
            canvas.drawBitmap(bitmap, src, dst, bitmapPaint)
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
