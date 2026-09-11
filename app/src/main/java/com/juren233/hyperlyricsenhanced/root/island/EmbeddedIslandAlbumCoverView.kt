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
