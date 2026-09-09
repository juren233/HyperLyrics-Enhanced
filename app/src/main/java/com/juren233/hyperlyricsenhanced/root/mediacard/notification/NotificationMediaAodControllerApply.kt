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

@SuppressLint("UseKtx")
internal fun NotificationMediaAodLyricHooker.applyState(controller: Any, state: ControllerState) {
    val diagnosticKey = "AOD_LOCK/${System.identityHashCode(controller)}"
    val api = NotificationMediaAodLyricHooker.resolveApi(controller.javaClass.classLoader) ?: run {
        DisplayDiagnosticLogger.log(
            "AOD_LOCK",
            "skipped",
            "hook_unavailable",
            dedupeKey = diagnosticKey,
        )
        return
    }
    val holder = state.holder ?: api.getHolder(controller) ?: run {
        DisplayDiagnosticLogger.log(
            "AOD_LOCK",
            "skipped",
            "holder_unavailable",
            dedupeKey = diagnosticKey,
        )
        return
    }
    state.holder = holder
    state.playing = NotificationMediaAodLyricHooker.resolvePlaying(
        api,
        controller,
        state.mediaData ?: api.getMediaData(controller)
    )
    synchronizeLyricPosition(api, controller)
    val player = api.getPlayer(holder)
    val interactive = player.context.getSystemService(PowerManager::class.java).isInteractive
    state.aodActive = AodMediaLyricPolicy.isLockScreenAodActive(
        fullAod = state.fullAod,
        interactive = interactive,
        playerShown = player.isShown
    )
    val textStyle = lockScreenAodTextStyle()
    val lyricPackage = LyriconDataBridge.currentLyricPackageName
    val mediaPackage = api.packageName(state.mediaData ?: api.getMediaData(controller))
    val content = appendNextSongPreview(
        content = currentContent(textStyle),
        style = textStyle,
        context = player.context,
        packageName = mediaPackage,
    )
    val packageMatches = lyricPackage.isNullOrBlank() ||
        mediaPackage.isNullOrBlank() || lyricPackage == mediaPackage
    val hasLyric = content.main.isNotBlank() || content.next.isNotBlank()
    val enabled = NotificationMediaAodLyricHooker.isEnabled()
    val show = AodMediaLyricPolicy.shouldShow(
        enabled = enabled,
        fullAod = state.fullAod,
        playing = state.playing,
        hasLyric = hasLyric,
        packageMatches = packageMatches,
        pauseStyle = textStyle.pauseStyle,
    )
    val decisionReason = if (show) {
        "policy_passed"
    } else {
        when {
            !enabled -> "feature_disabled"
            !state.fullAod && !interactive -> "waiting_full_aod"
            !state.aodActive && interactive -> "screen_interactive"
            !state.aodActive -> "player_hidden"
            !state.playing &&
                textStyle.pauseStyle != RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS ->
                "pause_policy"
            !hasLyric -> "no_lyrics"
            !packageMatches -> "package_mismatch"
            else -> "policy_rejected"
        }
    }
    AodEnvironmentDiagnostics.log(
        context = player.context,
        stage = "lockscreen_decision",
        modulePrefs = prefs,
        view = player,
        runtime = AodRuntimeDiagnosticState(
            surface = "lockscreen_media",
            fullAod = state.fullAod,
            playerShown = player.isShown,
            playing = state.playing,
            pauseStyle = textStyle.pauseStyle,
            pauseAllowed = state.playing ||
                textStyle.pauseStyle == RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS,
            hasContent = hasLyric,
            packageMatches = packageMatches,
            showDecision = show,
            decisionReason = decisionReason,
            overlayPresent = state.overlay != null,
            overlayShown = state.overlay?.root?.isShown,
        ),
        dedupeKey = diagnosticKey,
    )
    updatePositionPolling()

    if (!show) {
        val reason = decisionReason
        DisplayDiagnosticLogger.log(
            channel = "AOD_LOCK",
            result = "hidden",
            reason = reason,
            extra = "interactive=$interactive, playerShown=${player.isShown}, " +
                "fullAod=${state.fullAod}, aodActive=${state.aodActive}, " +
                "mediaPackage=${mediaPackage.orEmpty()}, overlay=${state.overlay != null}",
            dedupeKey = diagnosticKey,
        )
        restoreActions(state)
        state.overlay?.let { overlay ->
            overlay.root.visibility = View.GONE
            restorePlayerHeight(overlay, state.fullAod)
        }
        return
    }

    val actions = api.getActions(holder)
    if (state.actionVisibilities.isEmpty()) {
        actions.forEach { state.actionVisibilities[it] = it.visibility }
    }
    actions.forEach { action ->
        if (action.visibility == View.VISIBLE) action.visibility = View.INVISIBLE
    }

    val overlay = state.overlay ?: createOverlay(api, holder, actions).also {
        state.overlay = it
    }
    if (overlay == null) {
        DisplayDiagnosticLogger.log(
            channel = "AOD_LOCK",
            result = "skipped",
            reason = "overlay_unavailable",
            extra = "actions=${actions.size}",
            dedupeKey = diagnosticKey,
        )
        AodEnvironmentDiagnostics.log(
            context = player.context,
            stage = "lockscreen_overlay",
            modulePrefs = prefs,
            view = player,
            runtime = AodRuntimeDiagnosticState(
                surface = "lockscreen_media",
                fullAod = state.fullAod,
                playerShown = player.isShown,
                playing = state.playing,
                pauseStyle = textStyle.pauseStyle,
                hasContent = hasLyric,
                packageMatches = packageMatches,
                showDecision = false,
                decisionReason = "overlay_unavailable",
                overlayPresent = false,
                overlayShown = false,
            ),
            dedupeKey = diagnosticKey,
        )
        restoreActions(state)
        return
    }
    val contentChanged = overlay.main.text.toString() != content.main ||
        overlay.translation.text.toString() != content.translation ||
        overlay.backing.text.toString() != content.backing ||
        overlay.backingTranslation.text.toString() != content.backingTranslation ||
        overlay.overlappingMain.text.toString() != content.overlappingMain ||
        overlay.overlappingTranslation.text.toString() != content.overlappingTranslation ||
        overlay.overlappingBacking.text.toString() != content.overlappingBacking ||
        overlay.overlappingBackingTranslation.text.toString() !=
            content.overlappingBackingTranslation ||
        overlay.next.text.toString() != content.next
    val styleChanged = overlay.appliedTextStyle != textStyle
    val alignmentChanged = overlay.appliedMainAlignment != content.mainAlignment ||
        overlay.appliedBackingAlignment != content.backingAlignment ||
        overlay.appliedOverlappingAlignment != content.overlappingAlignment ||
        overlay.appliedOverlappingBackingAlignment != content.overlappingBackingAlignment ||
        overlay.appliedNextAlignment != content.nextAlignment
    if (contentChanged || styleChanged || alignmentChanged) {
        overlay.drawWakeLock.acquire(NotificationMediaAodLyricHooker.DRAW_WAKE_LOCK_TIMEOUT_MS)
    }
    overlay.main.text = content.main
    setOptionalText(overlay.translation, content.translation)
    setOptionalText(overlay.backing, content.backing)
    setOptionalText(overlay.backingTranslation, content.backingTranslation)
    setOptionalText(overlay.overlappingMain, content.overlappingMain)
    setOptionalText(overlay.overlappingTranslation, content.overlappingTranslation)
    setOptionalText(overlay.overlappingBacking, content.overlappingBacking)
    setOptionalText(
        overlay.overlappingBackingTranslation,
        content.overlappingBackingTranslation
    )
    setOptionalText(overlay.next, content.next)
    NotificationMediaAodLyricHooker.applyContentAlignment(overlay, content)
    NotificationMediaAodLyricHooker.applyLockScreenTextStyle(
        overlay = overlay,
        style = textStyle,
        mainTypefaceView = api.getTitleText(holder),
        translationTypefaceView = api.getArtistText(holder),
    )
    NotificationMediaAodLyricHooker.applyLyricRowOrder(overlay, textStyle.swapTranslation)
    NotificationMediaAodLyricHooker.updateLockScreenLineSpacing(overlay)
    overlay.main.setTextColor(api.getTitleText(holder).currentTextColor)
    overlay.backing.setTextColor(api.getTitleText(holder).currentTextColor)
    overlay.overlappingMain.setTextColor(api.getTitleText(holder).currentTextColor)
    overlay.overlappingBacking.setTextColor(api.getTitleText(holder).currentTextColor)
    val translationColor = api.getArtistText(holder).currentTextColor
    overlay.translation.setTextColor(translationColor)
    overlay.backingTranslation.setTextColor(translationColor)
    overlay.overlappingTranslation.setTextColor(translationColor)
    overlay.overlappingBackingTranslation.setTextColor(translationColor)
    overlay.next.setTextColor(
        if (textStyle.nextLyricStyle == RootConstants.AOD_NEXT_LYRIC_STYLE_BACKING) {
            api.getTitleText(holder).currentTextColor
        } else {
            translationColor
        }
    )
    overlay.fullAodActive = state.fullAod
    if (overlay.root.visibility == View.GONE) {
        overlay.root.visibility = View.INVISIBLE
    }
    overlay.root.post {
        if (overlay.root.visibility == View.GONE) return@post
        if (overlay.root.visibility == View.INVISIBLE) {
            overlay.root.visibility = View.VISIBLE
            overlay.root.bringToFront()
            HookLogger.i(TAG, "锁屏 AOD 歌词已在原生控件隐藏完成后显示")
        }
        AodEnvironmentDiagnostics.log(
            context = player.context,
            stage = "lockscreen_overlay_visible",
            modulePrefs = prefs,
            view = overlay.root,
            runtime = AodRuntimeDiagnosticState(
                surface = "lockscreen_media",
                fullAod = state.fullAod,
                playerShown = player.isShown,
                playing = state.playing,
                pauseStyle = textStyle.pauseStyle,
                pauseAllowed = true,
                hasContent = hasLyric,
                packageMatches = packageMatches,
                showDecision = true,
                decisionReason = "overlay_visible",
                overlayPresent = true,
                overlayShown = overlay.root.isShown,
            ),
            dedupeKey = diagnosticKey,
        )
        updateLockScreenCardHeight(
            overlay,
            forceRemeasure = contentChanged || styleChanged,
        )
        DisplayDiagnosticLogger.log(
            channel = "AOD_LOCK",
            result = "shown",
            reason = "overlay_visible",
            extra = "interactive=$interactive, playerShown=${player.isShown}, " +
                "fullAod=${state.fullAod}, mediaPackage=${mediaPackage.orEmpty()}, " +
                "contentChanged=$contentChanged, styleChanged=$styleChanged",
            dedupeKey = diagnosticKey,
        )
    }
    if (contentChanged || styleChanged) {
        overlay.root.invalidate()
        (overlay.root.parent as? View)?.invalidate()
        NotificationMediaAodLyricHooker.requestAodFrameRefresh(controller.javaClass.classLoader)
    }
}

