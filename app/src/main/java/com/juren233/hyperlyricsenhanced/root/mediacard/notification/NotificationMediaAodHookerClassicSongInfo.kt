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

internal fun NotificationMediaAodLyricHooker.setOptionalText(view: TextView, text: String) {
    view.text = text
    view.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
}

internal data class ClassicAodEmbeddedSongInfo(
    val text: String,
    val sourcePackage: String?,
    val textSize: Int = RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_TEXT_SIZE,
    val showIcon: Boolean = RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_SHOW_ICON,
)

internal fun NotificationMediaAodLyricHooker.currentClassicAodEmbeddedSongInfo(): ClassicAodEmbeddedSongInfo {
    val currentPrefs = prefs ?: return ClassicAodEmbeddedSongInfo("", null)
    if (
        ClassicAodSongInfoConfig.displayStyle(currentPrefs) !=
            RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_TEXT_EMBEDDED
    ) {
        return ClassicAodEmbeddedSongInfo("", null)
    }
    val song = LyriconDataBridge.currentSong
    val text = ClassicAodSongInfoConfig.formatSongInfo(
        title = song?.name.orEmpty(),
        artist = song?.artist.orEmpty(),
        format = ClassicAodSongInfoConfig.format(currentPrefs)
    )
    return ClassicAodEmbeddedSongInfo(
        text = text,
        sourcePackage = LyriconDataBridge.currentLyricPackageName
            ?: LyriconDataBridge.activePackageName,
        textSize = ClassicAodSongInfoConfig.embeddedTextSize(currentPrefs),
        showIcon = ClassicAodSongInfoConfig.showsEmbeddedIcon(currentPrefs),
    )
}

internal fun NotificationMediaAodLyricHooker.updateClassicEmbeddedSongInfo(
    overlay: AodPluginOverlay,
    songInfo: ClassicAodEmbeddedSongInfo,
) {
    if (songInfo.text.isBlank()) {
        overlay.songInfoRow.visibility = View.GONE
        overlay.songInfo.text = ""
        overlay.sourceIcon.setImageDrawable(null)
        overlay.appliedSongInfoPackage = null
        overlay.appliedSongInfoTextSize = songInfo.textSize
        overlay.appliedSongInfoShowsIcon = songInfo.showIcon
        return
    }

    overlay.songInfo.text = songInfo.text
    overlay.songInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, songInfo.textSize.toFloat())
    updateClassicSongInfoIconLayout(overlay, songInfo.textSize)
    if (overlay.appliedSongInfoPackage != songInfo.sourcePackage) {
        overlay.appliedSongInfoPackage = songInfo.sourcePackage
        val icon = songInfo.sourcePackage?.let { packageName ->
            runCatching {
                overlay.root.context.packageManager.getApplicationIcon(packageName)
            }.getOrNull()
        }
        overlay.sourceIcon.setImageDrawable(icon)
    }
    overlay.sourceIcon.visibility = if (
        songInfo.showIcon && overlay.sourceIcon.drawable != null
    ) {
        View.VISIBLE
    } else {
        View.GONE
    }
    overlay.appliedSongInfoTextSize = songInfo.textSize
    overlay.appliedSongInfoShowsIcon = songInfo.showIcon
    overlay.songInfoRow.visibility = View.VISIBLE
}

internal fun NotificationMediaAodLyricHooker.updateClassicSongInfoIconLayout(
    overlay: AodPluginOverlay,
    textSize: Int,
) {
    val params = overlay.sourceIcon.layoutParams as? LinearLayout.LayoutParams ?: return
    val scale = textSize.toFloat() /
        RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_TEXT_SIZE
    val density = overlay.root.resources.displayMetrics.density
    val iconSize = (AOD_PLUGIN_SONG_INFO_ICON_DP * density * scale)
        .roundToInt()
        .coerceAtLeast(1)
    val iconGap = (AOD_PLUGIN_SONG_INFO_ICON_GAP_DP * density * scale)
        .roundToInt()
        .coerceAtLeast(0)
    if (
        params.width != iconSize ||
        params.height != iconSize ||
        params.marginEnd != iconGap
    ) {
        params.width = iconSize
        params.height = iconSize
        params.marginEnd = iconGap
        overlay.sourceIcon.layoutParams = params
    }
}

internal fun NotificationMediaAodLyricHooker.updateClassicSongInfoMaxWidth(
    overlay: AodPluginOverlay,
    overlayWidth: Int,
) {
    if (overlay.songInfoRow.visibility != View.VISIBLE) return
    val rowWidth = overlayWidth -
        overlay.root.paddingLeft -
        overlay.root.paddingRight
    val iconWidth = if (overlay.sourceIcon.visibility == View.VISIBLE) {
        val iconParams = overlay.sourceIcon.layoutParams as? LinearLayout.LayoutParams
        (iconParams?.width ?: 0) + (iconParams?.marginEnd ?: 0)
    } else {
        0
    }
    val maxTextWidth = (rowWidth - iconWidth).coerceAtLeast(0)
    if (overlay.songInfo.maxWidth != maxTextWidth) {
        overlay.songInfo.maxWidth = maxTextWidth
    }
}
