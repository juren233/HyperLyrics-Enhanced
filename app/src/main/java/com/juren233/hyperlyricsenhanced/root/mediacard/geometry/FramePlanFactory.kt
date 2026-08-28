/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.geometry

import com.juren233.hyperlyricsenhanced.root.mediacard.MediaCardFullAodTransitionMode
import com.juren233.hyperlyricsenhanced.root.mediacard.transition.MediaCardFramePlan

/** Bridges host geometry/target metrics to the single native-fraction frame plan. */
internal object FramePlanFactory {
    fun create(
        fraction: Float,
        targetFullAod: Boolean,
        mode: MediaCardFullAodTransitionMode,
        geometry: MediaCardGeometrySnapshot,
        targetCardHeight: Int?,
        keepSecondLyric: Boolean,
        secondaryTextSizeSp: Float?,
        secondaryTopOffsetPx: Int?,
        secondaryAlpha: Int?,
        secondaryVisible: Boolean,
    ): MediaCardFramePlan = MediaCardFramePlan.interpolate(
        fraction = fraction,
        targetFullAod = targetFullAod,
        mode = mode,
        startCardHeight = geometry.playerHeight.takeIf { it > 0 },
        targetCardHeight = targetCardHeight,
        keepSecondLyric = keepSecondLyric,
        secondaryTextSizeSp = secondaryTextSizeSp,
        secondaryTopOffsetPx = secondaryTopOffsetPx,
        secondaryAlpha = secondaryAlpha,
        secondaryVisible = secondaryVisible,
    )
}
