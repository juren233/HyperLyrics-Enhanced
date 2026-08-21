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

/** Small-island artwork child. The host is a FrameLayout, so this child never consumes width. */
internal class EmbeddedIslandAlbumCoverView(
    context: android.content.Context,
) : View(context) {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clipPath = Path()
    private var bitmap: Bitmap? = null
    private var ownsBitmap = false
    private var lastDrawDiagnostic: String? = null

    fun updateArtwork(artwork: EmbeddedIslandArtwork) {
        if (bitmap !== artwork.bitmap || ownsBitmap != artwork.owned) {
            releaseOwnedBitmap(except = artwork.bitmap)
            bitmap = artwork.bitmap
            ownsBitmap = artwork.owned
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = bitmap?.takeUnless { it.isRecycled } ?: return
        val width = width
        val height = height
        if (width <= 0 || height <= 0) return

        val diameter = min(width, height).toFloat()
        val left = (width - diameter) / 2f
        val top = (height - diameter) / 2f
        val target = RectF(left, top, left + diameter, top + diameter)
        canvas.save()
        clipPath.reset()
        clipPath.addOval(target, Path.Direction.CW)
        canvas.clipPath(clipPath)
        drawCenterCrop(canvas, source, target, bitmapPaint)
        canvas.restore()
        logDrawDiagnostic(source, width, height)
    }

    fun release() {
        releaseOwnedBitmap(except = null)
        bitmap = null
        ownsBitmap = false
    }

    private fun releaseOwnedBitmap(except: Bitmap?) {
        val current = bitmap
        if (ownsBitmap && current != null && current !== except && !current.isRecycled) {
            current.recycle()
        }
    }

    private fun logDrawDiagnostic(source: Bitmap, width: Int, height: Int) {
        if (!BuildConfig.DEBUG) return
        val host = parent as? ViewGroup
        val signature = "${width}x$height|${source.width}x${source.height}|" +
            "${host?.width}x${host?.height}|${host?.indexOfChild(this)}|${host?.childCount}"
        if (signature == lastDrawDiagnostic) return
        lastDrawDiagnostic = signature
        HookLogger.i(
            "EmbeddedIslandAlbumCover",
            "小岛嵌入绘制诊断: view=${width}x$height, bitmap=${source.width}x${source.height}, " +
                "host=${resourceName(host) ?: host?.javaClass?.simpleName}, " +
                "hostSize=${host?.width}x${host?.height}, index=${host?.indexOfChild(this)}, " +
                "children=${host?.childCount}",
        )
    }
}

/** Big-island artwork background. It does not add a child to the horizontal big container. */
private class EmbeddedIslandAlbumCoverDrawable(
    private val originalBackground: Drawable?,
    artwork: EmbeddedIslandArtwork,
    private val density: Float,
) : Drawable() {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val transitionPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
        ) ?: return

        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(
            RectF(
                canvasLeft,
                canvasTop,
                maxOf(coverRight, extensionEnd),
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
        canvas.drawBitmap(
            source,
            Rect(cropLeft, cropTop, cropRight, cropBottom),
            coverTarget,
            bitmapPaint,
        )

        val transitionPrepared = extensionWidth > 0f && ensureTransitionBitmaps(
                source = source,
                cropLeft = cropLeft,
                cropTop = cropTop,
                cropRight = cropRight,
                cropBottom = cropBottom,
                coverWidth = coverWidth,
                visibleOverlap = visibleOverlap,
                blurInset = blurInset,
                cacheWidth = cachedTransitionWidth,
                targetHeight = height,
            )
        var nativeBlurEnabled = false
        var nativeBlurAvailable = false
        if (transitionPrepared) {
            val target = RectF(
                coverRight - blurInset,
                canvasTop,
                extensionEnd,
                canvasTop + height,
            )
            nativeBlurEnabled = canvas.isHardwareAccelerated && !blurSuppressed
            nativeBlurAvailable = nativeBlurEnabled && ensureTransitionRenderNodeDisplayList()
            logNativeBlurState(nativeBlurAvailable)
            val rawLayer = canvas.saveLayer(target, null)
            transitionTextureBitmap?.let { transition ->
                canvas.drawBitmap(
                    transition,
                    Rect(0, 0, transition.width, transition.height),
                    target,
                    transitionPaint,
                )
            }
            transitionMaskBitmap?.let { mask ->
                canvas.drawBitmap(
                    mask,
                    Rect(0, 0, mask.width, mask.height),
                    target,
                    maskPaint,
                )
            }
            canvas.restoreToCount(rawLayer)

            if (!blurSuppressed) {
                val blurLayer = canvas.saveLayer(target, null)
                if (nativeBlurAvailable) {
                canvas.save()
                canvas.translate(target.left, target.top)
                canvas.scale(
                    target.width() / transitionWidth.coerceAtLeast(1),
                    target.height() / transitionHeight.coerceAtLeast(1),
                )
                canvas.drawRenderNode(transitionRenderNode)
                canvas.restore()
                } else {
                    (transitionCpuBlurredTextureBitmap ?: transitionTextureBitmap)?.let { transition ->
                    canvas.drawBitmap(
                        transition,
                        Rect(0, 0, transition.width, transition.height),
                        target,
                        transitionPaint,
                    )
                    }
                }
                transitionBlurMaskBitmap?.let { mask ->
                    canvas.drawBitmap(
                        mask,
                        Rect(0, 0, mask.width, mask.height),
                        target,
                        maskPaint,
                    )
                }
                canvas.restoreToCount(blurLayer)
            }
            transitionShadeBitmap?.let { shade ->
                canvas.drawBitmap(
                    shade,
                    Rect(0, 0, shade.width, shade.height),
                    target,
                    shadePaint,
                )
            }
        }
        logBigDrawDiagnostic(
            width = width,
            height = height,
            coverWidth = coverWidth,
            extensionWidth = extensionWidth,
            cacheWidth = cachedTransitionWidth,
            visibleOverlap = visibleOverlap,
            blurInset = blurInset,
            transitionPrepared = transitionPrepared,
            nativeBlurEnabled = nativeBlurEnabled,
            nativeBlurAvailable = nativeBlurAvailable,
            blurSuppressed = blurSuppressed,
        )

        canvas.restore()
    }

    private fun ensureTransitionBitmaps(
        source: Bitmap,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
        coverWidth: Float,
        visibleOverlap: Float,
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
                    "source=${source.width}x${source.height}, edgeColumns=5",
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
            val sourceOverlap = sourceWidth * (blurInset / coverWidth)
            val hold = IslandGradientCoverLayout.embeddedTransitionHold(density)
            val totalWidth = (width - 1).coerceAtLeast(1).toFloat()
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
                val sourceX = IslandGradientCoverLayout.embeddedTransitionEdgeSourceX(
                    position = position,
                    overlap = blurInset,
                    cropRight = cropRight,
                    sourceOverlap = sourceOverlap,
                )
                val diffusionRadius = IslandGradientCoverLayout.embeddedTransitionDiffusionRadius(
                    position = position,
                    totalWidth = totalWidth,
                    overlap = blurInset,
                    targetHeight = targetHeight,
                    density = density,
                )
                val visibleStart = (blurInset - visibleOverlap).coerceAtLeast(0f)
                val feather = IslandGradientCoverLayout.embeddedTransitionFeatherAlpha(
                    position = position - visibleStart,
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
                    position = position,
                    totalWidth = totalWidth,
                    overlap = blurInset,
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
                    val color = if (position <= blurInset) {
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
                                overlap = blurInset,
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
     * Blend the last five visible artwork columns instead of stretching one potentially noisy
     * column. Linear weights favour the true outer edge while retaining a small amount of local
     * colour context, avoiding repeated five-column texture patterns.
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
        val sampleCount = minOf(5, cropRight - cropLeft).coerceAtLeast(1)
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
        blurInset: Float,
        transitionPrepared: Boolean,
        nativeBlurEnabled: Boolean,
        nativeBlurAvailable: Boolean,
        blurSuppressed: Boolean,
    ) {
        if (!BuildConfig.DEBUG) return
        val totalWidth = (cacheWidth - 1).coerceAtLeast(1).toFloat()
        val blurAtCoverEdge = IslandGradientCoverLayout.embeddedTransitionBlurProgress(
            position = blurInset,
            totalWidth = totalWidth,
            blurInset = blurInset,
            density = density,
        )
        val signature = "$width|$height|${coverWidth.roundToInt()}|${extensionWidth.roundToInt()}|" +
            "$cacheWidth|${visibleOverlap.roundToInt()}|${blurInset.roundToInt()}|" +
            "${(blurAtCoverEdge * 100f).roundToInt()}|" +
            "$transitionPrepared|$nativeBlurEnabled|$nativeBlurAvailable|$blurSuppressed"
        if (signature == lastDrawDiagnostic) return
        lastDrawDiagnostic = signature
        HookLogger.i(
            "EmbeddedIslandAlbumCover",
            "大岛封面绘制诊断: bounds=${width}x$height, " +
                "cover=${coverWidth.roundToInt()}, extension=${extensionWidth.roundToInt()}, " +
                "visibleOverlap=${visibleOverlap.roundToInt()}, blurInset=${blurInset.roundToInt()}, " +
                "blurAtCoverEdge=${(blurAtCoverEdge * 100f).roundToInt()}%, cacheWidth=$cacheWidth, " +
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
        val blurInset = IslandGradientCoverLayout.embeddedTransitionBlurInset(coverWidth, density)
        val prepared = ensureTransitionBitmaps(
            source = source,
            cropLeft = cropLeft,
            cropTop = cropTop,
            cropRight = cropRight,
            cropBottom = cropBottom,
            coverWidth = coverWidth,
            visibleOverlap = visibleOverlap,
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

internal data class EmbeddedIslandArtwork(
    val bitmap: Bitmap,
    val owned: Boolean,
    val fingerprint: Int,
)

internal object EmbeddedIslandAlbumCoverController {
    private const val TAG = "EmbeddedIslandAlbumCover"
    private const val VIEW_TAG = "hyperlyricsenhanced.embedded_album_cover"
    private const val FALLBACK_ICON_DP = 24f

    private data class SmallState(
        val host: FrameLayout,
        val cover: EmbeddedIslandAlbumCoverView,
        var source: WeakReference<ImageView>? = null,
        var fingerprint: Int = Int.MIN_VALUE,
    )

    private data class BigState(
        val target: View,
        val originalBackground: Drawable?,
        val cover: EmbeddedIslandAlbumCoverDrawable,
        var source: WeakReference<ImageView>? = null,
        var fingerprint: Int,
    )

    private val smallStates = WeakHashMap<FrameLayout, SmallState>()
    private val bigStates = WeakHashMap<View, BigState>()
    private val originalVisibility = WeakHashMap<ImageView, Int>()
    private val backgroundDiagnosticListeners = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()
    @Volatile
    private var playbackActive = true

    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
    }

    fun isPlaybackActive(): Boolean = playbackActive

    fun apply(host: ViewGroup, source: ImageView, smallIsland: Boolean): Boolean {
        val artwork = artworkFrom(source) ?: return false
        val applied = if (smallIsland) {
            applySmall(host, source, artwork)
        } else {
            applyBig(host, source, artwork)
        }
        if (!applied) {
            if (artwork.owned && !artwork.bitmap.isRecycled) artwork.bitmap.recycle()
            return false
        }

        synchronized(originalVisibility) {
            originalVisibility.putIfAbsent(source, source.visibility)
        }
        source.visibility = View.INVISIBLE
        return true
    }

    fun restoreForSource(source: ImageView) {
        synchronized(smallStates) {
            val iterator = smallStates.entries.iterator()
            while (iterator.hasNext()) {
                val state = iterator.next().value
                if (state.source?.get() === source) {
                    state.host.removeView(state.cover)
                    state.cover.release()
                    iterator.remove()
                }
            }
        }
        synchronized(bigStates) {
            val iterator = bigStates.entries.iterator()
            while (iterator.hasNext()) {
                val state = iterator.next().value
                if (state.source?.get() === source) {
                    state.target.background = state.originalBackground
                    state.cover.release()
                    iterator.remove()
                }
            }
        }
        synchronized(originalVisibility) {
            originalVisibility.remove(source)?.let { source.visibility = it }
        }
    }

    fun cleanup() {
        synchronized(smallStates) {
            smallStates.values.forEach { state ->
                state.host.removeView(state.cover)
                state.cover.release()
            }
            smallStates.clear()
        }
        synchronized(bigStates) {
            bigStates.values.forEach { state ->
                state.target.background = state.originalBackground
                state.cover.release()
            }
            bigStates.clear()
        }
        synchronized(backgroundDiagnosticListeners) {
            backgroundDiagnosticListeners.forEach { (target, listener) ->
                target.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
            }
            backgroundDiagnosticListeners.clear()
        }
        synchronized(originalVisibility) {
            originalVisibility.forEach { (view, visibility) -> view.visibility = visibility }
            originalVisibility.clear()
        }
        playbackActive = true
    }

    private fun applySmall(
        host: ViewGroup,
        source: ImageView,
        artwork: EmbeddedIslandArtwork,
    ): Boolean {
        val frame = host as? FrameLayout ?: return false
        removeOtherStatesForSource(source, keepSmall = frame, keepBig = null)
        val state = synchronized(smallStates) {
            smallStates[frame] ?: run {
                val cover = EmbeddedIslandAlbumCoverView(frame.context).apply {
                    tag = VIEW_TAG
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                frame.addView(
                    cover,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                SmallState(frame, cover).also { smallStates[frame] = it }
            }
        }
        state.source = WeakReference(source)
        if (state.fingerprint != artwork.fingerprint) {
            state.cover.updateArtwork(artwork)
            state.fingerprint = artwork.fingerprint
        } else if (artwork.owned && !artwork.bitmap.isRecycled) {
            artwork.bitmap.recycle()
        }
        if (frame.indexOfChild(state.cover) != frame.childCount - 1) {
            state.cover.bringToFront()
        }
        logSmallState(state, source, artwork)
        return true
    }

    private fun applyBig(
        host: ViewGroup,
        source: ImageView,
        artwork: EmbeddedIslandArtwork,
    ): Boolean {
        val target = findAreaLeft(host) ?: return false
        removeOtherStatesForSource(source, keepSmall = null, keepBig = target)
        var created = false
        val state = synchronized(bigStates) {
            bigStates[target] ?: run {
                created = true
                val original = target.background
                val cover = EmbeddedIslandAlbumCoverDrawable(
                    originalBackground = original,
                    artwork = artwork,
                    density = target.resources.displayMetrics.density,
                )
                target.background = cover
                BigState(
                    target = target,
                    originalBackground = original,
                    cover = cover,
                    fingerprint = artwork.fingerprint,
                ).also { bigStates[target] = it }
            }
        }
        state.source = WeakReference(source)
        if (state.cover !== target.background) target.background = state.cover
        monitorBackgroundOwnership(target, state.cover, source, host)
        if (!created && state.fingerprint != artwork.fingerprint) {
            state.cover.updateArtwork(artwork)
            state.fingerprint = artwork.fingerprint
        } else if (!created && artwork.owned && !artwork.bitmap.isRecycled) {
            artwork.bitmap.recycle()
        }
        val stableHeight = target.height.takeIf { it > 0 }
            ?: target.measuredHeight.takeIf { it > 0 }
            ?: host.height.takeIf { it > 0 }
        stableHeight?.let(state.cover::prewarmTransitionCache)
        if (BuildConfig.DEBUG) {
            val location = IntArray(2).also(target::getLocationInWindow)
            HookLogger.i(
                TAG,
                "大岛封面背景诊断: host=${resourceName(host) ?: host.javaClass.simpleName}, " +
                    "hostClass=${host.javaClass.name}, hostSize=${host.width}x${host.height}, " +
                    "target=${resourceName(target) ?: target.javaClass.simpleName}, " +
                    "targetClass=${target.javaClass.name}, targetSize=${target.width}x${target.height}, " +
                    "targetLocation=${location[0]},${location[1]}, " +
                    "source=${System.identityHashCode(source)}, drawable=${source.drawable?.javaClass?.simpleName}, " +
                    "bitmap=${artwork.bitmap.width}x${artwork.bitmap.height}, " +
                    "fingerprint=${artwork.fingerprint.toUInt().toString(16)}",
            )
        }
        return true
    }

    private fun monitorBackgroundOwnership(
        target: View,
        expected: EmbeddedIslandAlbumCoverDrawable,
        source: ImageView,
        host: ViewGroup,
    ) {
        if (!BuildConfig.DEBUG) return
        synchronized(backgroundDiagnosticListeners) {
            backgroundDiagnosticListeners.remove(target)?.let { old ->
                target.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(old)
            }
            var frames = 0
            lateinit var listener: ViewTreeObserver.OnPreDrawListener
            listener = ViewTreeObserver.OnPreDrawListener {
                frames += 1
                val current = target.background
                val replaced = current !== expected
                if (frames == 1 || frames == 60 || frames == 120 || frames == 240) {
                    HookLogger.i(
                        TAG,
                        "大岛背景可见性采样: frame=$frames, " +
                            "host=${resourceName(host) ?: host.javaClass.simpleName}@" +
                            System.identityHashCode(host) +
                            ", target=${resourceName(target) ?: target.javaClass.simpleName}@" +
                            System.identityHashCode(target) +
                            ", drawableState=${expected.diagnosticState()}, " +
                            "targetForeground=${drawableDiagnostic(target.foreground)}, " +
                            "sourceState=${imageDrawableDiagnostic(source)}, " +
                            "descendants=${viewDescendantDiagnostic(target)}",
                    )
                }
                if (replaced || frames >= 240 || !target.isAttachedToWindow) {
                    target.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
                    synchronized(backgroundDiagnosticListeners) {
                        if (backgroundDiagnosticListeners[target] === listener) {
                            backgroundDiagnosticListeners.remove(target)
                        }
                    }
                    HookLogger.i(
                        TAG,
                        "大岛背景所有权诊断: result=${if (replaced) "replaced" else "stable"}, " +
                            "frame=$frames, host=${resourceName(host) ?: host.javaClass.simpleName}@" +
                            System.identityHashCode(host) +
                            ", target=${resourceName(target) ?: target.javaClass.simpleName}@" +
                            System.identityHashCode(target) +
                            ", attached=${target.isAttachedToWindow}, size=${target.width}x${target.height}, " +
                            "expected=${expected.javaClass.name}, actual=${current?.javaClass?.name}, " +
                            "drawableAlpha=${expected.diagnosticAlpha()}, " +
                            "drawableState=${expected.diagnosticState()}, " +
                            "targetVisibility=${target.visibility}, targetAlpha=${target.alpha}, " +
                            "hostVisibility=${host.visibility}, hostAlpha=${host.alpha}, " +
                            "ancestors=${viewAncestorDiagnostic(target)}, " +
                            "source=${System.identityHashCode(source)}, visibility=${source.visibility}, " +
                            "drawable=${source.drawable?.javaClass?.name}, " +
                            "sourceState=${imageDrawableDiagnostic(source)}, " +
                            "targetForeground=${drawableDiagnostic(target.foreground)}, " +
                            "descendants=${viewDescendantDiagnostic(target)}",
                    )
                }
                true
            }
            backgroundDiagnosticListeners[target] = listener
            target.viewTreeObserver.takeIf { it.isAlive }?.addOnPreDrawListener(listener)
        }
    }

    private fun viewAncestorDiagnostic(view: View): String {
        val parts = ArrayList<String>()
        var current: View? = view
        var depth = 0
        while (current != null && depth < 8) {
            val location = IntArray(2)
            current.getLocationInWindow(location)
            parts += "${resourceName(current) ?: current.javaClass.simpleName}@" +
                "${System.identityHashCode(current)}:" +
                "v=${current.visibility},a=${current.alpha},s=${current.width}x${current.height}," +
                "xy=${location[0]},${location[1]}"
            current = current.parent as? View
            depth += 1
        }
        return parts.joinToString(">")
    }

    private fun viewDescendantDiagnostic(root: View): String {
        val parts = ArrayList<String>()
        fun visit(view: View, depth: Int) {
            if (parts.size >= 32 || depth > 5) return
            val location = IntArray(2)
            view.getLocationInWindow(location)
            parts += "${resourceName(view) ?: view.javaClass.simpleName}@" +
                "${System.identityHashCode(view)}:" +
                "d=$depth,v=${view.visibility},wv=${view.windowVisibility},shown=${view.isShown}," +
                "a=${view.alpha},opaque=${view.isOpaque},z=${view.z}," +
                "s=${view.width}x${view.height},xy=${location[0]},${location[1]}," +
                "bg=${drawableDiagnostic(view.background)},fg=${drawableDiagnostic(view.foreground)}"
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    visit(view.getChildAt(index), depth + 1)
                    if (parts.size >= 32) break
                }
            }
        }
        visit(root, 0)
        return parts.joinToString(">")
    }

    private fun imageDrawableDiagnostic(view: ImageView): String {
        val drawable = view.drawable
        val bitmapState = (drawable as? BitmapDrawable)?.bitmap?.let(::bitmapDiagnostic)
        return "view=${System.identityHashCode(view)},drawable=${drawableDiagnostic(drawable)}," +
            "bitmap=$bitmapState"
    }

    private fun drawableDiagnostic(drawable: Drawable?): String {
        drawable ?: return "null"
        val color = (drawable as? ColorDrawable)?.color?.toUInt()?.toString(16)
        return "${drawable.javaClass.simpleName}@${System.identityHashCode(drawable)}:" +
            "a=${drawable.alpha}" + (color?.let { ",color=$it" } ?: "")
    }

    private fun findAreaLeft(host: ViewGroup): View? {
        return findViewByNames(host, "area_left", "fake_area_left")
    }

    private fun removeOtherStatesForSource(
        source: ImageView,
        keepSmall: FrameLayout?,
        keepBig: View?,
    ) {
        synchronized(smallStates) {
            val iterator = smallStates.entries.iterator()
            while (iterator.hasNext()) {
                val state = iterator.next().value
                if (state.host !== keepSmall && state.source?.get() === source) {
                    state.host.removeView(state.cover)
                    state.cover.release()
                    iterator.remove()
                }
            }
        }
        synchronized(bigStates) {
            val iterator = bigStates.entries.iterator()
            while (iterator.hasNext()) {
                val state = iterator.next().value
                if (state.target !== keepBig && state.source?.get() === source) {
                    state.target.background = state.originalBackground
                    state.cover.release()
                    iterator.remove()
                }
            }
        }
    }

    private fun findViewByNames(root: ViewGroup, vararg names: String): View? {
        names.forEach { name ->
            if (resourceName(root) == name) return root
            IslandViewHelper.findViewByName(root, name)?.let { return it }
        }
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (resourceName(child) in names) return child
        }
        return null
    }

    private fun logSmallState(
        state: SmallState,
        source: ImageView,
        artwork: EmbeddedIslandArtwork,
    ) {
        if (!BuildConfig.DEBUG) return
        HookLogger.i(
            TAG,
            "小岛封面嵌入诊断: host=${resourceName(state.host) ?: state.host.javaClass.simpleName}, " +
                "hostSize=${state.host.width}x${state.host.height}, " +
                "source=${System.identityHashCode(source)}, sourceSize=${source.width}x${source.height}, " +
                "bitmap=${artwork.bitmap.width}x${artwork.bitmap.height}, " +
                "index=${state.host.indexOfChild(state.cover)}, children=${state.host.childCount}",
        )
    }

    private fun artworkFrom(source: ImageView): EmbeddedIslandArtwork? {
        val drawable = source.drawable ?: return null
        if (drawable is BitmapDrawable) {
            val bitmap = drawable.bitmap ?: return null
            if (!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                return EmbeddedIslandArtwork(
                    bitmap = bitmap,
                    owned = false,
                    fingerprint = artworkFingerprint(bitmap),
                )
            }
        }

        val density = source.resources.displayMetrics.density
        val sourceWidth = drawable.intrinsicWidth.takeIf { it > 0 }
            ?: source.width.takeIf { it > 0 }
            ?: (FALLBACK_ICON_DP * density).roundToInt()
        val sourceHeight = drawable.intrinsicHeight.takeIf { it > 0 }
            ?: source.height.takeIf { it > 0 }
            ?: (FALLBACK_ICON_DP * density).roundToInt()
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val scale = min(1f, 512f / max(sourceWidth, sourceHeight).toFloat())
        val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val oldBounds = Rect(drawable.bounds)
        return runCatching {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            EmbeddedIslandArtwork(
                bitmap = bitmap,
                owned = true,
                fingerprint = artworkFingerprint(bitmap),
            )
        }.getOrNull().also {
            drawable.bounds = oldBounds
            if (it == null && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun artworkFingerprint(bitmap: Bitmap): Int {
        var hash = 31 * bitmap.width + bitmap.height
        return runCatching {
            val columns = minOf(bitmap.width, 3)
            val rows = minOf(bitmap.height, 3)
            repeat(rows) { row ->
                val y = if (rows == 1) 0 else row * (bitmap.height - 1) / (rows - 1)
                repeat(columns) { column ->
                    val x = if (columns == 1) 0 else column * (bitmap.width - 1) / (columns - 1)
                    hash = 31 * hash + bitmap.getPixel(x, y)
                }
            }
            hash
        }.getOrElse {
            31 * hash + bitmap.generationId
        }
    }

}

private fun drawCenterCrop(canvas: Canvas, source: Bitmap, target: RectF, paint: Paint) {
    val scale = max(target.width() / source.width, target.height() / source.height)
    val drawWidth = source.width * scale
    val drawHeight = source.height * scale
    val left = target.centerX() - drawWidth / 2f
    val top = target.centerY() - drawHeight / 2f
    canvas.drawBitmap(source, null, RectF(left, top, left + drawWidth, top + drawHeight), paint)
}

private fun bitmapDiagnostic(bitmap: Bitmap?): String {
    bitmap ?: return "null"
    if (bitmap.isRecycled) return "recycled@${System.identityHashCode(bitmap)}"
    return "${bitmap.width}x${bitmap.height}@${System.identityHashCode(bitmap)}:" +
        "config=${bitmap.config},gen=${bitmap.generationId},alpha=${bitmap.hasAlpha()}"
}

private fun findDescendantByResourceName(root: View, name: String): View? {
    if (resourceName(root) == name) return root
    val group = root as? ViewGroup ?: return null
    for (index in 0 until group.childCount) {
        findDescendantByResourceName(group.getChildAt(index), name)?.let { return it }
    }
    return null
}

private fun verticallyDiffusedEdgeColor(
    edgeColumn: IntArray,
    centerY: Int,
    radius: Float,
): Int {
    val last = edgeColumn.lastIndex
    if (last < 0) return Color.TRANSPARENT
    val safeCenter = centerY.coerceIn(0, last)
    if (radius < 0.5f) return edgeColumn[safeCenter]

    // A Gaussian profile keeps the last-column color coherent near the centre while gradually
    // borrowing nearby rows as the extension grows. A box average made the right side look like
    // flat horizontal bands.
    val sigma = (radius * 0.5f).coerceAtLeast(0.75f)
    val extent = kotlin.math.ceil(radius).toInt().coerceAtLeast(1)
    val denominator = 2f * sigma * sigma
    var weightSum = 0f
    var alpha = 0f
    var red = 0f
    var green = 0f
    var blue = 0f
    for (offset in -extent..extent) {
        val row = (safeCenter + offset).coerceIn(0, last)
        val distance = offset.toFloat()
        val weight = kotlin.math.exp(-(distance * distance) / denominator)
        val color = edgeColumn[row]
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

private fun blendArgbColors(from: Int, to: Int, fraction: Float): Int {
    val progress = fraction.coerceIn(0f, 1f)
    fun blend(start: Int, end: Int): Int =
        (start + (end - start) * progress).roundToInt().coerceIn(0, 255)
    return Color.argb(
        blend(Color.alpha(from), Color.alpha(to)),
        blend(Color.red(from), Color.red(to)),
        blend(Color.green(from), Color.green(to)),
        blend(Color.blue(from), Color.blue(to)),
    )
}

private fun blurArgbPixelsInPlace(
    pixels: IntArray,
    width: Int,
    height: Int,
    radiusX: Int,
    radiusY: Int,
) {
    if (pixels.isEmpty() || width <= 0 || height <= 0) return
    val horizontal = IntArray(pixels.size)
    boxBlurHorizontal(pixels, horizontal, width, height, radiusX.coerceAtLeast(0))
    boxBlurVertical(horizontal, pixels, width, height, radiusY.coerceAtLeast(0))
}

private fun boxBlurHorizontal(
    source: IntArray,
    destination: IntArray,
    width: Int,
    height: Int,
    radius: Int,
) {
    if (radius == 0) {
        source.copyInto(destination)
        return
    }
    val alpha = IntArray(width + 1)
    val red = IntArray(width + 1)
    val green = IntArray(width + 1)
    val blue = IntArray(width + 1)
    for (y in 0 until height) {
        alpha[0] = 0
        red[0] = 0
        green[0] = 0
        blue[0] = 0
        for (x in 0 until width) {
            val color = source[y * width + x]
            alpha[x + 1] = alpha[x] + Color.alpha(color)
            red[x + 1] = red[x] + Color.red(color)
            green[x + 1] = green[x] + Color.green(color)
            blue[x + 1] = blue[x] + Color.blue(color)
        }
        for (x in 0 until width) {
            val start = (x - radius).coerceAtLeast(0)
            val end = (x + radius).coerceAtMost(width - 1)
            val count = end - start + 1
            destination[y * width + x] = Color.argb(
                (alpha[end + 1] - alpha[start] + count / 2) / count,
                (red[end + 1] - red[start] + count / 2) / count,
                (green[end + 1] - green[start] + count / 2) / count,
                (blue[end + 1] - blue[start] + count / 2) / count,
            )
        }
    }
}

private fun boxBlurVertical(
    source: IntArray,
    destination: IntArray,
    width: Int,
    height: Int,
    radius: Int,
) {
    if (radius == 0) {
        source.copyInto(destination)
        return
    }
    val alpha = IntArray(height + 1)
    val red = IntArray(height + 1)
    val green = IntArray(height + 1)
    val blue = IntArray(height + 1)
    for (x in 0 until width) {
        alpha[0] = 0
        red[0] = 0
        green[0] = 0
        blue[0] = 0
        for (y in 0 until height) {
            val color = source[y * width + x]
            alpha[y + 1] = alpha[y] + Color.alpha(color)
            red[y + 1] = red[y] + Color.red(color)
            green[y + 1] = green[y] + Color.green(color)
            blue[y + 1] = blue[y] + Color.blue(color)
        }
        for (y in 0 until height) {
            val start = (y - radius).coerceAtLeast(0)
            val end = (y + radius).coerceAtMost(height - 1)
            val count = end - start + 1
            destination[y * width + x] = Color.argb(
                (alpha[end + 1] - alpha[start] + count / 2) / count,
                (red[end + 1] - red[start] + count / 2) / count,
                (green[end + 1] - green[start] + count / 2) / count,
                (blue[end + 1] - blue[start] + count / 2) / count,
            )
        }
    }
}

private fun resourceName(view: View?): String? {
    val target = view ?: return null
    if (target.id == View.NO_ID) return null
    return runCatching { target.resources.getResourceEntryName(target.id) }.getOrNull()
}
