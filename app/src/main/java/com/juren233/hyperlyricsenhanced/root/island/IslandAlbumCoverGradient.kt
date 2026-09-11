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

internal fun IslandAlbumCoverStyleHooker.applyGradientCover(
    accessor: IslandAlbumCoverStyleHooker.CoverAccessor,
    holder: Any,
    fixIcon: ImageView,
    fakeContentView: ViewGroup?,
    packageName: String?,
) {
    val iconContainer = accessor.iconContainerField.get(holder) as? View ?: return
    val existingState = IslandAlbumCoverStyleHooker.gradientStates[fixIcon]
    if (existingState == null || existingState.iconContainer !== iconContainer) {
        IslandAlbumCoverStyleHooker.gradientStates.remove(fixIcon)?.let { IslandAlbumCoverStyleHooker.restoreGradientState(it) }
        IslandAlbumCoverStyleHooker.gradientStates[fixIcon] = GradientCoverState.capture(fixIcon, iconContainer, packageName)
    } else {
        existingState.packageName = packageName
    }
    val state = IslandAlbumCoverStyleHooker.gradientStates[fixIcon] ?: return
    state.applyLeftContentTextShadow()
    if (fakeContentView != null) {
        // Style the freshly rebound fake holder synchronously. setFixIcon returns on the
        // UI thread before the next frame, so this closes the default-style exposure window.
        state.removePreDrawObserver()
        IslandAlbumCoverStyleHooker.applyFakeTransitionCover(
            fakeView = fakeContentView,
            source = "after fake holder bind",
        )
        return
    }

    IslandAlbumCoverStyleHooker.ensureArtworkContinuity(fixIcon, "real")
    val smallIsland = state.isSmallIslandState()
    val embeddedHost = if (smallIsland) {
        (IslandAlbumCoverStyleHooker.callViewGetter(holder, "getSmallContainer") as? ViewGroup)
            ?: (state.module as? android.widget.FrameLayout)
                ?.takeIf { IslandAlbumCoverStyleHooker.isSmallIslandModule(it) }
    } else {
        IslandAlbumCoverStyleHooker.callViewGetter(holder, "getBigContainer") as? ViewGroup
    }
    if (embeddedHost != null &&
        EmbeddedIslandAlbumCoverController.apply(embeddedHost, fixIcon, smallIsland)
    ) {
        // The embedded child now owns rendering. Restore the native ImageView's visual
        // properties without changing its parent or measurement contract.
        state.removePreDrawObserver()
        state.restoreCoverVisuals()
        return
    }

    if (smallIsland) {
        // A cold small-island holder can expose its stable 104x104 module before
        // getSmallContainer(). If neither embedded host works, keep native visuals; the old
        // scale/translation fallback is the confirmed cause of the one-frame flash.
        state.removePreDrawObserver()
        state.restoreCoverVisuals()
        return
    }

    val localSnapshot = IslandAlbumCoverStyleHooker.resolveLocalBigSnapshot(fixIcon, fixIcon.rootView)
    val snapshot = localSnapshot ?: IslandAlbumCoverStyleHooker.cachedBigVisual
    if (snapshot != null && !snapshot.smallIsland) {
        state.applySnapshot(snapshot)
        state.removePreDrawObserver()
    } else {
        // Fallback for a host revision whose content containers are not discoverable yet.
        state.scheduleLayout()
    }
}

internal fun IslandAlbumCoverStyleHooker.restoreGradientCover(fixIcon: ImageView) {
    val state = IslandAlbumCoverStyleHooker.gradientStates.remove(fixIcon) ?: return
    IslandAlbumCoverStyleHooker.restoreGradientState(state)
}

internal fun IslandAlbumCoverStyleHooker.restoreAllGradientCovers() {
    IslandAlbumCoverStyleHooker.gradientStates.values.toList().forEach { IslandAlbumCoverStyleHooker.restoreGradientState(it) }
    IslandAlbumCoverStyleHooker.gradientStates.clear()
}

internal fun IslandAlbumCoverStyleHooker.restoreGradientState(state: GradientCoverState) {
    state.removePreDrawObserver()
    state.restoreLeftContentTextShadow()
    EmbeddedIslandAlbumCoverController.restoreForSource(state.fixIcon)
    state.iconContainer.translationX = state.originalIconContainerTranslationX
    state.iconContainer.translationY = state.originalIconContainerTranslationY

    val fixLp = state.fixIcon.layoutParams
    if (fixLp != null) {
        fixLp.width = state.originalFixIconWidth
        fixLp.height = state.originalFixIconHeight
        if (fixLp is ViewGroup.MarginLayoutParams) {
            fixLp.marginStart = state.originalFixIconMarginStart
            fixLp.marginEnd = state.originalFixIconMarginEnd
            fixLp.topMargin = state.originalFixIconMarginTop
            fixLp.bottomMargin = state.originalFixIconMarginBottom
        }
        state.fixIcon.layoutParams = fixLp
    }
    state.restoreCoverVisuals()
    state.fixIcon.visibility = state.originalFixIconVisibility

    state.iconContainer.setPadding(
        state.originalIconContainerPaddingLeft,
        state.originalIconContainerPaddingTop,
        state.originalIconContainerPaddingRight,
        state.originalIconContainerPaddingBottom
    )
    state.module?.setPadding(
        state.originalModulePaddingLeft,
        state.originalModulePaddingTop,
        state.originalModulePaddingRight,
        state.originalModulePaddingBottom
    )
    (state.iconContainer as? ViewGroup)?.clipChildren = state.originalIconContainerClipChildren
    (state.module as? ViewGroup)?.clipChildren = state.originalModuleClipChildren
    (state.module as? ViewGroup)?.clipToPadding = state.originalModuleClipToPadding
    (state.moduleParent as? ViewGroup)?.clipChildren = state.originalModuleParentClipChildren
    (state.moduleParent as? ViewGroup)?.clipToPadding = state.originalModuleParentClipToPadding

    val iconLp = state.iconContainer.layoutParams
    if (iconLp != null) {
        iconLp.width = state.originalIconContainerWidth
        iconLp.height = state.originalIconContainerHeight
        state.originalIconContainerGravity?.let { IslandAlbumCoverStyleHooker.setGravity(iconLp, it) }
        if (iconLp is ViewGroup.MarginLayoutParams) {
            iconLp.marginStart = state.originalIconContainerMarginStart
            iconLp.marginEnd = state.originalIconContainerMarginEnd
            iconLp.topMargin = state.originalIconContainerMarginTop
            iconLp.bottomMargin = state.originalIconContainerMarginBottom
        }
        state.iconContainer.layoutParams = iconLp
    }

}

internal fun GradientCoverState.applyLayout(
    geometry: IslandGradientGeometryCandidate,
    logFinalPlacement: Boolean,
    smallIslandOverride: Boolean? = null,
) {
    val density = fixIcon.resources.displayMetrics.density
    val moduleView = module ?: ((fixIcon.parent as? View)?.parent as? View)
        ?: return
    val moduleWidth = moduleView.width.takeIf { it > 0 } ?: moduleView.measuredWidth
    val moduleHeight = moduleView.height.takeIf { it > 0 } ?: moduleView.measuredHeight
    val iconWidth = fixIcon.expectedLayoutWidth()
    val iconHeight = fixIcon.expectedLayoutHeight()
    val iconLocation = fixIcon.baseLocationInWindow()
    val smallIsland = smallIslandOverride ?: IslandAlbumCoverStyleHooker.isSmallIslandModule(moduleView)
    val islandWindowX = geometry.left.toFloat()
    val placement = IslandGradientCoverLayout.resolve(
        moduleWidth = moduleWidth,
        moduleHeight = moduleHeight,
        moduleWindowY = geometry.top.toFloat(),
        islandWindowX = islandWindowX,
        iconWindowX = iconLocation.first,
        iconWindowY = iconLocation.second,
        iconWidth = iconWidth,
        iconHeight = iconHeight,
        isSmallIsland = smallIsland,
        density = density,
    ) ?: return

    (moduleView as? ViewGroup)?.clipChildren = false
    (moduleView as? ViewGroup)?.clipToPadding = false
    (moduleView.parent as? ViewGroup)?.clipChildren = false
    (moduleView.parent as? ViewGroup)?.clipToPadding = false
    (iconContainer as? ViewGroup)?.clipChildren = false

    if (smallIsland) {
        val sameSmallVisualShape = (appliedSmall == true) &&
            (appliedCoverWidth == placement.coverWidth) &&
            (appliedCoverHeight == placement.coverHeight) &&
            (fixIcon.scaleX == placement.iconScaleX) &&
            (fixIcon.scaleY == placement.iconScaleY) &&
            (fixIcon.scaleType == ImageView.ScaleType.CENTER_CROP) &&
            (fixIcon.outlineProvider === IslandAlbumCoverStyleHooker.circleOutlineProvider) &&
            fixIcon.clipToOutline &&
            fixIcon.foreground == null
        if (sameSmallVisualShape) {
            if (fixIcon.translationX != placement.iconTranslationX) {
                fixIcon.translationX = placement.iconTranslationX
            }
            if (fixIcon.translationY != placement.iconTranslationY) {
                fixIcon.translationY = placement.iconTranslationY
            }
            if (logFinalPlacement) {
                cacheCurrentVisual(smallIsland, placement)
                logPlacement(moduleView, islandWindowX, smallIsland, placement)
            }
            return
        }

        val alreadyMatchesSmall = (appliedSmall == true) &&
            (appliedCoverWidth == placement.coverWidth) &&
            (appliedCoverHeight == placement.coverHeight) &&
            (fixIcon.scaleX == placement.iconScaleX) &&
            (fixIcon.scaleY == placement.iconScaleY) &&
            (fixIcon.translationX == placement.iconTranslationX) &&
            (fixIcon.translationY == placement.iconTranslationY) &&
            (fixIcon.scaleType == ImageView.ScaleType.CENTER_CROP) &&
            fixIcon.clipToOutline

        if (alreadyMatchesSmall) {
            if (logFinalPlacement) {
                cacheCurrentVisual(smallIsland, placement)
                logPlacement(moduleView, islandWindowX, smallIsland, placement)
            }
            return
        }

        fixIcon.pivotX = 0f
        fixIcon.pivotY = 0f
        fixIcon.scaleX = placement.iconScaleX
        fixIcon.scaleY = placement.iconScaleY
        fixIcon.translationX = placement.iconTranslationX
        fixIcon.translationY = placement.iconTranslationY
        fixIcon.scaleType = ImageView.ScaleType.CENTER_CROP
        fixIcon.foreground = null
        fixIcon.outlineProvider = IslandAlbumCoverStyleHooker.circleOutlineProvider
        fixIcon.clipToOutline = true
        fixIcon.invalidateOutline()

        appliedSmall = true
        appliedCoverWidth = placement.coverWidth
        appliedCoverHeight = placement.coverHeight

        if (logFinalPlacement) {
            cacheCurrentVisual(smallIsland, placement)
            logPlacement(moduleView, islandWindowX, smallIsland, placement)
        }
        return
    }

    val sameBigVisualShape = (appliedSmall == false) &&
        (appliedCoverWidth == placement.coverWidth) &&
        (appliedCoverHeight == placement.coverHeight) &&
        (fixIcon.scaleX == placement.iconScaleX) &&
        (fixIcon.scaleY == placement.iconScaleY) &&
        (fixIcon.scaleType == ImageView.ScaleType.CENTER_CROP) &&
        (fixIcon.outlineProvider === IslandAlbumCoverStyleHooker.leftRoundedCoverOutlineProvider) &&
        fixIcon.clipToOutline &&
        (fixIcon.foreground is RightEdgeGradientDrawable)
    if (sameBigVisualShape) {
        if (fixIcon.translationX != placement.iconTranslationX) {
            fixIcon.translationX = placement.iconTranslationX
        }
        if (fixIcon.translationY != placement.iconTranslationY) {
            fixIcon.translationY = placement.iconTranslationY
        }
        if (logFinalPlacement) {
            cacheCurrentVisual(smallIsland, placement)
            logPlacement(moduleView, islandWindowX, smallIsland, placement)
        }
        return
    }

    val alreadyMatchesBig = (appliedSmall == false) &&
        (appliedCoverWidth == placement.coverWidth) &&
        (appliedCoverHeight == placement.coverHeight) &&
        (fixIcon.scaleX == placement.iconScaleX) &&
        (fixIcon.scaleY == placement.iconScaleY) &&
        (fixIcon.translationX == placement.iconTranslationX) &&
        (fixIcon.translationY == placement.iconTranslationY) &&
        (fixIcon.scaleType == ImageView.ScaleType.CENTER_CROP) &&
        fixIcon.clipToOutline

    if (alreadyMatchesBig) {
        if (logFinalPlacement) {
            cacheCurrentVisual(smallIsland, placement)
            logPlacement(moduleView, islandWindowX, smallIsland, placement)
        }
        return
    }

    fixIcon.pivotX = 0f
    fixIcon.pivotY = 0f
    fixIcon.scaleX = placement.iconScaleX
    fixIcon.scaleY = placement.iconScaleY
    fixIcon.translationX = placement.iconTranslationX
    fixIcon.translationY = placement.iconTranslationY
    fixIcon.scaleType = ImageView.ScaleType.CENTER_CROP
    fixIcon.outlineProvider = IslandAlbumCoverStyleHooker.leftRoundedCoverOutlineProvider
    fixIcon.clipToOutline = true
    fixIcon.invalidateOutline()
    fixIcon.foreground = RightEdgeGradientDrawable(
        bandFraction = placement.gradientBandFraction,
        color = resolveIslandBackgroundColor(fixIcon),
    )

    appliedSmall = false
    appliedCoverWidth = placement.coverWidth
    appliedCoverHeight = placement.coverHeight

    if (logFinalPlacement) {
        cacheCurrentVisual(smallIsland, placement)
        logPlacement(moduleView, islandWindowX, smallIsland, placement)
    }
}

internal fun View.baseLocationInWindow(): Pair<Float, Float> {
    val location = IntArray(2)
    getLocationInWindow(location)
    return (location[0] - translationX) to (location[1] - translationY)
}

internal fun View.baseLocationRelativeTo(root: View): Pair<Float, Float>? {
    var x = 0f
    var y = 0f
    var current: View = this
    while (current !== root) {
        x += current.left
        y += current.top
        if (current !== this) {
            x += current.translationX
            y += current.translationY
        }
        current = current.parent as? View ?: return null
    }
    return x to y
}

internal fun View.baseLocationFor(root: View): Pair<Float, Float> {
    return baseLocationRelativeTo(root) ?: baseLocationInWindow()
}

internal fun ImageView.expectedLayoutWidth(): Int {
    return IslandGradientCoverLayout.resolveIconDimension(
        actualSize = width,
        measuredSize = measuredWidth,
        layoutParamSize = layoutParams?.width ?: 0,
        minimumSize = minimumWidth,
        density = resources.displayMetrics.density,
    )
}

internal fun ImageView.expectedLayoutHeight(): Int {
    return IslandGradientCoverLayout.resolveIconDimension(
        actualSize = height,
        measuredSize = measuredHeight,
        layoutParamSize = layoutParams?.height ?: 0,
        minimumSize = minimumHeight,
        density = resources.displayMetrics.density,
    )
}
