package com.juren233.hyperlyricsenhanced.root.mediacard.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Media-card adaptation of the layer geometry in Apple Music 1586's original
 * classes2.dex, LyricsBackgroundLayerView.onDraw: -120s/+90s/+70s, 1.3 overscan,
 * offset layers, saturation before composition, then scrims and blur (radius 20 here; original radius 25).
 * Resource scrims were verified with aapt2 against that APK. No Apple runtime lookup.
 * The separable Gaussian uses RenderScript's radius-to-sigma mapping; buffers are
 * reused at the original reduced resolution instead of blurring a full-size card.
 */
internal class AppleLyricsFlowRenderer(context: Context, artwork: MediaFlowArtwork) {
    private val downsample = if (context.resources.configuration.densityDpi >= 420) 24f else 16f
    private var previous = artwork.createBitmap()
    private var current = previous
    private var composite: Bitmap? = null
    private var incoming: Bitmap? = null
    private var blurred: Bitmap? = null
    private var compositeCanvas = Canvas()
    private var incomingCanvas = Canvas()
    private var pixels = IntArray(0)
    private var horizontal = IntArray(0)
    private val matrix = Matrix()
    private val outputMatrix = Matrix()
    private val artworkPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(2.5f) })
    }
    private val blendPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val outputPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun updateArtwork(artwork: MediaFlowArtwork, playing: Boolean) {
        val next = artwork.createBitmap()
        if (previous !== current) previous.recycle()
        if (playing) {
            previous = current
        } else {
            current.recycle()
            previous = next
        }
        current = next
    }

    fun draw(
        canvas: Canvas, width: Int, height: Int, seconds: Float, fraction: Float,
        tone: MediaFlowTone, drawHeight: Int = height
    ) {
        val w = (width * 1.3f / downsample).roundToInt().coerceAtLeast(1)
        val h = (height * 1.3f / downsample).roundToInt().coerceAtLeast(1)
        ensureBuffers(w, h)
        val bitmap = requireNotNull(composite)
        if (fraction < 1f && previous !== current) {
            drawLayers(compositeCanvas, previous, w, h, seconds)
            drawLayers(incomingCanvas, current, w, h, seconds)
            blendPaint.alpha = (fraction * 255f).roundToInt().coerceIn(0, 255)
            compositeCanvas.drawBitmap(requireNotNull(incoming), 0f, 0f, blendPaint)
        } else {
            drawLayers(compositeCanvas, current, w, h, seconds)
        }
        compositeCanvas.drawColor(if (tone == MediaFlowTone.DARK) 0x80000000.toInt() else 0x4d000000)
        compositeCanvas.drawColor(if (tone == MediaFlowTone.DARK) 0x0dffffff else 0x1affffff)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        AppleLyricsFlowBlur.apply(pixels, horizontal, w, h, vertical = false)
        AppleLyricsFlowBlur.apply(horizontal, pixels, w, h, vertical = true)
        requireNotNull(blurred).setPixels(pixels, 0, w, 0, 0, w, h)
        // Crop the same 1.3 overscan after the composite has been blurred.
        val renderedWidth = w * downsample
        val renderedHeight = h * downsample
        outputMatrix.setScale(downsample, downsample)
        outputMatrix.postTranslate(
            -(renderedWidth - renderedWidth / 1.3f) / 2f,
            -(renderedHeight - renderedHeight / 1.3f) / 2f
        )
        requireNotNull(outputPaint.shader).setLocalMatrix(outputMatrix)
        // The transition layer may extend below its content viewport. Mirror the
        // blurred texture there, retaining the content's scale and origin.
        canvas.drawRect(0f, 0f, width.toFloat(), drawHeight.toFloat(), outputPaint)
    }

    private fun drawLayers(canvas: Canvas, artwork: Bitmap, w: Int, h: Int, seconds: Float) {
        canvas.drawColor(Color.BLACK)
        val side = (max(w, h) * 1.3f).roundToInt().toFloat()
        val scale = side / artwork.height
        for (layer in 0..2) {
            val period = when (layer) { 0 -> -120f; 1 -> 90f; else -> 70f }
            val angle = (seconds % kotlin.math.abs(period)) * 360f / period
            matrix.setScale(scale, scale)
            matrix.postRotate(angle, side / 2f, side / 2f)
            matrix.postTranslate(-(side - w) / 2f, -(side - h) / 2f)
            when (layer) {
                1 -> matrix.postTranslate(-0.95f * w, -0.7f * h)
                2 -> {
                    matrix.postTranslate(-0.5f * w, 0.7f * h)
                    matrix.postRotate(angle, w / 2f, h / 2f)
                }
            }
            canvas.drawBitmap(artwork, matrix, artworkPaint)
        }
    }

    private fun ensureBuffers(w: Int, h: Int) {
        if (composite?.width == w && composite?.height == h) return
        composite?.recycle()
        incoming?.recycle()
        blurred?.recycle()
        composite = createBitmap(w, h)
        incoming = createBitmap(w, h)
        blurred = createBitmap(w, h)
        outputPaint.shader = BitmapShader(requireNotNull(blurred), Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
            .apply { setFilterMode(BitmapShader.FILTER_MODE_LINEAR) }
        compositeCanvas = Canvas(requireNotNull(composite))
        incomingCanvas = Canvas(requireNotNull(incoming))
        pixels = IntArray(w * h)
        horizontal = IntArray(w * h)
    }

}

// Kernel definition: https://android.googlesource.com/platform/cts/+/a14d199/tests/tests/renderscript/cts/intrinsic_blur.rs
internal object AppleLyricsFlowBlur {
    private const val RADIUS = 20
    private val weights = FloatArray(RADIUS * 2 + 1) { index ->
        val distance = (index - RADIUS).toFloat()
        val sigma = 0.4f * RADIUS + 0.6f
        exp(-distance * distance / (2f * sigma * sigma))
    }.apply {
        val total = sum()
        indices.forEach { this[it] /= total }
    }

    fun apply(source: IntArray, target: IntArray, w: Int, h: Int, vertical: Boolean) {
        for (y in 0 until h) for (x in 0 until w) {
            var red = 0f
            var green = 0f
            var blue = 0f
            for (index in weights.indices) {
                val offset = index - RADIUS
                val sx = if (vertical) x else (x + offset).coerceIn(0, w - 1)
                val sy = if (vertical) (y + offset).coerceIn(0, h - 1) else y
                val color = source[sy * w + sx]
                val weight = weights[index]
                red += ((color ushr 16) and 255) * weight
                green += ((color ushr 8) and 255) * weight
                blue += (color and 255) * weight
            }
            target[y * w + x] = (0xff shl 24) or (red.roundToInt() shl 16) or
                (green.roundToInt() shl 8) or blue.roundToInt()
        }
    }
}
