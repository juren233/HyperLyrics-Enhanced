/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.createBitmap
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.IslandAlbumCoverWhitelist
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.mediacard.island.IslandExpandedMediaAmbientFlowHooker
import com.juren233.hyperlyricsenhanced.root.mediacard.island.onIslandAlbumIconUpdated
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.SystemUiEnhancementGate
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.lyric.view.line.LyricTextPaintOwner
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class ArtworkIdentity(
    val packageName: String,
    val title: String,
)

internal fun IslandAlbumCoverStyleHooker.resolveArtworkIdentity(
    fixIcon: ImageView,
    dynamicIslandData: Any,
): ArtworkIdentity? {
    val packageName = IslandProbeUtils.extractMediaIslandInfo(dynamicIslandData)
        ?.packageName
        ?.takeIf(String::isNotBlank)
        ?: return null
    val token = MediaMetadataHelper.currentArtworkCaptureToken(
        fixIcon.context,
        packageName,
    ) ?: return null
    val lyricSong = LyriconDataBridge.currentSong
    if (IslandSlotContentAssembler.shouldRejectArtworkForTitleMismatch(
            lyricTitle = lyricSong?.name ?: LyriconDataBridge.currentSongName,
            mediaTitle = token.title,
            lyricArtist = lyricSong?.artist,
            mediaArtist = token.artist,
            mediaAlbum = token.album,
        )
    ) {
        return null
    }
    return ArtworkIdentity(packageName = packageName, title = token.title)
}

internal fun IslandAlbumCoverStyleHooker.ensureArtworkContinuity(imageView: ImageView, source: String): Boolean {
    if (imageView.drawable.hasUsableArtworkPixels()) return false
    val identity = synchronized(artworkIdentityByView) {
        artworkIdentityByView[imageView]
    } ?: return false
    val bitmap = MediaMetadataHelper.currentCachedArtwork(
        context = imageView.context,
        packageName = identity.packageName,
        expectedTitle = identity.title,
    ) ?: return false
    imageView.setImageBitmap(bitmap)
    if (BuildConfig.DEBUG) {
        HookLogger.d(
            IslandAlbumCoverStyleHooker.TAG,
            "渐变封面复用当前歌曲缓存: source=$source, package=${identity.packageName}, " +
                "title=${identity.title}, size=${bitmap.width}x${bitmap.height}",
        )
    }
    return true
}

internal fun Drawable?.hasUsableArtworkPixels(): Boolean {
    val drawable = this ?: return false
    if (drawable !is BitmapDrawable) {
        return drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0
    }
    val bitmap = drawable.bitmap ?: return false
    return IslandAlbumCoverStyleHooker.hasVisibleArtworkPixels(bitmap)
}

internal fun IslandAlbumCoverStyleHooker.scheduleNativeArtworkCapture(fixIcon: ImageView, dynamicIslandData: Any) {
    val packageName = IslandProbeUtils.extractMediaIslandInfo(dynamicIslandData)
        ?.packageName
        ?.takeIf(String::isNotBlank)
        ?: return
    val generation = synchronized(captureGenerationByView) {
        ((captureGenerationByView[fixIcon] ?: 0) + 1).also {
            captureGenerationByView[fixIcon] = it
        }
    }
    CAPTURE_DELAYS_MS.forEach { delayMs ->
        fixIcon.postDelayed(
            {
                val stillCurrent = synchronized(captureGenerationByView) {
                    captureGenerationByView[fixIcon] == generation
                }
                if (!stillCurrent) return@postDelayed
                val token = MediaMetadataHelper.currentArtworkCaptureToken(
                    fixIcon.context,
                    packageName,
                ) ?: return@postDelayed
                val lyricSong = LyriconDataBridge.currentSong
                if (IslandSlotContentAssembler.shouldRejectArtworkForTitleMismatch(
                        lyricTitle = lyricSong?.name
                            ?: LyriconDataBridge.currentSongName,
                        mediaTitle = token.title,
                        lyricArtist = lyricSong?.artist,
                        mediaArtist = token.artist,
                        mediaAlbum = token.album,
                    )
                ) {
                    return@postDelayed
                }
                val capture = fixIcon.drawable.toCaptureBitmap(fixIcon) ?: return@postDelayed
                val cached = MediaMetadataHelper.cacheCapturedArtwork(
                    context = fixIcon.context,
                    token = token,
                    bitmap = capture.bitmap,
                    logger = HookLogger,
                )
                if (!cached && capture.owned) capture.bitmap.recycle()
                if (cached) {
                    synchronized(captureGenerationByView) {
                        if (captureGenerationByView[fixIcon] == generation) {
                            captureGenerationByView[fixIcon] = generation + 1
                        }
                    }
                }
            },
            delayMs,
        )
    }
}

internal fun Drawable?.toCaptureBitmap(view: ImageView): CapturedBitmap? {
    val drawable = this ?: return null
    if (drawable is BitmapDrawable) {
        return drawable.bitmap
            ?.takeIf{ IslandAlbumCoverStyleHooker.hasVisibleArtworkPixels(it) }
            ?.let { CapturedBitmap(it, owned = false) }
    }
    val sourceWidth = drawable.intrinsicWidth.takeIf { it > 0 }
        ?: view.width.takeIf { it > 0 }
        ?: return null
    val sourceHeight = drawable.intrinsicHeight.takeIf { it > 0 }
        ?: view.height.takeIf { it > 0 }
        ?: return null
    val maxDimension = maxOf(sourceWidth, sourceHeight)
    val scale = if (maxDimension > IslandAlbumCoverStyleHooker.CAPTURE_MAX_DIMENSION) {
        IslandAlbumCoverStyleHooker.CAPTURE_MAX_DIMENSION.toFloat() / maxDimension.toFloat()
    } else {
        1f
    }
    val width = (sourceWidth * scale).toInt().coerceAtLeast(1)
    val height = (sourceHeight * scale).toInt().coerceAtLeast(1)
    val bitmap = createBitmap(width, height)
    val oldBounds = android.graphics.Rect(drawable.bounds)
    return runCatching {
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        bitmap.takeIf{ IslandAlbumCoverStyleHooker.hasVisibleArtworkPixels(it) }?.let {
            CapturedBitmap(it, owned = true)
        }
    }.getOrNull().also {
        drawable.bounds = oldBounds
        if (it == null) bitmap.recycle()
    }
}

internal fun IslandAlbumCoverStyleHooker.hasVisibleArtworkPixels(bitmap: Bitmap): Boolean {
    if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
    return runCatching {
        val columns = minOf(bitmap.width, 4)
        val rows = minOf(bitmap.height, 4)
        repeat(rows) { row ->
            val y = if (rows == 1) 0 else row * (bitmap.height - 1) / (rows - 1)
            repeat(columns) { column ->
                val x = if (columns == 1) 0 else column * (bitmap.width - 1) / (columns - 1)
                if ((bitmap.getPixel(x, y) ushr 24) != 0) return true
            }
        }
        false
    }.getOrElse {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        try {
            IslandAlbumCoverStyleHooker.hasVisibleArtworkPixels(copy)
        } finally {
            copy.recycle()
        }
    }
}

internal data class CapturedBitmap(
    val bitmap: Bitmap,
    val owned: Boolean,
)

internal data class CoverVisualSnapshot(
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float,
    val coverWidth: Int,
    val coverHeight: Int,
    val smallIsland: Boolean,
    val gradientBandFraction: Float,
    val islandColor: Int,
)

internal data class TextShadowTargetSnapshot(
    val view: View,
    val paint: Paint,
    val radius: Float,
    val dx: Float,
    val dy: Float,
    val color: Int,
)

