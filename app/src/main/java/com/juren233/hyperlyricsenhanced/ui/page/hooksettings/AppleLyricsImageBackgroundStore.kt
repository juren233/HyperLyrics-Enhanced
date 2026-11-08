/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.TextPaint
import android.text.TextUtils
import com.juren233.hyperlyricsenhanced.contract.AppleLyricsSharePoster
import com.juren233.hyperlyricsenhanced.contract.AppleMusicPreferenceKeys
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog
import com.juren233.hyperlyricsenhanced.root.RootApplication
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The custom lyric share background: the picked image is kept privately, blur and brightness are
 * applied here, and the finished image is published as an Xposed Remote File that the Apple Music
 * Provider only crops onto the poster. The preview runs the exact same [render] at a lower
 * resolution; blur scales with the image's short side, so both look the same.
 */
internal object AppleLyricsImageBackgroundStore {
    /** Longest edge kept from the picked image. */
    private const val MAX_SOURCE_EDGE = 2048

    /** Longest edge of the in-page preview source. */
    const val PREVIEW_EDGE = 720

    /** Blur at level 100, as a share of the image's short side. */
    private const val MAX_BLUR_FRACTION = 0.08f

    private const val SOURCE_FILE = "apple_lyrics_image_background_source.jpg"
    private const val JPEG_QUALITY = 92

    private fun sourceFile(context: Context) = File(context.filesDir, SOURCE_FILE)

    fun hasSource(context: Context): Boolean = sourceFile(context).isFile

    /** Identifies the kept source (0 when none); changes whenever a new image is imported. */
    fun sourceStamp(context: Context): Long = sourceFile(context).let { file ->
        if (file.isFile) file.lastModified() * 31 + file.length() else 0L
    }

    /** Decodes the picked image, downscaled to [MAX_SOURCE_EDGE], and keeps it as the new source. */
    fun importSource(context: Context, uri: Uri): Boolean = runCatching {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_SOURCE_EDGE) {
                val scale = MAX_SOURCE_EDGE.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
        try {
            sourceFile(context).outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output))
            }
        } finally {
            bitmap.recycle()
        }
        true
    }.getOrDefault(false)

    /** The kept source, optionally downscaled so its longest edge is at most [maxEdge]. */
    fun loadSource(context: Context, maxEdge: Int = Int.MAX_VALUE): Bitmap? {
        val file = sourceFile(context).takeIf(File::isFile) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        val decodedLongest = max(decoded.width, decoded.height)
        if (decodedLongest <= maxEdge) return decoded
        val scale = maxEdge.toFloat() / decodedLongest
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).roundToInt().coerceAtLeast(1),
            (decoded.height * scale).roundToInt().coerceAtLeast(1),
            true,
        ).also { if (it !== decoded) decoded.recycle() }
    }

    /** Applies blur (0..100) and brightness (-100..100) to [source]; returns a new bitmap. */
    fun render(source: Bitmap, blur: Int, brightness: Int): Bitmap {
        val width = source.width
        val height = source.height
        val radius = blur.coerceIn(0, 100) / 100f * MAX_BLUR_FRACTION * min(width, height)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(brightnessMatrix(brightness))
        }
        if (radius < 0.5f) {
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(source, 0f, 0f, paint)
            }
        }
        val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1, usage).use { reader ->
            val node = RenderNode("AppleLyricsImageBackground").apply {
                setPosition(0, 0, width, height)
                // CLAMP keeps the edges opaque instead of fading into transparent black.
                setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
            }
            node.beginRecording().drawBitmap(source, 0f, 0f, paint)
            node.endRecording()
            val renderer = HardwareRenderer()
            try {
                renderer.setSurface(reader.surface)
                renderer.setContentRoot(node)
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                reader.acquireNextImage().use { image ->
                    val buffer = checkNotNull(image.hardwareBuffer) { "no hardware buffer" }
                    buffer.use {
                        val wrapped = checkNotNull(
                            Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)),
                        ) { "wrapHardwareBuffer failed" }
                        return wrapped.copy(Bitmap.Config.ARGB_8888, false).also { wrapped.recycle() }
                    }
                }
            } finally {
                renderer.destroy()
                node.discardDisplayList()
            }
        }
    }

    /** -100 blends fully to black, 100 fully to white, 0 leaves the image unchanged. */
    private fun brightnessMatrix(brightness: Int): ColorMatrix {
        val t = brightness.coerceIn(-100, 100) / 100f
        val scale = if (t < 0f) 1f + t else 1f - t
        val offset = if (t > 0f) 255f * t else 0f
        return ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, offset,
                0f, scale, 0f, 0f, offset,
                0f, 0f, scale, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }

    /**
     * Renders the full-resolution source and publishes it for the Provider. Returns false when
     * there is no source or the Xposed service is unavailable.
     */
    fun publish(context: Context, blur: Int, brightness: Int): Boolean = runCatching {
        val service = RootApplication.xposedService ?: return false
        val source = loadSource(context) ?: return false
        val bytes = try {
            val rendered = render(source, blur, brightness)
            try {
                ByteArrayOutputStream().use { output ->
                    check(rendered.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output))
                    output.toByteArray()
                }
            } finally {
                rendered.recycle()
            }
        } finally {
            source.recycle()
        }
        val name = AppleMusicPreferenceKeys.APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_FILE
        // Remove first so a smaller image never keeps trailing bytes of the previous one.
        if (name in service.listRemoteFiles()) service.deleteRemoteFile(name)
        service.openRemoteFile(name).use { descriptor ->
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { it.write(bytes) }
        }
        true
    }.getOrDefault(false)

    // ---- Preview ----

    /** Preview poster scale relative to Apple's render size. */
    private const val PREVIEW_SCALE = 0.5f

    /** Bundled resources rendered by Apple Music itself (lyric card and its story background). */
    fun loadSample(context: Context, resId: Int): Bitmap? = BitmapFactory.decodeResource(
        context.resources,
        resId,
        BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )

    /**
     * Draws a preview poster the way the Provider composes the export: [background] cover-cropped
     * behind Apple's real lyric [card], plus the QR footer when [withQr]. Geometry and colours
     * come from [AppleLyricsSharePoster], the same source the Provider uses.
     */
    fun composePreview(background: Bitmap, card: Bitmap, wordmark: Drawable?, withQr: Boolean): Bitmap =
        composePreview(card, wordmark, withQr) { canvas, width, height ->
            canvas.drawColor(OPAQUE)
            val crop = AppleLyricsSharePoster.coverCrop(background.width, background.height, width, height)
            canvas.drawBitmap(
                background,
                Rect(crop.left, crop.top, crop.right, crop.bottom),
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }

    /** Same poster over a flat [color]; drawn opaque, as the Provider paints its flat background. */
    fun composePreview(color: Int, card: Bitmap, wordmark: Drawable?, withQr: Boolean): Bitmap =
        composePreview(card, wordmark, withQr) { canvas, _, _ -> canvas.drawColor(color or OPAQUE) }

    /**
     * Apple Music's wordmark from the installed app, for the footer subtitle; null when Apple Music
     * is not installed or has no such drawable, and the subtitle then reads "Apple Music" as text.
     */
    fun loadWordmark(context: Context): Drawable? = runCatching {
        val packageName = OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME
        val resources = context.packageManager.getResourcesForApplication(packageName)
        @Suppress("DiscouragedApi") // Another app's resource: only reachable by name.
        val id = resources.getIdentifier(AppleLyricsSharePoster.WORDMARK_DRAWABLE, "drawable", packageName)
        if (id == 0) null else resources.getDrawable(id, null)
    }.getOrNull()

    /** Width / height of the preview poster for a sample card of [cardWidth] x [cardHeight]. */
    fun previewAspectRatio(cardWidth: Int, cardHeight: Int, withQr: Boolean): Float =
        AppleLyricsSharePoster.layout(previewEdge(cardWidth), previewEdge(cardHeight), withQr)
            .let { it.width.toFloat() / it.height }

    private fun previewEdge(cardEdge: Int) = (cardEdge * PREVIEW_SCALE).roundToInt()

    private fun composePreview(
        card: Bitmap,
        wordmark: Drawable?,
        withQr: Boolean,
        drawBackground: (Canvas, Int, Int) -> Unit,
    ): Bitmap {
        val cardWidth = previewEdge(card.width)
        val cardHeight = previewEdge(card.height)
        val spec = AppleLyricsSharePoster.layout(cardWidth, cardHeight, withQr)
        val poster = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(poster)
        drawBackground(canvas, spec.width, spec.height)
        // Sampled from the bare background, before the card and its shadow, as the export does.
        val footerForeground = AppleLyricsSharePoster.footerTextSampleArea(spec).let { area ->
            AppleLyricsSharePoster.foregroundFor(
                averageColor(poster, Rect(area.left, area.top, area.right, area.bottom)),
            )
        }
        val cardRect = RectF(
            spec.cardLeft.toFloat(),
            spec.cardTop.toFloat(),
            (spec.cardLeft + cardWidth).toFloat(),
            (spec.cardTop + cardHeight).toFloat(),
        )
        canvas.drawRoundRect(
            RectF(cardRect).apply { offset(0f, spec.shadowRadius / 3f) },
            spec.cardCornerRadius,
            spec.cardCornerRadius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AppleLyricsSharePoster.SHADOW_COLOR
                maskFilter = BlurMaskFilter(spec.shadowRadius.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
            },
        )
        canvas.drawBitmap(card, null, cardRect, Paint(Paint.FILTER_BITMAP_FLAG))
        if (withQr) drawFooter(canvas, spec, footerForeground, wordmark)
        return poster
    }

    /** Footer with a placeholder QR tile and the same title/subtitle the export uses. */
    private fun drawFooter(
        canvas: Canvas,
        spec: AppleLyricsSharePoster.Layout,
        foreground: Int,
        wordmark: Drawable?,
    ) {
        val tile = RectF(
            spec.qrLeft.toFloat(),
            spec.qrTop.toFloat(),
            (spec.qrLeft + spec.qrTile).toFloat(),
            (spec.qrTop + spec.qrTile).toFloat(),
        )
        canvas.drawRoundRect(tile, spec.tileRadius, spec.tileRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        })
        drawPlaceholderQr(canvas, RectF(tile).apply { inset(spec.qrInset.toFloat(), spec.qrInset.toFloat()) })

        val chinese = Locale.getDefault().language == Locale.CHINESE.language
        val available = (spec.textRight - spec.textLeft).toFloat().coerceAtLeast(1f)
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foreground
            textSize = spec.titleSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foreground
            alpha = AppleLyricsSharePoster.SUBTITLE_ALPHA
            textSize = spec.subtitleSize
        }
        val title = TextUtils.ellipsize(AppleLyricsSharePoster.qrTitle(chinese), titlePaint, available, TextUtils.TruncateAt.END)
        val gap = spec.subtitleSize * 0.55f
        val titleHeight = titlePaint.fontMetrics.let { it.descent - it.ascent }
        val subtitleHeight = subtitlePaint.fontMetrics.let { it.descent - it.ascent }
        val blockTop = tile.centerY() - (titleHeight + gap + subtitleHeight) / 2f
        canvas.drawText(title, 0, title.length, spec.textLeft.toFloat(), blockTop - titlePaint.fontMetrics.ascent, titlePaint)
        drawSubtitle(
            canvas = canvas,
            paint = subtitlePaint,
            left = spec.textLeft.toFloat(),
            baseline = blockTop + titleHeight + gap - subtitlePaint.fontMetrics.ascent,
            available = available,
            chinese = chinese,
            wordmark = wordmark,
        )
    }

    /** Same drawing as the Provider's export footer: text, Apple's wordmark, text. */
    private fun drawSubtitle(
        canvas: Canvas,
        paint: TextPaint,
        left: Float,
        baseline: Float,
        available: Float,
        chinese: Boolean,
        wordmark: Drawable?,
    ) {
        // A fresh copy per render: previews may render concurrently while a slider moves.
        val mark = wordmark?.constantState?.newDrawable()?.mutate()
            ?.takeIf { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 }
        if (mark == null) {
            val text = TextUtils.ellipsize(AppleLyricsSharePoster.qrSubtitle(chinese), paint, available, TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, left, baseline, paint)
            return
        }
        val (before, after) = AppleLyricsSharePoster.qrSubtitleAroundWordmark(chinese)
        val capHeight = Rect().also { paint.getTextBounds("H", 0, 1, it) }.height().toFloat()
        val height = AppleLyricsSharePoster.wordmarkHeight(capHeight)
        val width = height * mark.intrinsicWidth / mark.intrinsicHeight
        val top = AppleLyricsSharePoster.wordmarkTop(baseline, height)
        var x = left
        canvas.drawText(before, x, baseline, paint)
        x += paint.measureText(before)
        mark.setBounds(x.roundToInt(), top.roundToInt(), (x + width).roundToInt(), (top + height).roundToInt())
        // Same colour and alpha as the subtitle text.
        mark.setTint(paint.color)
        mark.draw(canvas)
        x += width
        canvas.drawText(after, x, baseline, paint)
    }

    /** Three finder squares and a sparse module pattern; only stands in for the real code. */
    private fun drawPlaceholderQr(canvas: Canvas, area: RectF) {
        val modules = 21
        val cell = area.width() / modules
        val paint = Paint().apply { color = Color.BLACK }
        fun finder(column: Int, row: Int) {
            val left = area.left + column * cell
            val top = area.top + row * cell
            canvas.drawRect(left, top, left + cell * 7, top + cell * 7, paint)
            canvas.drawRect(left + cell, top + cell, left + cell * 6, top + cell * 6, Paint().apply { color = Color.WHITE })
            canvas.drawRect(left + cell * 2, top + cell * 2, left + cell * 5, top + cell * 5, paint)
        }
        finder(0, 0)
        finder(modules - 7, 0)
        finder(0, modules - 7)
        for (row in 0 until modules) {
            for (column in 0 until modules) {
                val inFinder = (row < 8 && column < 8) || (row < 8 && column >= modules - 8) ||
                    (row >= modules - 8 && column < 8)
                if (inFinder || (row * 7 + column * 3 + row * column) % 5 > 1) continue
                val left = area.left + column * cell
                val top = area.top + row * cell
                canvas.drawRect(left, top, left + cell, top + cell, paint)
            }
        }
    }

    private fun averageColor(bitmap: Bitmap, area: Rect): Int {
        val grid = AppleLyricsSharePoster.SAMPLE_GRID
        val l = area.left.coerceIn(0, bitmap.width - 1)
        val t = area.top.coerceIn(0, bitmap.height - 1)
        val r = area.right.coerceIn(l + 1, bitmap.width)
        val b = area.bottom.coerceIn(t + 1, bitmap.height)
        var red = 0L
        var green = 0L
        var blue = 0L
        for (row in 0 until grid) {
            val y = (t + (b - t) * (2 * row + 1) / (2 * grid)).coerceAtMost(bitmap.height - 1)
            for (column in 0 until grid) {
                val x = (l + (r - l) * (2 * column + 1) / (2 * grid)).coerceAtMost(bitmap.width - 1)
                val pixel = bitmap.getPixel(x, y)
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
            }
        }
        val count = grid * grid
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private const val OPAQUE = 0xFF000000.toInt()
}
