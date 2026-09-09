/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("PrivateApi")

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.animation.DecelerateInterpolator
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.ClassicAodSongInfoConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.CjkLyricWhitespacePolicy
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.lyric.view.SongPreprocessor
import com.juren233.hyperlyricsenhanced.root.ClassicAodFocusNotificationRecovery
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.utils.DisplayDiagnosticLogger
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.MediaCardDiagnosticLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

internal fun NotificationMediaAodLyricHooker.updateLockScreenCardHeight(
    overlay: LyricOverlay,
    forceRemeasure: Boolean = false
) {
    if (!overlay.lifetime.allowsUpdates || !overlay.root.isShown) return
    if (updateLockScreenHorizontalMargins(overlay)) {
        overlay.root.post {
            updateLockScreenCardHeight(overlay, forceRemeasure = true)
        }
        return
    }
    if (forceRemeasure) {
        val measuredWidth = overlay.root.width.takeIf { it > 0 }
            ?: overlay.root.measuredWidth
        if (measuredWidth > 0) {
            overlay.root.measure(
                View.MeasureSpec.makeMeasureSpec(
                    measuredWidth,
                    View.MeasureSpec.EXACTLY
                ),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        }
    }
    if (overlay.root.measuredHeight <= 0) return
    val anchorBottom = AodMediaLyricPolicy.contentAnchorBottom(
        albumBottom = overlay.album.bottom,
        artistBottom = overlay.artist.bottom
    )
    if (anchorBottom <= 0) return
    if (overlay.playerSize.baseHeight <= 0) {
        overlay.playerSize.baseHeight = overlay.player.height.takeIf { it > 0 }
            ?: overlay.player.measuredHeight
    }
    if (overlay.playerSize.baseHeight <= 0) return
    if (overlay.backgroundSize.baseHeight <= 0) {
        overlay.backgroundSize.baseHeight = overlay.backgroundSize.view.height.takeIf { it > 0 }
            ?: overlay.backgroundSize.view.measuredHeight
    }
    if (overlay.backgroundSize.baseHeight <= 0) return

    val density = overlay.root.resources.displayMetrics.density
    val topGap = (LOCK_SCREEN_AOD_TOP_GAP_DP * density).toInt()
    val bottomGap = (LOCK_SCREEN_AOD_BOTTOM_GAP_DP * density).toInt()
    val nativeCardHeight = AodMediaLyricPolicy.lockScreenNativeCardHeight(
        fullAod = overlay.fullAodActive,
        fullAodBaseHeight = overlay.backgroundSize.baseHeight,
        playerBaseHeight = overlay.playerSize.baseHeight,
    )
    if (overlay.fullAodActive) {
        overlay.backgroundConstraints?.pinToParentTop(overlay.player)
    } else {
        overlay.backgroundConstraints?.restore()
    }
    val lyricTop = AodMediaLyricPolicy.lockScreenLyricTop(
        anchorBottom = anchorBottom,
        topGap = topGap
    )
    val params = overlay.root.layoutParams as? ViewGroup.MarginLayoutParams
    val targetTopMargin = lyricTop - overlay.album.bottom
    if (params != null && params.topMargin != targetTopMargin) {
        params.topMargin = targetTopMargin
        overlay.root.layoutParams = params
    }
    val lyricBottom = lyricTop + overlay.root.measuredHeight
    val targetHeight = AodMediaLyricPolicy.lockScreenTargetCardHeight(
        nativeCardHeight = nativeCardHeight,
        lyricBottom = lyricBottom,
        bottomPadding = bottomGap,
    )
    val backgroundTargetHeight = AodMediaLyricPolicy.lockScreenBackgroundTargetHeight(
        targetCardHeight = targetHeight,
    )

    if (overlay.appliedCardHeight != targetHeight) {
        animateLockScreenCardHeight(
            overlay = overlay,
            targetHeight = targetHeight,
            backgroundTargetHeight = backgroundTargetHeight,
        )
        overlay.appliedCardHeight = targetHeight
        HookLogger.i(
            NotificationMediaAodLyricHooker.TAG,
            "锁屏 AOD 媒体卡片高度动画开始: " +
                "lyricTop=$lyricTop, lyricBottom=$lyricBottom, " +
                "albumBottom=${overlay.album.bottom}, artistBottom=${overlay.artist.bottom}, " +
                "topGap=$topGap, bottomGap=$bottomGap, " +
                "nativeBackgroundHeight=$nativeCardHeight, " +
                "targetHeight=$targetHeight, " +
                "backgroundTargetHeight=$backgroundTargetHeight"
        )
    }
    val appliedHeight = overlay.appliedCardHeight
    if (
        overlay.heightAnimator?.isRunning != true &&
        (
            AodMediaLyricPolicy.lockScreenHeightNeedsReassert(
                if (overlay.backgroundConstraints?.isPinned == true) {
                    backgroundTargetHeight
                } else {
                    appliedHeight
                },
                overlay.backgroundSize.view.layoutParams?.height,
            ) ||
                AodMediaLyricPolicy.lockScreenHeightNeedsReassert(
                    appliedHeight,
                    overlay.player.layoutParams?.height,
                )
        )
    ) {
        appliedHeight?.let { height ->
            animateLockScreenCardHeight(
                overlay = overlay,
                targetHeight = height,
                backgroundTargetHeight = AodMediaLyricPolicy
                    .lockScreenBackgroundTargetHeight(height),
            )
        }
    }
    if (BuildConfig.DEBUG) {
        val background = overlay.backgroundSize.view
        val driftKey = buildString {
            append("applied=${overlay.appliedCardHeight}, target=$targetHeight")
            append(", bgTarget=$backgroundTargetHeight")
            append(", bg=${background.height}/${background.measuredHeight}/${background.layoutParams?.height}")
            append(", bgTop=${background.top}, bgBottom=${background.bottom}")
            append(", bgTransY=${background.translationY}")
            append(", ").append(overlay.backgroundConstraints?.snapshot().orEmpty())
            append(", player=${overlay.player.height}/${overlay.player.measuredHeight}/${overlay.player.layoutParams?.height}")
            append(", ").append(overlay.headerHeightController?.actualHeightSnapshot().orEmpty())
        }
        if (driftKey != overlay.lastHeightDriftKey) {
            overlay.lastHeightDriftKey = driftKey
            HookLogger.i(NotificationMediaAodLyricHooker.TAG, "AOD_HEIGHT_VERIFY $driftKey shown=${overlay.root.isShown}")
        }
    }
}

internal fun NotificationMediaAodLyricHooker.updateLockScreenHorizontalMargins(overlay: LyricOverlay): Boolean {
    val playerWidth = overlay.player.width.takeIf { it > 0 }
        ?: overlay.player.measuredWidth
    val backgroundWidth = overlay.backgroundSize.view.width.takeIf { it > 0 }
        ?: overlay.backgroundSize.view.measuredWidth
    val albumWidth = overlay.album.width.takeIf { it > 0 }
        ?: overlay.album.measuredWidth
    if (playerWidth <= 0 || backgroundWidth <= 0 || albumWidth <= 0) return false

    val playerLocation = IntArray(2)
    val backgroundLocation = IntArray(2)
    val albumLocation = IntArray(2)
    overlay.player.getLocationOnScreen(playerLocation)
    overlay.backgroundSize.view.getLocationOnScreen(backgroundLocation)
    overlay.album.getLocationOnScreen(albumLocation)

    val cardLeft = backgroundLocation[0] - playerLocation[0]
    val cardRight = cardLeft + backgroundWidth
    val albumLeft = albumLocation[0] - playerLocation[0]
    val margins = AodMediaLyricPolicy.lockScreenHorizontalMargins(
        playerWidth = playerWidth,
        cardLeft = cardLeft,
        cardRight = cardRight,
        albumLeft = albumLeft,
        extraInset = (
            LOCK_SCREEN_AOD_SIDE_MARGIN_EXTRA_DP *
                overlay.root.resources.displayMetrics.density
            ).toInt(),
    )
    val params = overlay.root.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
    if (
        params.leftMargin == margins.left &&
        params.rightMargin == margins.right &&
        params.marginStart == margins.left &&
        params.marginEnd == margins.right
    ) {
        return false
    }

    params.leftMargin = margins.left
    params.rightMargin = margins.right
    params.marginStart = margins.left
    params.marginEnd = margins.right
    overlay.root.layoutParams = params
    HookLogger.i(
        NotificationMediaAodLyricHooker.TAG,
        "锁屏 AOD 歌词左右边距已按封面位置调整: " +
            "cardLeft=$cardLeft, albumLeft=$albumLeft, " +
            "leftMargin=${margins.left}, rightMargin=${margins.right}",
    )
    return true
}

internal fun NotificationMediaAodLyricHooker.restorePlayerHeight(overlay: LyricOverlay, fullAod: Boolean = false) {
    if (
        !AodMediaLyricPolicy.lockScreenHeightNeedsRestore(
            appliedHeight = overlay.appliedCardHeight,
            heightAnimationActive = overlay.heightAnimator != null,
        ) && overlay.backgroundConstraints?.isPinned != true
    ) {
        return
    }
    val animator = overlay.heightAnimator
    overlay.heightAnimator = null
    animator?.cancel()
    overlay.backgroundConstraints?.restore()
    restoreViewSize(overlay.playerSize)
    resizeViewToHeight(
        overlay.backgroundSize.view,
        if (fullAod) {
            overlay.backgroundSize.baseHeight
        } else {
            overlay.playerSize.baseHeight
        }
    )
    overlay.headerHeightController?.restoreHeight()
    overlay.appliedCardHeight = null
    overlay.backgroundSize.view.requestLayout()
    overlay.player.requestLayout()
    (overlay.player.parent as? View)?.requestLayout()
    if (BuildConfig.DEBUG) {
        HookLogger.i(
            NotificationMediaAodLyricHooker.TAG,
            "AOD_HEIGHT_RESTORE fullAod=$fullAod, " +
                "bgH=${overlay.backgroundSize.view.height}, " +
                "playerH=${overlay.player.height}, " +
                overlay.headerHeightController?.actualHeightSnapshot().orEmpty()
        )
    }
}

internal fun NotificationMediaAodLyricHooker.resizeViewToHeight(view: View, targetHeight: Int) {
    val params = view.layoutParams ?: return
    if (params.height != targetHeight) {
        params.height = targetHeight
        view.layoutParams = params
    }
}

internal fun NotificationMediaAodLyricHooker.animateLockScreenCardHeight(
    overlay: LyricOverlay,
    targetHeight: Int,
    backgroundTargetHeight: Int = targetHeight,
) {
    val previousAnimator = overlay.heightAnimator
    overlay.heightAnimator = null
    previousAnimator?.cancel()
    val background = overlay.backgroundSize.view
    val player = overlay.player
    val headerController = overlay.headerHeightController

    val startBackgroundHeight = background.height.takeIf { it > 0 }
        ?: (background.layoutParams?.height)?.takeIf { it >= 0 }
        ?: overlay.backgroundSize.baseHeight
    val startPlayerHeight = player.height.takeIf { it > 0 }
        ?: (player.layoutParams?.height)?.takeIf { it >= 0 }
        ?: overlay.playerSize.baseHeight
    val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = LOCK_SCREEN_AOD_HEIGHT_ANIMATION_MS
        interpolator = DecelerateInterpolator()
        addUpdateListener { animation ->
            val fraction = animation.animatedValue as Float
            resizeViewToHeight(
                background,
                lerp(startBackgroundHeight, backgroundTargetHeight, fraction),
            )
            resizeViewToHeight(
                player,
                lerp(startPlayerHeight, targetHeight, fraction),
            )
            background.requestLayout()
            player.requestLayout()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (overlay.heightAnimator !== animation) return
                overlay.heightAnimator = null
                resizeViewToHeight(background, backgroundTargetHeight)
                resizeViewToHeight(player, targetHeight)
                headerController?.applyFinalHeight(targetHeight)
                background.requestLayout()
                player.requestLayout()
                (player.parent as? View)?.requestLayout()
                HookLogger.i(
                    NotificationMediaAodLyricHooker.TAG,
                    "锁屏 AOD 媒体卡片高度动画完成: target=$targetHeight, " +
                        "backgroundTarget=$backgroundTargetHeight",
                )
            }
        })
    }
    overlay.heightAnimator = animator
    animator.start()
}

internal fun NotificationMediaAodLyricHooker.lerp(start: Int, end: Int, fraction: Float): Int =
    (start + (end - start) * fraction).roundToInt()

internal fun NotificationMediaAodLyricHooker.restoreViewSize(size: ViewSizeSnapshot) {
    val params = size.view.layoutParams
    if (params.height != size.originalLayoutHeight) {
        params.height = size.originalLayoutHeight
        size.view.layoutParams = params
    }
    if (size.view.minimumHeight != size.originalMinimumHeight) {
        size.view.minimumHeight = size.originalMinimumHeight
    }
}

internal fun NotificationMediaAodLyricHooker.captureViewSize(view: View): ViewSizeSnapshot {
    val layoutHeight = view.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
    return ViewSizeSnapshot(
        view = view,
        originalLayoutHeight = layoutHeight,
        originalMinimumHeight = view.minimumHeight,
        baseHeight = layoutHeight.takeIf { it >= 0 }
            ?: view.height.takeIf { it > 0 }
            ?: view.measuredHeight.takeIf { it > 0 }
            ?: layoutHeight.coerceAtLeast(0)
    )
}
