/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.geometry

import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/** Bounds are always untransformed coordinates local to [player]. */
internal data class LocalViewBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

internal data class MediaCardGeometrySnapshot(
    val playerWidth: Int,
    val playerHeight: Int,
    val cardBottom: Int,
    val anchor: LocalViewBounds?,
    val progress: LocalViewBounds?,
    val controls: LocalViewBounds?,
    val actions: List<LocalViewBounds>,
    val contentBottom: Int,
    val safeBottomInset: Int,
    val lyricTop: Int,
    val valid: Boolean,
) {
    val actionTop: Int? get() = actions.minOfOrNull { it.top }
    val actionBottom: Int? get() = actions.maxOfOrNull { it.bottom }
    val progressTop: Int? get() = progress?.top
    val progressBottom: Int? get() = progress?.bottom
}

/** Converts a concrete host subtree to one coherent player-local geometry snapshot. */
internal class GeometryResolver {
    fun resolve(
        player: ViewGroup,
        anchor: View?,
        controls: View?,
        progress: View?,
        actions: List<View>,
    ): MediaCardGeometrySnapshot {
        val bounds = { view: View? -> view?.let { localBounds(it, player) } }
        val anchorBounds = bounds(anchor)
        val progressBounds = bounds(progress)
        val controlBounds = bounds(controls)
        val actionBounds = actions.mapNotNull(bounds)
        val contentBottom = max(
            max(progressBounds?.bottom ?: 0, actionBounds.maxOfOrNull { it.bottom } ?: 0),
            controlBounds?.bottom ?: 0,
        )
        val playerHeight = measuredHeight(player)
        val cardBottom = playerHeight.coerceAtLeast(0)
        val lyricTop = anchorBounds?.bottom ?: 0
        val safeBottomInset = (cardBottom - contentBottom).coerceAtLeast(0)
        return MediaCardGeometrySnapshot(
            playerWidth = player.width.coerceAtLeast(player.measuredWidth),
            playerHeight = playerHeight,
            cardBottom = cardBottom,
            anchor = anchorBounds,
            progress = progressBounds,
            controls = controlBounds,
            actions = actionBounds,
            contentBottom = contentBottom,
            safeBottomInset = safeBottomInset,
            lyricTop = lyricTop,
            valid = player.width > 0 && playerHeight > 0,
        )
    }

    /**
     * The card target is derived from the measured lyric root and the actual host
     * insets, never from screen coordinates or an OS-specific fixed card height.
     */
    fun requiredBottom(
        geometry: MediaCardGeometrySnapshot,
        lyricHeight: Int,
        topInset: Int = 0,
        bottomInset: Int = geometry.safeBottomInset,
    ): Int? {
        if (!geometry.valid || lyricHeight <= 0) return null
        val lyricTop = geometry.lyricTop + topInset.coerceAtLeast(0)
        val lyricBottom = lyricTop + lyricHeight + bottomInset.coerceAtLeast(0)
        // The target is an absolute player-local bottom. Never add a player
        // height to an already absolute top coordinate.
        return max(geometry.contentBottom, lyricBottom)
    }

    fun targetCardHeight(
        geometry: MediaCardGeometrySnapshot,
        lyricHeight: Int,
        topInset: Int = 0,
        bottomInset: Int = geometry.safeBottomInset,
    ): Int? = requiredBottom(geometry, lyricHeight, topInset, bottomInset)
        ?.coerceAtLeast(geometry.playerHeight)
        ?.takeIf { it > 0 }

    fun localBounds(view: View, ancestor: View): LocalViewBounds? {
        var current: View? = view
        var left = 0
        var top = 0
        while (current != null && current !== ancestor) {
            left += current.left
            top += current.top
            current = current.parent as? View
        }
        if (current !== ancestor) return null
        val width = view.width.takeIf { it > 0 } ?: view.measuredWidth
        val height = view.height.takeIf { it > 0 } ?: view.measuredHeight
        return LocalViewBounds(left, top, left + width, top + height)
    }

    private fun measuredHeight(view: View): Int = view.height.takeIf { it > 0 }
        ?: view.measuredHeight.takeIf { it > 0 }
        ?: view.layoutParams?.height?.takeIf { it > 0 }
        ?: 0
}
