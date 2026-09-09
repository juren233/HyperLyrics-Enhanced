/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Big-island artwork background. It does not add a child to the horizontal big container. */
internal class EmbeddedIslandAlbumCoverDrawable(
    private val originalBackground: Drawable?,
    artwork: EmbeddedIslandArtwork,
    private val density: Float,
) : Drawable() {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val transitionPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val terminalBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        blendMode = BlendMode.DST_IN
    }
    private val clipPath = Path()
    private val nativeBlurEffect = RenderEffect.createBlurEffect(
        IslandGradientCoverLayout.embeddedTransitionBlurRadiusX(density),
        IslandGradientCoverLayout.embeddedTransitionBlurRadiusY(density),
        Shader.TileMode.CLAMP,
    )
    private val transitionRenderNode = RenderNode("HyperLyricsIslandCoverTransition").apply {
        setRenderEffect(nativeBlurEffect)
    }
    private var bitmap = artwork.bitmap
    private var ownsBitmap = artwork.owned
    private var transitionTextureBitmap: Bitmap? = null
    private var transitionCpuBlurredTextureBitmap: Bitmap? = null
    private var transitionMaskBitmap: Bitmap? = null
    private var transitionBlurMaskBitmap: Bitmap? = null
    private var transitionShadeBitmap: Bitmap? = null
    private var transitionWidth: Int = -1
    private var transitionHeight: Int = -1
    private var transitionRenderNodeReady = false
    private var lastNativeBlurHardware: Boolean? = null
    private var renderNodeRecoveryCount = 0
    private var lastDrawDiagnostic: String? = null
    private var appliedAlpha: Int = 255
    private var drawCount = 0L
    private var lastDrawUptimeMs = 0L
    private var recycledBitmapLogged = false

    fun updateArtwork(artwork: EmbeddedIslandArtwork) {
        if (bitmap !== artwork.bitmap || ownsBitmap != artwork.owned) {
            if (ownsBitmap && bitmap !== artwork.bitmap && !bitmap.isRecycled) bitmap.recycle()
            bitmap = artwork.bitmap
            ownsBitmap = artwork.owned
            recycleTransitionBitmaps()
            recycledBitmapLogged = false
        }
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        originalBackground?.bounds = bounds
    }

    override fun draw(canvas: Canvas) {
        drawCount += 1
        lastDrawUptimeMs = SystemClock.uptimeMillis()
        originalBackground?.draw(canvas)
        val source = bitmap.takeUnless { it.isRecycled } ?: run {
            if (BuildConfig.DEBUG && !recycledBitmapLogged) {
                recycledBitmapLogged = true
                HookLogger.i(
                    "EmbeddedIslandAlbumCover",
                    "大岛封面源 Bitmap 已回收: drawable=${System.identityHashCode(this)}, " +
                        "drawCount=$drawCount",
                )
            }
            drawTerminalBlackMask(canvas)
            return
        }
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0) return

        val canvasLeft = bounds.left.toFloat()
        val canvasTop = bounds.top.toFloat()
        val coverWidth = min(width, height).toFloat()
        val coverRight = canvasLeft + coverWidth
        val radius = height / 2f
        val blurSuppressed = shouldSuppressPausedCollapsedBlur()
        val extensionWidth = IslandGradientCoverLayout.embeddedTransitionVisibleExtension(
            availableWidth = bounds.right.toFloat() - coverRight,
            density = density,
        )
        val visibleOverlap = IslandGradientCoverLayout.embeddedTransitionOverlap(coverWidth, density)
        val transitionInset = IslandGradientCoverLayout.embeddedTransitionInset(coverWidth, density)
        val blurInset = IslandGradientCoverLayout.embeddedTransitionBlurInset(coverWidth, density)
        val cachedTransitionWidth = IslandGradientCoverLayout.embeddedTransitionBitmapWidth(
            coverWidth = coverWidth,
            density = density,
        )
        val extensionEnd = coverRight + extensionWidth
        val crop = IslandGradientCoverLayout.centerCropWindow(
            sourceWidth = source.width,
            sourceHeight = source.height,
            targetWidth = coverWidth,
            targetHeight = height.toFloat(),
        ) ?: run {
            drawTerminalBlackMask(canvas)
            return
        }

        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(
            RectF(
                canvasLeft,
                canvasTop,
                bounds.right.toFloat(),
                canvasTop + height,
            ),
            floatArrayOf(
                radius, radius,
                0f, 0f,
                0f, 0f,
                radius, radius,
            ),
            Path.Direction.CW,
        )
        canvas.clipPath(clipPath)

        val cropLeft = kotlin.math.floor(crop.left).toInt().coerceIn(0, source.width - 1)
        val cropTop = kotlin.math.floor(crop.top).toInt().coerceIn(0, source.height - 1)
        val cropRight = kotlin.math.ceil(crop.right).toInt().coerceIn(cropLeft + 1, source.width)
        val cropBottom = kotlin.math.ceil(crop.bottom).toInt().coerceIn(cropTop + 1, source.height)
        val coverTarget = RectF(canvasLeft, canvasTop, coverRight, canvasTop + height)
        val coverSource = Rect(cropLeft, cropTop, cropRight, cropBottom)

        // 暂停态（含显示歌名歌手的有字态）封面置顶：先画完整过渡层，封面最后画并盖住其
        // 侵入封面的重叠区，封面保持 100% 清晰，渐变仅从封面右缘向外可见；过渡层原有的
        // 封面内重叠保留在封面之下，避免封面与延伸之间出现黑缝。播放态保持原顺序
        // （封面在下、过渡层渐变覆盖封面右缘），视觉与既有行为完全一致。
        val coverOnTop = IslandGradientCoverLayout.embeddedCoverOnTopForPlaybackState(
            EmbeddedIslandAlbumCoverController.isPlaybackActive(),
        )
        if (!coverOnTop) {
            canvas.drawBitmap(source, coverSource, coverTarget, bitmapPaint)
        }

        val transitionPrepared = extensionWidth > 0f && ensureTransitionBitmaps(
                source = source,
                cropLeft = cropLeft,
                cropTop = cropTop,
                cropRight = cropRight,
                cropBottom = cropBottom,
                coverWidth = coverWidth,
                visibleOverlap = visibleOverlap,
                transitionInset = transitionInset,
                blurInset = blurInset,
                cacheWidth = cachedTransitionWidth,
                targetHeight = height,
            )
        var nativeBlurEnabled = false
        var nativeBlurAvailable = false
        if (transitionPrepared) {
            val rawTarget = RectF(
                coverRight - transitionInset,
                canvasTop,
                extensionEnd,
                canvasTop + height,
            )
            val blurTarget = RectF(
                coverRight - blurInset,
                canvasTop,
                extensionEnd,
                canvasTop + height,
            )
            val rawSourceLeft = IslandGradientCoverLayout.embeddedTransitionRawCacheOffset(
                transitionInset = transitionInset,
                blurInset = blurInset,
            ).roundToInt().coerceIn(0, (transitionWidth - 1).coerceAtLeast(0))
            val rawSource = Rect(
                rawSourceLeft,
                0,
                transitionWidth.coerceAtLeast(1),
                transitionHeight.coerceAtLeast(1),
            )
            nativeBlurEnabled = canvas.isHardwareAccelerated && !blurSuppressed
            nativeBlurAvailable = nativeBlurEnabled && ensureTransitionRenderNodeDisplayList()
            logNativeBlurState(nativeBlurAvailable)
            val rawLayer = canvas.saveLayer(rawTarget, null)
            transitionTextureBitmap?.let { transition ->
                canvas.drawBitmap(
                    transition,
                    rawSource,
                    rawTarget,
                    transitionPaint,
                )
            }
            transitionMaskBitmap?.let { mask ->
                canvas.drawBitmap(
                    mask,
                    rawSource,
                    rawTarget,
                    maskPaint,
                )
            }
            canvas.restoreToCount(rawLayer)

            if (!blurSuppressed) {
                val blurLayer = canvas.saveLayer(blurTarget, null)
                if (nativeBlurAvailable) {
                canvas.save()
                canvas.translate(blurTarget.left, blurTarget.top)
                canvas.scale(
                    blurTarget.width() / transitionWidth.coerceAtLeast(1),
                    blurTarget.height() / transitionHeight.coerceAtLeast(1),
                )
                canvas.drawRenderNode(transitionRenderNode)
                canvas.restore()
                } else {
                    (transitionCpuBlurredTextureBitmap ?: transitionTextureBitmap)?.let { transition ->
                    canvas.drawBitmap(
                        transition,
                        Rect(0, 0, transition.width, transition.height),
                        blurTarget,
                        transitionPaint,
                    )
                    }
                }
                transitionBlurMaskBitmap?.let { mask ->
                    canvas.drawBitmap(
                        mask,
                        Rect(0, 0, mask.width, mask.height),
                        blurTarget,
                        maskPaint,
                    )
                }
                canvas.restoreToCount(blurLayer)
            }
            transitionShadeBitmap?.let { shade ->
                canvas.drawBitmap(
                    shade,
                    rawSource,
                    rawTarget,
                    shadePaint,
                )
            }
            val availableExtension = (bounds.right.toFloat() - coverRight).coerceAtLeast(0f)
            val uncoveredBackgroundTail = (availableExtension - extensionWidth).coerceAtLeast(0f)
            if (uncoveredBackgroundTail > 0f) {
                // The native background remains visible after the capped transition width. Own
                // that terminal region here so the final black gradient pixel never meets a
                // second Drawable with a different alpha/rounding/compositing path.
                val terminalTailStart = maxOf(coverRight, extensionEnd - 1f)
                canvas.drawRect(
                    terminalTailStart,
                    canvasTop,
                    bounds.right.toFloat(),
                    canvasTop + height,
                    terminalBlackPaint,
                )
            }
        }
        if (coverOnTop) {
            canvas.drawBitmap(source, coverSource, coverTarget, bitmapPaint)
        }
        logBigDrawDiagnostic(
            width = width,
            height = height,
            coverWidth = coverWidth,
            extensionWidth = extensionWidth,
            cacheWidth = cachedTransitionWidth,
            visibleOverlap = visibleOverlap,
            transitionInset = transitionInset,
            blurInset = blurInset,
            coverOnTop = coverOnTop,
            transitionPrepared = transitionPrepared,
            nativeBlurEnabled = nativeBlurEnabled,
            nativeBlurAvailable = nativeBlurAvailable,
            blurSuppressed = blurSuppressed,
        )

        canvas.restore()
        drawTerminalBlackMask(canvas)
    }

    /**
     * Unconditional pure-black band over the last few columns of the drawable bounds. Runtime
     * evidence (150182–150184) showed the reported cover-tinted terminal line lives inside the
     * last two columns and that the fake-island twin of this drawable can overlap the real one
     * with a 1–2px misalignment during width animations. Painting the tail black on every draw
     * exit path (recycled bitmap, failed crop, and normal completion) physically covers all of
     * those sources; the gradient already reaches >=97% black there, so the visual delta is
     * imperceptible.
     */
    private fun drawTerminalBlackMask(canvas: Canvas) {
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0) return
        val maskLeft = maxOf(
            bounds.left.toFloat(),
            bounds.right - TERMINAL_BLACK_MASK_WIDTH_PX,
        )
        if (maskLeft >= bounds.right.toFloat()) return
        canvas.drawRect(
            maskLeft,
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.top + height.toFloat(),
            terminalBlackPaint,
        )
    }

    private fun ensureTransitionBitmaps(
        source: Bitmap,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
        coverWidth: Float,
        visibleOverlap: Float,
        transitionInset: Float,
        blurInset: Float,
        cacheWidth: Int,
        targetHeight: Int,
    ): Boolean {
        val width = cacheWidth.coerceAtLeast(1)
        if (targetHeight <= 0 || cropRight <= cropLeft || cropBottom <= cropTop) return false
        if (transitionTextureBitmap != null && transitionCpuBlurredTextureBitmap != null &&
            transitionMaskBitmap != null && transitionBlurMaskBitmap != null &&
            transitionShadeBitmap != null &&
            transitionWidth == width && transitionHeight == targetHeight
        ) {
            return true
        }
        recycleTransitionBitmaps()
        if (BuildConfig.DEBUG) {
            HookLogger.i(
                "EmbeddedIslandAlbumCover",
                "大岛边缘扩散缓存重建: cache=${width}x$targetHeight, " +
                    "source=${source.width}x${source.height}, edgeColumns=3",
            )
        }

        val readable = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        } ?: return false
        var texture: Bitmap? = null
        var cpuBlurredTexture: Bitmap? = null
        var mask: Bitmap? = null
        var blurMask: Bitmap? = null
        var shade: Bitmap? = null
        return try {
            val sourcePixels = IntArray(readable.width * readable.height)
            readable.getPixels(
                sourcePixels,
                0,
                readable.width,
                0,
                0,
                readable.width,
                readable.height,
            )
            val texturePixels = IntArray(width * targetHeight)
            val maskPixels = IntArray(width * targetHeight)
            val blurMaskPixels = IntArray(width * targetHeight)
            val shadePixels = IntArray(width * targetHeight)
            val maskRow = IntArray(width)
            val blurMaskRow = IntArray(width)
            val shadeRow = IntArray(width)
            val sourceWidth = cropRight - cropLeft
            val sourceHeight = cropBottom - cropTop
            val cacheInset = maxOf(transitionInset, blurInset)
            val rawCacheOffset = IslandGradientCoverLayout.embeddedTransitionRawCacheOffset(
                transitionInset = transitionInset,
                blurInset = blurInset,
            )
            val sourceOverlap = sourceWidth * (cacheInset / coverWidth)
            val hold = IslandGradientCoverLayout.embeddedTransitionHold(density)
            val totalWidth = (width - 1).coerceAtLeast(1).toFloat()
            val rawTotalWidth = (totalWidth - rawCacheOffset).coerceAtLeast(1f)
            val edgeColumn = IntArray(targetHeight) { targetY ->
                val sourceY = cropTop +
                    ((targetY + 0.5f) * sourceHeight / targetHeight) - 0.5f
                sampleWeightedArtworkEdgeColor(
                    pixels = sourcePixels,
                    stride = readable.width,
                    centerY = sourceY,
                    cropLeft = cropLeft,
                    cropTop = cropTop,
                    cropRight = cropRight,
                    cropBottom = cropBottom,
                )
            }
            for (targetX in 0 until width) {
                val position = targetX.toFloat()
                val rawPosition = position - rawCacheOffset
                val sourceX = IslandGradientCoverLayout.embeddedTransitionEdgeSourceX(
                    position = position,
                    overlap = cacheInset,
                    cropRight = cropRight,
                    sourceOverlap = sourceOverlap,
                )
                val diffusionRadius = IslandGradientCoverLayout.embeddedTransitionDiffusionRadius(
                    position = position,
                    totalWidth = totalWidth,
                    overlap = cacheInset,
                    targetHeight = targetHeight,
                    density = density,
                )
                val visibleStart = (transitionInset - visibleOverlap).coerceAtLeast(0f)
                val feather = IslandGradientCoverLayout.embeddedTransitionFeatherAlpha(
                    position = rawPosition - visibleStart,
                    overlap = visibleOverlap,
                )
                maskRow[targetX] = Color.argb(
                    (255f * feather).roundToInt().coerceIn(0, 255),
                    255,
                    255,
                    255,
                )
                val blurProgress = IslandGradientCoverLayout.embeddedTransitionBlurProgress(
                    position = position,
                    totalWidth = totalWidth,
                    blurInset = blurInset,
                    density = density,
                )
                blurMaskRow[targetX] = Color.argb(
                    (255f * blurProgress).roundToInt().coerceIn(0, 255),
                    255,
                    255,
                    255,
                )
                val blackMix = IslandGradientCoverLayout.embeddedTransitionBlackMix(
                    position = rawPosition,
                    totalWidth = rawTotalWidth,
                    overlap = transitionInset,
                    hold = hold,
                )
                shadeRow[targetX] = Color.argb(
                    (255f * blackMix).roundToInt().coerceIn(0, 255),
                    0,
                    0,
                    0,
                )

                for (targetY in 0 until targetHeight) {
                    val diffusedColor = verticallyDiffusedEdgeColor(
                        edgeColumn = edgeColumn,
                        centerY = targetY,
                        radius = diffusionRadius,
                    )
                    val color = if (position <= cacheInset) {
                        val sourceY = cropTop +
                            ((targetY + 0.5f) * sourceHeight / targetHeight) - 0.5f
                        val artworkColor = sampleBilinearColor(
                            pixels = sourcePixels,
                            stride = readable.width,
                            centerX = sourceX,
                            centerY = sourceY,
                            minX = cropLeft,
                            minY = cropTop,
                            maxX = cropRight - 1,
                            maxY = cropBottom - 1,
                        )
                        blendArgbColors(
                            from = artworkColor,
                            to = diffusedColor,
                            fraction = IslandGradientCoverLayout.embeddedTransitionDiffusionBlend(
                                position = position,
                                overlap = cacheInset,
                            ),
                        )
                    } else {
                        diffusedColor
                    }
                    texturePixels[targetY * width + targetX] = color
                }
            }
            for (targetY in 0 until targetHeight) {
                System.arraycopy(maskRow, 0, maskPixels, targetY * width, width)
                System.arraycopy(blurMaskRow, 0, blurMaskPixels, targetY * width, width)
                System.arraycopy(shadeRow, 0, shadePixels, targetY * width, width)
            }

            // Keep a blurred CPU copy for the rare fallback path. Normal hardware drawing uses
            // the raw edge-smear texture through the Android-native RenderEffect below.
            val cpuBlurredPixels = texturePixels.copyOf()
            blurArgbPixelsInPlace(
                pixels = cpuBlurredPixels,
                width = width,
                height = targetHeight,
                radiusX = IslandGradientCoverLayout.embeddedTransitionBlurRadiusX(density)
                    .roundToInt(),
                radiusY = IslandGradientCoverLayout.embeddedTransitionBlurRadiusY(density)
                    .roundToInt(),
            )

            texture = Bitmap.createBitmap(
                texturePixels,
                width,
                targetHeight,
                Bitmap.Config.ARGB_8888,
            )
            cpuBlurredTexture = Bitmap.createBitmap(
                cpuBlurredPixels,
                width,
                targetHeight,
                Bitmap.Config.ARGB_8888,
            )
            mask = Bitmap.createBitmap(
                maskPixels,
                width,
                targetHeight,
                Bitmap.Config.ARGB_8888,
            )
            blurMask = Bitmap.createBitmap(
                blurMaskPixels,
                width,
                targetHeight,
                Bitmap.Config.ARGB_8888,
            )
            shade = Bitmap.createBitmap(
                shadePixels,
                width,
                targetHeight,
                Bitmap.Config.ARGB_8888,
            )
            transitionTextureBitmap = texture
            transitionCpuBlurredTextureBitmap = cpuBlurredTexture
            transitionMaskBitmap = mask
            transitionBlurMaskBitmap = blurMask
            transitionShadeBitmap = shade
            transitionWidth = width
            transitionHeight = targetHeight
            transitionRenderNodeReady = recordTransitionRenderNode(texture)
            true
        } catch (_: Throwable) {
            texture?.takeUnless { it.isRecycled }?.recycle()
            cpuBlurredTexture?.takeUnless { it.isRecycled }?.recycle()
            mask?.takeUnless { it.isRecycled }?.recycle()
            blurMask?.takeUnless { it.isRecycled }?.recycle()
            shade?.takeUnless { it.isRecycled }?.recycle()
            false
        } finally {
            if (readable !== source && !readable.isRecycled) readable.recycle()
        }
    }

    private fun sampleBilinearColor(
        pixels: IntArray,
        stride: Int,
        centerX: Float,
        centerY: Float,
        minX: Int,
        minY: Int,
        maxX: Int,
        maxY: Int,
    ): Int {
        val x = centerX.coerceIn(minX.toFloat(), maxX.toFloat())
        val y = centerY.coerceIn(minY.toFloat(), maxY.toFloat())
        val x0 = kotlin.math.floor(x).toInt()
        val y0 = kotlin.math.floor(y).toInt()
        val x1 = minOf(x0 + 1, maxX)
        val y1 = minOf(y0 + 1, maxY)
        val xFraction = x - x0
        val yFraction = y - y0
        val topLeft = pixels[y0 * stride + x0]
        val topRight = pixels[y0 * stride + x1]
        val bottomLeft = pixels[y1 * stride + x0]
        val bottomRight = pixels[y1 * stride + x1]

        fun interpolate(channel: (Int) -> Int): Int {
            val top = channel(topLeft) + (channel(topRight) - channel(topLeft)) * xFraction
            val bottom = channel(bottomLeft) +
                (channel(bottomRight) - channel(bottomLeft)) * xFraction
            return (top + (bottom - top) * yFraction).roundToInt().coerceIn(0, 255)
        }

        return Color.argb(
            interpolate(Color::alpha),
            interpolate(Color::red),
            interpolate(Color::green),
            interpolate(Color::blue),
        )
    }

    /**
     * Blend the last three visible artwork columns instead of stretching one potentially noisy
     * column. Linear weights favour the true outer edge while retaining a small amount of local
     * colour context without making the sampled band too broad.
     */
    private fun sampleWeightedArtworkEdgeColor(
        pixels: IntArray,
        stride: Int,
        centerY: Float,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
    ): Int {
        val sampleCount = minOf(3, cropRight - cropLeft).coerceAtLeast(1)
        var weightSum = 0f
        var alpha = 0f
        var red = 0f
        var green = 0f
        var blue = 0f
        for (index in 0 until sampleCount) {
            val sourceX = cropRight - sampleCount + index.toFloat()
            val weight = (index + 1).toFloat()
            val color = sampleBilinearColor(
                pixels = pixels,
                stride = stride,
                centerX = sourceX,
                centerY = centerY,
                minX = cropLeft,
                minY = cropTop,
                maxX = cropRight - 1,
                maxY = cropBottom - 1,
            )
            weightSum += weight
            alpha += Color.alpha(color) * weight
            red += Color.red(color) * weight
            green += Color.green(color) * weight
            blue += Color.blue(color) * weight
        }
        return Color.argb(
            (alpha / weightSum).roundToInt().coerceIn(0, 255),
            (red / weightSum).roundToInt().coerceIn(0, 255),
            (green / weightSum).roundToInt().coerceIn(0, 255),
            (blue / weightSum).roundToInt().coerceIn(0, 255),
        )
    }

    private fun recordTransitionRenderNode(texture: Bitmap?): Boolean {
        val source = texture?.takeUnless { it.isRecycled } ?: return false
        return runCatching {
            transitionRenderNode.setPosition(0, 0, source.width, source.height)
            transitionRenderNode.setRenderEffect(nativeBlurEffect)
            val recordingCanvas = transitionRenderNode.beginRecording(source.width, source.height)
            recordingCanvas.drawBitmap(source, 0f, 0f, transitionPaint)
            transitionRenderNode.endRecording()
            transitionRenderNode.hasDisplayList()
        }.getOrElse {
            runCatching { transitionRenderNode.endRecording() }
            false
        }
    }

    /** Re-record only when HWUI discarded the native blur display list during island restore. */
    private fun ensureTransitionRenderNodeDisplayList(): Boolean {
        if (runCatching { transitionRenderNode.hasDisplayList() }.getOrDefault(false)) {
            transitionRenderNodeReady = true
            return true
        }
        val restored = recordTransitionRenderNode(transitionTextureBitmap)
        transitionRenderNodeReady = restored
        if (BuildConfig.DEBUG) {
            renderNodeRecoveryCount += 1
            HookLogger.i(
                "EmbeddedIslandAlbumCover",
                "大岛原生模糊 DisplayList 恢复: restored=$restored, " +
                    "count=$renderNodeRecoveryCount, drawCount=$drawCount, " +
                    "texture=${bitmapDiagnostic(transitionTextureBitmap)}",
            )
        }
        return restored
    }

    private fun logNativeBlurState(enabled: Boolean) {
        if (!BuildConfig.DEBUG || lastNativeBlurHardware == enabled) return
        lastNativeBlurHardware = enabled
        HookLogger.i(
            "EmbeddedIslandAlbumCover",
            "大岛原生模糊状态: enabled=$enabled, " +
                "radius=${IslandGradientCoverLayout.embeddedTransitionBlurRadiusX(density)}x" +
                IslandGradientCoverLayout.embeddedTransitionBlurRadiusY(density),
        )
    }

    private fun logBigDrawDiagnostic(
        width: Int,
        height: Int,
        coverWidth: Float,
        extensionWidth: Float,
        cacheWidth: Int,
        visibleOverlap: Float,
        transitionInset: Float,
        blurInset: Float,
        coverOnTop: Boolean,
        transitionPrepared: Boolean,
        nativeBlurEnabled: Boolean,
        nativeBlurAvailable: Boolean,
        blurSuppressed: Boolean,
    ) {
        if (!BuildConfig.DEBUG) return
        val totalWidth = (cacheWidth - 1).coerceAtLeast(1).toFloat()
        val availableExtension = (width - coverWidth).coerceAtLeast(0f)
        val uncoveredBackgroundTail = (availableExtension - extensionWidth).coerceAtLeast(0f)
        val blurEdgeAlpha = IslandGradientCoverLayout.embeddedTransitionBlurEdgeAlpha()
        val blurFullAfterEdge =
            IslandGradientCoverLayout.embeddedTransitionBlurFullAfterEdge(density)
        val rawCacheOffset = IslandGradientCoverLayout.embeddedTransitionRawCacheOffset(
            transitionInset = transitionInset,
            blurInset = blurInset,
        )
        val blurAtTargetStart = IslandGradientCoverLayout.embeddedTransitionBlurProgress(
            position = 0f,
            totalWidth = totalWidth,
            blurInset = blurInset,
            density = density,
        )
        val blurAtCoverEdge = IslandGradientCoverLayout.embeddedTransitionBlurProgress(
            position = blurInset,
            totalWidth = totalWidth,
            blurInset = blurInset,
            density = density,
        )
        val signature = "$width|$height|${coverWidth.roundToInt()}|${extensionWidth.roundToInt()}|" +
            "$cacheWidth|${visibleOverlap.roundToInt()}|${transitionInset.roundToInt()}|" +
            "${blurInset.roundToInt()}|$coverOnTop|${rawCacheOffset.roundToInt()}|" +
            "${(blurEdgeAlpha * 100f).roundToInt()}|${blurFullAfterEdge.roundToInt()}|" +
            "${(blurAtTargetStart * 100f).roundToInt()}|" +
            "${(blurAtCoverEdge * 100f).roundToInt()}|" +
            "${uncoveredBackgroundTail.roundToInt()}|$transitionPrepared|$nativeBlurEnabled|" +
            "$nativeBlurAvailable|$blurSuppressed"
        if (signature == lastDrawDiagnostic) return
        lastDrawDiagnostic = signature
        HookLogger.i(
            "EmbeddedIslandAlbumCover",
            "大岛封面绘制诊断: bounds=${width}x$height, " +
                "cover=${coverWidth.roundToInt()}, extension=${extensionWidth.roundToInt()}, " +
                "visibleOverlap=${visibleOverlap.roundToInt()}, transitionInset=${transitionInset.roundToInt()}, " +
                "blurInset=${blurInset.roundToInt()}, coverOnTop=$coverOnTop, " +
                "rawCacheOffset=${rawCacheOffset.roundToInt()}, " +
                "blurEdgeAlpha=${(blurEdgeAlpha * 100f).roundToInt()}%, " +
                "blurFullAfterEdge=${blurFullAfterEdge.roundToInt()}, " +
                "blurAtTargetStart=${(blurAtTargetStart * 100f).roundToInt()}%, " +
                "blurAtCoverEdge=${(blurAtCoverEdge * 100f).roundToInt()}%, " +
                "extensionEnd=${(coverWidth + extensionWidth).roundToInt()}, " +
                "uncoveredBackgroundTail=${uncoveredBackgroundTail.roundToInt()}, cacheWidth=$cacheWidth, " +
                "prepared=$transitionPrepared, algorithm=edge_smear_native_blur, " +
                "nativeBlurEnabled=$nativeBlurEnabled, nativeBlurAvailable=$nativeBlurAvailable, " +
                "cpuFallback=${transitionCpuBlurredTextureBitmap != null}, " +
                "blurSuppressed=$blurSuppressed",
        )
    }

    private fun shouldSuppressPausedCollapsedBlur(): Boolean {
        if (EmbeddedIslandAlbumCoverController.isPlaybackActive()) return false
        val host = callback as? View ?: return false
        val textContainer = findDescendantByResourceName(
            host,
            "island_container_module_text",
        ) ?: return false
        val collapsed = textContainer.visibility != View.VISIBLE ||
            (textContainer.width <= 12 && textContainer.height <= 1)
        if (BuildConfig.DEBUG && collapsed) {
            HookLogger.d(
                "EmbeddedIslandAlbumCover",
                "暂停无字默认态隐藏右侧模糊: host=${resourceName(host)}, " +
                    "textSize=${textContainer.width}x${textContainer.height}, " +
                    "textVisibility=${textContainer.visibility}",
            )
        }
        return collapsed
    }

    private fun recycleTransitionBitmaps() {
        transitionTextureBitmap?.takeUnless { it.isRecycled }?.recycle()
        transitionCpuBlurredTextureBitmap?.takeUnless { it.isRecycled }?.recycle()
        transitionMaskBitmap?.takeUnless { it.isRecycled }?.recycle()
        transitionBlurMaskBitmap?.takeUnless { it.isRecycled }?.recycle()
        transitionShadeBitmap?.takeUnless { it.isRecycled }?.recycle()
        transitionTextureBitmap = null
        transitionCpuBlurredTextureBitmap = null
        transitionMaskBitmap = null
        transitionBlurMaskBitmap = null
        transitionShadeBitmap = null
        transitionWidth = -1
        transitionHeight = -1
        transitionRenderNodeReady = false
        transitionRenderNode.discardDisplayList()
    }

    fun release() {
        if (ownsBitmap && !bitmap.isRecycled) bitmap.recycle()
        ownsBitmap = false
        recycleTransitionBitmaps()
    }

    /**
     * Prepare the fixed transition cache before MIUI starts a big-to-small island animation.
     * The cache dimensions are based on the stable capsule height, not the currently measured
     * animated width, so this does not create a per-frame allocation or layout dependency.
     */
    fun prewarmTransitionCache(stableHeight: Int): Boolean {
        val source = bitmap.takeUnless { it.isRecycled } ?: return false
        val height = stableHeight.takeIf { it > 0 } ?: return false
        val coverWidth = height.toFloat()
        val crop = IslandGradientCoverLayout.centerCropWindow(
            sourceWidth = source.width,
            sourceHeight = source.height,
            targetWidth = coverWidth,
            targetHeight = height.toFloat(),
        ) ?: return false
        val cropLeft = kotlin.math.floor(crop.left).toInt().coerceIn(0, source.width - 1)
        val cropTop = kotlin.math.floor(crop.top).toInt().coerceIn(0, source.height - 1)
        val cropRight = kotlin.math.ceil(crop.right).toInt().coerceIn(cropLeft + 1, source.width)
        val cropBottom = kotlin.math.ceil(crop.bottom).toInt().coerceIn(cropTop + 1, source.height)
        val visibleOverlap = IslandGradientCoverLayout.embeddedTransitionOverlap(coverWidth, density)
        val transitionInset = IslandGradientCoverLayout.embeddedTransitionInset(coverWidth, density)
        val blurInset = IslandGradientCoverLayout.embeddedTransitionBlurInset(coverWidth, density)
        val prepared = ensureTransitionBitmaps(
            source = source,
            cropLeft = cropLeft,
            cropTop = cropTop,
            cropRight = cropRight,
            cropBottom = cropBottom,
            coverWidth = coverWidth,
            visibleOverlap = visibleOverlap,
            transitionInset = transitionInset,
            blurInset = blurInset,
            cacheWidth = IslandGradientCoverLayout.embeddedTransitionBitmapWidth(coverWidth, density),
            targetHeight = height,
        )
        if (BuildConfig.DEBUG && prepared) {
            HookLogger.i(
                "EmbeddedIslandAlbumCover",
                "大岛边缘扩散缓存已预热: stableHeight=$height, " +
                    "cache=${transitionWidth}x$transitionHeight",
            )
        }
        return prepared
    }

    fun diagnosticAlpha(): Int = appliedAlpha

    fun diagnosticState(nowUptimeMs: Long = SystemClock.uptimeMillis()): String {
        val lastDrawAgeMs = if (lastDrawUptimeMs <= 0L) -1L else nowUptimeMs - lastDrawUptimeMs
        return "drawCount=$drawCount,lastDrawAgeMs=$lastDrawAgeMs," +
            "source=${bitmapDiagnostic(bitmap)}," +
            "texture=${bitmapDiagnostic(transitionTextureBitmap)}," +
            "cpuTexture=${bitmapDiagnostic(transitionCpuBlurredTextureBitmap)}," +
            "mask=${bitmapDiagnostic(transitionMaskBitmap)}," +
            "blurMask=${bitmapDiagnostic(transitionBlurMaskBitmap)}," +
            "shade=${bitmapDiagnostic(transitionShadeBitmap)}," +
            "renderNodeReady=$transitionRenderNodeReady," +
            "renderNodeHasDisplayList=${runCatching { transitionRenderNode.hasDisplayList() }.getOrNull()}," +
            "algorithm=edge_smear_native_blur"
    }

    override fun setAlpha(alpha: Int) {
        appliedAlpha = alpha
        bitmapPaint.alpha = alpha
        transitionPaint.alpha = alpha
        shadePaint.alpha = alpha
        terminalBlackPaint.alpha = alpha
        transitionRenderNodeReady = recordTransitionRenderNode(transitionTextureBitmap)
        if (BuildConfig.DEBUG) HookLogger.i(
            "EmbeddedIslandAlbumCover",
            "大岛封面 Drawable alpha 变化: drawable=${System.identityHashCode(this)}, alpha=$alpha",
        )
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bitmapPaint.colorFilter = colorFilter
        transitionPaint.colorFilter = colorFilter
        shadePaint.colorFilter = colorFilter
        terminalBlackPaint.colorFilter = colorFilter
        transitionRenderNodeReady = recordTransitionRenderNode(transitionTextureBitmap)
        if (BuildConfig.DEBUG) HookLogger.i(
            "EmbeddedIslandAlbumCover",
            "大岛封面 Drawable colorFilter 变化: drawable=${System.identityHashCode(this)}, " +
                "filter=${colorFilter?.javaClass?.name}",
        )
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

