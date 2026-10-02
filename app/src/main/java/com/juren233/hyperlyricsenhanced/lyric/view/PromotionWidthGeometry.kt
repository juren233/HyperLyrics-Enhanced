/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.view

/** Shared width and placement rules for next-line promotion in dynamic-width slots. */
internal object PromotionWidthGeometry {
    fun reserveWidth(
        pendingWidth: Int?,
        currentWidth: Float,
        hugContentWidth: Boolean,
        scaledPreviewWidth: Float,
        targetTextWidth: Float
    ): Int? {
        val pending = pendingWidth ?: return null
        if (!hugContentWidth || pending <= currentWidth) return null
        return pending.takeIf { maxOf(scaledPreviewWidth, targetTextWidth) > currentWidth }
    }

    fun placementFactor(
        alignRight: Boolean,
        center: Boolean,
        lineAlignedRight: Boolean
    ): Float = when {
        alignRight -> 1f
        center -> 0.5f
        lineAlignedRight -> 1f
        else -> 0f
    }

    fun childOffset(reservedWidth: Int?, currentWidth: Float, factor: Float): Float {
        val reserved = reservedWidth ?: return 0f
        return ((reserved - currentWidth).coerceAtLeast(0f)) * factor
    }
}
