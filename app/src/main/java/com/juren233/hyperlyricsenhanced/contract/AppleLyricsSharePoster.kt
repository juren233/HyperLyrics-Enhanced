/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.contract

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Single source of the Apple Music lyric share poster geometry and colours.
 *
 * The Apple Music Provider draws the exported poster with it and the core settings page draws the
 * background preview with it, so both always agree on proportions, the flat "cover colour" and
 * the footer text colour. Plain Kotlin only (no android.graphics), so it runs in JVM unit tests on
 * both sides.
 */
object AppleLyricsSharePoster {
    /** Pure layout in pixels; every size derives from the card width. */
    data class Layout(
        val width: Int,
        val height: Int,
        val padding: Int,
        val cardLeft: Int,
        val cardTop: Int,
        val footerTop: Int,
        val qrTile: Int,
        val qrInset: Int,
        val qrLeft: Int,
        val qrTop: Int,
        val textLeft: Int,
        val textRight: Int,
        val titleSize: Float,
        val subtitleSize: Float,
        val tileRadius: Float,
        val shadowRadius: Float,
        val cardCornerRadius: Float,
    )

    fun layout(cardWidth: Int, cardHeight: Int, withQr: Boolean): Layout {
        val unit = cardWidth / 100f
        val padding = (unit * 6f).roundToInt().coerceAtLeast(16)
        val qrTile = if (withQr) (unit * 22f).roundToInt() else 0
        val qrInset = (unit * 1.6f).roundToInt().coerceAtLeast(6)
        val footerTop = padding + cardHeight + if (withQr) (unit * 5f).roundToInt() else 0
        val height = if (withQr) footerTop + qrTile + padding else footerTop + padding
        val width = cardWidth + padding * 2
        val qrLeft = width - padding - qrTile
        return Layout(
            width = width,
            height = height,
            padding = padding,
            cardLeft = padding,
            cardTop = padding,
            footerTop = footerTop,
            qrTile = qrTile,
            qrInset = qrInset,
            qrLeft = qrLeft,
            qrTop = footerTop,
            textLeft = padding + (unit * 1.5f).roundToInt(),
            textRight = qrLeft - (unit * 5f).roundToInt(),
            titleSize = unit * 4.6f,
            subtitleSize = unit * 3.3f,
            tileRadius = unit * 3f,
            shadowRadius = unit * 3.5f,
            cardCornerRadius = cardWidth * 0.035f,
        )
    }

    /** Source window, in source pixels, that fills a destination of the given size. */
    data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** Centred "cover" crop: scales uniformly to fill the destination and trims the overflow. */
    fun coverCrop(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): Crop {
        if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) {
            return Crop(0, 0, srcWidth, srcHeight)
        }
        // Source is wider than the destination when srcW / srcH > dstW / dstH.
        return if (srcWidth.toLong() * dstHeight > dstWidth.toLong() * srcHeight) {
            val width = (srcHeight.toLong() * dstWidth / dstHeight).toInt().coerceIn(1, srcWidth)
            val left = (srcWidth - width) / 2
            Crop(left, 0, left + width, srcHeight)
        } else {
            val height = (srcWidth.toLong() * dstHeight / dstWidth).toInt().coerceIn(1, srcHeight)
            val top = (srcHeight - height) / 2
            Crop(0, top, srcWidth, top + height)
        }
    }

    /**
     * Poster area whose average colour decides the footer text colour over an image background:
     * the text column beside the QR tile.
     */
    fun footerTextSampleArea(layout: Layout): Crop =
        Crop(layout.textLeft, layout.qrTop, layout.textRight, layout.qrTop + layout.qrTile)

    /** Grid size (per axis) used when averaging colours from the card or the backdrop. */
    const val SAMPLE_GRID = 12

    /** "Cover colour" background: the card's average colour darkened towards black. */
    fun coverColorBackground(sampled: Int): Int = blend(sampled, BLACK, COVER_COLOR_DARKEN)

    /** White or near-black, whichever has the higher WCAG contrast on [background]. */
    fun foregroundFor(background: Int): Int {
        val backgroundLuminance = relativeLuminance(background)
        val onWhite = 1.05 / (backgroundLuminance + 0.05)
        val onDark = (backgroundLuminance + 0.05) / (relativeLuminance(DARK_TEXT) + 0.05)
        return if (onWhite >= onDark) WHITE else DARK_TEXT
    }

    /** Footer title next to the QR code. */
    fun qrTitle(chinese: Boolean): String = if (chinese) "扫码收听完整歌曲" else "Scan to listen"

    /** Footer subtitle next to the QR code. */
    fun qrSubtitle(chinese: Boolean): String = if (chinese) "在 Apple Music 中打开" else "Open in Apple Music"

    /** Alpha of the footer subtitle relative to the title colour. */
    const val SUBTITLE_ALPHA = 170

    /** Shadow under the card (ARGB). */
    const val SHADOW_COLOR = 0x55000000

    const val WHITE = 0xFFFFFFFF.toInt()
    const val DARK_TEXT = 0xFF1C1C1E.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val COVER_COLOR_DARKEN = 0.42f

    private fun channel(color: Int, shift: Int) = (color shr shift) and 0xFF

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun blend(from: Int, to: Int, ratio: Float): Int {
        val keep = 1f - ratio
        return rgb(
            (channel(from, 16) * keep + channel(to, 16) * ratio).roundToInt(),
            (channel(from, 8) * keep + channel(to, 8) * ratio).roundToInt(),
            (channel(from, 0) * keep + channel(to, 0) * ratio).roundToInt(),
        )
    }

    /** WCAG relative luminance (linearised sRGB), 0 = black, 1 = white. */
    fun relativeLuminance(color: Int): Double {
        fun linear(shift: Int): Double {
            val value = channel(color, shift) / 255.0
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }
}
