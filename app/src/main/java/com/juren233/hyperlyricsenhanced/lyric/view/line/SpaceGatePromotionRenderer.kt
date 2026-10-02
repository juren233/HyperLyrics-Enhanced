/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import androidx.core.graphics.withSave
import com.juren233.hyperlyricsenhanced.lyric.model.LyricLine

internal data class SpaceGatePromotionSnapshot(
    val text: String,
    val geometry: SpaceGatePromotionGeometry.Endpoint,
    val paint: TextPaint,
    val typefaceSelector: ((Char) -> Typeface)?,
    val baseline: Float,
    val fadingEdgeLength: Float,
    val textScaleY: Float = 1f,
    val fontSignature: Int = 0,
    val shadowAlpha: Float = 1f,
    val shadows: SpaceGatePromotionShadows = SpaceGatePromotionShadows(),
)

/** Copies of a flying snapshot retain these masks when only the viewport geometry changes. */
internal class SpaceGatePromotionShadows {
    val left = LineShadowRenderer()
    val right = LineShadowRenderer()
}

/** An uncut text strip drawn twice through the two stationary physical slots. */
internal class SpaceGatePromotionRenderer(
    private val source: SpaceGatePromotionSnapshot,
    private val target: SpaceGatePromotionSnapshot,
    private val sourceTop: Int,
    private val targetTop: Int,
    private val targetLine: LyricLine,
    private val sourceAlpha: Float,
) {
    val geometry = SpaceGatePromotionGeometry(source.geometry, target.geometry)
    var fraction = 0f
    // Shadows use the same independent alpha-mask path as the steady-state lyric, not its Shader.
    private val paint = TextPaint(source.paint).apply { clearShadowLayer() }
    private val glyphRuns = TextPaint(source.paint).let { measurePaint ->
        source.geometry.glyphs.map { glyph ->
            prepareTextRuns(
                source.text.substring(glyph.charStart, glyph.charEnd), glyph.naturalStart,
                fontAt = { source.typefaceSelector?.invoke(it) ?: source.paint.typeface },
                measure = { text, font ->
                    measurePaint.typeface = font
                    measurePaint.measureText(text)
                },
            )
        }
    }
    private val leftMask = edgeMask(Color.TRANSPARENT, Color.BLACK)
    private val rightMask = edgeMask(Color.BLACK, Color.TRANSPARENT)

    fun matches(other: SpaceGatePromotionRenderer): Boolean =
        targetLine.begin == other.targetLine.begin && targetLine.end == other.targetLine.end &&
            targetLine.duration == other.targetLine.duration && target.text == other.target.text

    fun prepareShadows(left: TextPaint?, right: TextPaint?) {
        left?.let { source.shadows.left.preparePromotion(target, it) }
        right?.let { source.shadows.right.preparePromotion(target, it) }
    }

    /** A retarget begins at the currently drawn glyph positions and scale, not at either old endpoint. */
    fun snapshot(): SpaceGatePromotionSnapshot = source.copy(
        geometry = geometry.frame(fraction),
        baseline = SpaceGatePromotionGeometry.lerp(sourceTop + source.baseline, targetTop + target.baseline, fraction),
        paint = TextPaint(source.paint).apply {
            alpha = SpaceGatePromotionGeometry.lerp(source.paint.alpha * sourceAlpha, target.paint.alpha.toFloat(), fraction).toInt()
        },
        textScaleY = SpaceGatePromotionGeometry.lerp(
            source.textScaleY, target.paint.textSize * target.textScaleY / source.paint.textSize, fraction,
        ),
        shadowAlpha = SpaceGatePromotionGeometry.lerp(source.shadowAlpha * sourceAlpha, target.shadowAlpha, fraction),
    )

    fun draw(
        canvas: Canvas, isRightSide: Boolean, slotWidth: Int, height: Int,
        cameraSeam: Float = geometry.target.seam, shadowStyle: TextPaint,
    ) {
        paint.alpha = SpaceGatePromotionGeometry.lerp(source.paint.alpha * sourceAlpha, target.paint.alpha.toFloat(), fraction).toInt()
        val startX = if (isRightSide) geometry.target.seam else geometry.target.seam - cameraSeam
        val last = geometry.source.glyphs.lastIndex
        if (last < 0) return
        val depth = if (isRightSide) geometry.end(last, fraction) - (startX + slotWidth)
            else startX - geometry.start(0, fraction)
        val length = depth.coerceIn(0f, target.fadingEdgeLength)
        canvas.withSave {
            // Clip before text transforms. Scaling a pre-clipped child View would move the camera edge.
            clipRect(0f, 0f, slotWidth.toFloat(), height.toFloat())
            translate(-startX, 0f)
            // Only the outer-edge DST_IN mask requires an offscreen layer.
            val layer = if (length > 0f) saveLayer(startX, 0f, startX + slotWidth, height.toFloat(), null)
                else save()
            val baseline = SpaceGatePromotionGeometry.lerp(
                sourceTop + source.baseline, targetTop + target.baseline, fraction,
            )
            val scaleY = SpaceGatePromotionGeometry.lerp(
                source.textScaleY, target.paint.textSize * target.textScaleY / source.paint.textSize, fraction,
            )
            val shadow = if (isRightSide) source.shadows.right else source.shadows.left
            shadow.drawPromotion(
                this, target, geometry, fraction, baseline,
                scaleY * source.paint.textSize / target.paint.textSize, shadowStyle,
                SpaceGatePromotionGeometry.lerp(source.shadowAlpha * sourceAlpha, target.shadowAlpha, fraction),
            )
            for ((index, glyph) in geometry.source.glyphs.withIndex()) {
                val sourceWidth = glyph.naturalEnd - glyph.naturalStart
                if (glyph.blank || sourceWidth <= 0f) continue
                val start = geometry.start(index, fraction)
                val end = geometry.end(index, fraction)
                val scaleX = (end - start) / sourceWidth
                withSeamFade(geometry.fade(index, fraction), paint) {
                    withSave {
                        translate(start - glyph.naturalStart * scaleX, baseline)
                        scale(scaleX, scaleY)
                        for (run in glyphRuns[index]) {
                            paint.typeface = run.font
                            drawText(run.text, run.x, 0f, paint)
                        }
                    }
                }
            }
            // Preserve the existing outer-edge fade for a preview growing beyond the visible strip.
            if (length > 0f) {
                withSave {
                    translate(if (isRightSide) startX + slotWidth - length else startX, 0f)
                    scale(length, 1f)
                    drawRect(0f, 0f, 1f, height.toFloat(), if (isRightSide) rightMask else leftMask)
                }
            }
            restoreToCount(layer)
        }
    }

    private fun edgeMask(from: Int, to: Int) = Paint().apply {
        shader = LinearGradient(0f, 0f, 1f, 0f, from, to, Shader.TileMode.CLAMP)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
}
