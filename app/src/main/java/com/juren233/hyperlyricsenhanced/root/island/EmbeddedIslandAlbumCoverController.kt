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
        if (playbackActive == active) return
        playbackActive = active
        synchronized(smallStates) {
            smallStates.values.forEach { it.cover.invalidate() }
        }
        synchronized(bigStates) {
            bigStates.values.forEach { it.target.invalidate() }
        }
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

internal fun drawCenterCrop(canvas: Canvas, source: Bitmap, target: RectF, paint: Paint) {
    val scale = max(target.width() / source.width, target.height() / source.height)
    val drawWidth = source.width * scale
    val drawHeight = source.height * scale
    val left = target.centerX() - drawWidth / 2f
    val top = target.centerY() - drawHeight / 2f
    canvas.drawBitmap(source, null, RectF(left, top, left + drawWidth, top + drawHeight), paint)
}

internal fun bitmapDiagnostic(bitmap: Bitmap?): String {
    bitmap ?: return "null"
    if (bitmap.isRecycled) return "recycled@${System.identityHashCode(bitmap)}"
    return "${bitmap.width}x${bitmap.height}@${System.identityHashCode(bitmap)}:" +
        "config=${bitmap.config},gen=${bitmap.generationId},alpha=${bitmap.hasAlpha()}"
}

internal fun findDescendantByResourceName(root: View, name: String): View? {
    if (resourceName(root) == name) return root
    val group = root as? ViewGroup ?: return null
    for (index in 0 until group.childCount) {
        findDescendantByResourceName(group.getChildAt(index), name)?.let { return it }
    }
    return null
}

internal fun verticallyDiffusedEdgeColor(
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

internal fun blendArgbColors(from: Int, to: Int, fraction: Float): Int {
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

internal fun blurArgbPixelsInPlace(
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

internal fun resourceName(view: View?): String? {
    val target = view ?: return null
    if (target.id == View.NO_ID) return null
    return runCatching { target.resources.getResourceEntryName(target.id) }.getOrNull()
}
