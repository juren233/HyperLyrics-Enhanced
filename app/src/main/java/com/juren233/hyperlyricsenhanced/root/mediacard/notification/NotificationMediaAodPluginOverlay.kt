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

internal fun NotificationMediaAodLyricHooker.refreshAodPluginStates() {
    synchronized(NotificationMediaAodLyricHooker.aodPluginStates) { NotificationMediaAodLyricHooker.aodPluginStates.entries.toList() }
        .forEach { (aodView, state) -> safeApplyAodPlugin(aodView, state) }
}

internal fun NotificationMediaAodLyricHooker.safeApplyAodPlugin(aodView: Any, state: AodPluginState) {
    MediaCardDiagnosticLogger.log(
        stage = "aod_classic",
        event = "apply_begin",
        details = "aodView=${MediaCardDiagnosticLogger.identity(aodView)},state=${MediaCardDiagnosticLogger.identity(state)},overlay=${MediaCardDiagnosticLogger.view(state.overlay?.root)},attached=${state.attached},playing=${state.playing}",
    )
    runCatching { applyAodPluginState(aodView, state) }
        .onSuccess {
            MediaCardDiagnosticLogger.log(
                stage = "aod_classic",
                event = "apply_complete",
                details = "aodView=${MediaCardDiagnosticLogger.identity(aodView)},overlay=${MediaCardDiagnosticLogger.view(state.overlay?.root)},attached=${state.attached},playing=${state.playing}",
            )
        }
        .onFailure {
            MediaCardDiagnosticLogger.log(
                stage = "aod_classic",
                event = "apply_failed",
                reason = "exception",
                details = "aodView=${MediaCardDiagnosticLogger.identity(aodView)},error=${MediaCardDiagnosticLogger.sanitize(it.message)}",
            )
            state.overlay?.root?.visibility = View.GONE
            HookLogger.e(TAG, "应用通知图标式息屏歌词失败", it)
        }
}

internal fun NotificationMediaAodLyricHooker.applyAodPluginState(aodView: Any, state: AodPluginState) {
    val diagnosticKey = "AOD_CLASSIC/${System.identityHashCode(aodView)}"
    val api = resolveAodPluginApi(aodView.javaClass.classLoader) ?: run {
        DisplayDiagnosticLogger.log(
            "AOD_CLASSIC",
            "skipped",
            "hook_unavailable",
            dedupeKey = diagnosticKey,
        )
        return
    }
    val view = aodView as? View ?: run {
        DisplayDiagnosticLogger.log(
            "AOD_CLASSIC",
            "skipped",
            "view_unavailable",
            dedupeKey = diagnosticKey,
        )
        return
    }
    state.attached = view.isAttachedToWindow
    state.playing = LyriconDataBridge.currentPlaybackState ?: state.playing
    synchronizeLyricPosition()
    val textStyle = classicAodTextStyle()
    val mediaPackage = LyriconDataBridge.currentLyricPackageName
        ?: LyriconDataBridge.activePackageName
    val content = appendNextSongPreview(
        content = currentContent(textStyle),
        style = textStyle,
        context = view.context,
        packageName = mediaPackage,
    )
    val songInfo = currentClassicAodEmbeddedSongInfo()
    val fullAodActive = synchronized(NotificationMediaAodLyricHooker.states) {
        NotificationMediaAodLyricHooker.states.values.any { controllerState ->
            controllerState.overlay?.root?.isShown == true &&
                controllerState.aodActive
        }
    }
    val enabled = NotificationMediaAodLyricHooker.isEnabled()
    val viewShown = view.isShown
    val aodShown = api.isAodShown(aodView)
    val pauseAllowed = state.playing ||
        textStyle.pauseStyle == RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS
    // Overlapping/background-vocal rows are valid lyric content even when
    // the user disables “显示下一句歌词”. Do not let the single-line
    // layout policy hide a current line whose companion vocal is the only
    // additional rendered row.
    val hasContent = content.main.isNotBlank() ||
        content.next.isNotBlank() || songInfo.text.isNotBlank()
    val show = enabled &&
        state.attached &&
        viewShown &&
        aodShown &&
        pauseAllowed &&
        !fullAodActive &&
        hasContent
    val decisionReason = if (show) {
        "policy_passed"
    } else {
        when {
            !enabled -> "feature_disabled"
            !state.attached -> "view_detached"
            !viewShown -> "view_hidden"
            !aodShown -> "aod_panel_hidden"
            !pauseAllowed -> "pause_policy"
            fullAodActive -> "lockscreen_aod_active"
            !hasContent -> "no_lyrics_or_song_info"
            else -> "policy_rejected"
        }
    }
    AodEnvironmentDiagnostics.log(
        context = view.context,
        stage = "classic_decision",
        modulePrefs = prefs,
        view = view,
        runtime = AodRuntimeDiagnosticState(
            surface = "classic_aod_plugin",
            fullAod = fullAodActive,
            aodPanelShown = aodShown,
            playing = state.playing,
            pauseStyle = textStyle.pauseStyle,
            pauseAllowed = pauseAllowed,
            hasContent = hasContent,
            showDecision = show,
            decisionReason = decisionReason,
            overlayPresent = state.overlay != null,
            overlayShown = state.overlay?.root?.isShown,
        ),
        dedupeKey = diagnosticKey,
    )

    if (!show) {
        val reason = decisionReason
        DisplayDiagnosticLogger.log(
            channel = "AOD_CLASSIC",
            result = "hidden",
            reason = reason,
            extra = "attached=${state.attached}, viewShown=$viewShown, " +
                "aodShown=$aodShown, fullAodActive=$fullAodActive, " +
                "overlay=${state.overlay != null}",
            dedupeKey = diagnosticKey,
        )
        state.overlay?.root?.visibility = View.GONE
        return
    }

    val overlay = state.overlay ?: createAodPluginOverlay(api, aodView)?.also {
        state.overlay = it
    } ?: return
    val contentChanged = overlay.main.text.toString() != content.main ||
        overlay.translation.text.toString() != content.translation ||
        overlay.backing.text.toString() != content.backing ||
        overlay.backingTranslation.text.toString() != content.backingTranslation ||
        overlay.overlappingMain.text.toString() != content.overlappingMain ||
        overlay.overlappingTranslation.text.toString() != content.overlappingTranslation ||
        overlay.overlappingBacking.text.toString() != content.overlappingBacking ||
        overlay.overlappingBackingTranslation.text.toString() !=
            content.overlappingBackingTranslation ||
        overlay.next.text.toString() != content.next ||
        overlay.songInfo.text.toString() != songInfo.text ||
        overlay.appliedSongInfoPackage != songInfo.sourcePackage ||
        overlay.appliedSongInfoTextSize != songInfo.textSize ||
        overlay.appliedSongInfoShowsIcon != songInfo.showIcon
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
    NotificationMediaAodLyricHooker.applyClassicTextStyle(overlay, textStyle)
    NotificationMediaAodLyricHooker.applyLyricRowOrder(overlay, textStyle.swapTranslation)
    updateClassicEmbeddedSongInfo(overlay, songInfo)
    updateClassicLineSpacing(overlay)
    overlay.root.visibility = View.VISIBLE
    overlay.root.bringToFront()
    positionAodPluginOverlay(overlay)
    AodEnvironmentDiagnostics.log(
        context = view.context,
        stage = "classic_overlay_visible",
        modulePrefs = prefs,
        view = overlay.root,
        runtime = AodRuntimeDiagnosticState(
            surface = "classic_aod_plugin",
            fullAod = fullAodActive,
            aodPanelShown = aodShown,
            playing = state.playing,
            pauseStyle = textStyle.pauseStyle,
            pauseAllowed = pauseAllowed,
            hasContent = hasContent,
            showDecision = true,
            decisionReason = "overlay_visible",
            overlayPresent = true,
            overlayShown = overlay.root.isShown,
        ),
        dedupeKey = diagnosticKey,
    )
    DisplayDiagnosticLogger.log(
        channel = "AOD_CLASSIC",
        result = "shown",
        reason = "overlay_visible",
        extra = "attached=${state.attached}, viewShown=$viewShown, aodShown=$aodShown, " +
            "contentChanged=$contentChanged, styleChanged=$styleChanged, " +
            "songInfo=${songInfo.text.isNotBlank()}",
        dedupeKey = diagnosticKey,
    )
    if (contentChanged || styleChanged || alignmentChanged) {
        overlay.root.invalidate()
        overlay.parent.invalidate()
        NotificationMediaAodLyricHooker.requestAodFrameRefresh(aodView.javaClass.classLoader)
    }
}

@SuppressLint("UseKtx")
internal fun NotificationMediaAodLyricHooker.createAodPluginOverlay(
    api: AodPluginApi,
    aodView: Any
): AodPluginOverlay? {
    val diagnosticKey = "AOD_CLASSIC/${System.identityHashCode(aodView)}/overlay"
    val aodRoot = aodView as? FrameLayout ?: run {
        DisplayDiagnosticLogger.log(
            channel = "AOD_CLASSIC",
            result = "skipped",
            reason = "root_not_frame_layout",
            extra = "rootClass=${aodView.javaClass.name}",
            dedupeKey = "$diagnosticKey/root",
        )
        return null
    }
    val anchor = api.getNotificationIcons(aodView) ?: run {
        DisplayDiagnosticLogger.log(
            channel = "AOD_CLASSIC",
            result = "skipped",
            reason = "notification_icons_null",
            extra = "rootClass=${aodRoot.javaClass.name}",
            dedupeKey = "$diagnosticKey/anchor_presence",
        )
        return null
    }
    if (!anchor.isAttachedToWindow) {
        DisplayDiagnosticLogger.log(
            channel = "AOD_CLASSIC",
            result = "pending",
            reason = "anchor_detached",
            extra = "anchorClass=${anchor.javaClass.name}, " +
                "width=${anchor.width}, height=${anchor.height}",
            dedupeKey = "$diagnosticKey/anchor_attachment",
        )
    }
    if (anchor.width <= 0 || anchor.height <= 0) {
        DisplayDiagnosticLogger.log(
            channel = "AOD_CLASSIC",
            result = "pending",
            reason = "anchor_zero_size",
            extra = "attached=${anchor.isAttachedToWindow}, " +
                "width=${anchor.width}, height=${anchor.height}",
            dedupeKey = "$diagnosticKey/anchor_size",
        )
    }
    val parentCandidate = api.getTableModeContainer(aodView)
    val movingContainer = parentCandidate as? FrameLayout
    if (movingContainer == null) {
        DisplayDiagnosticLogger.log(
            channel = "AOD_CLASSIC",
            result = "pending",
            reason = "parent_unavailable",
            extra = "candidateClass=${parentCandidate?.javaClass?.name ?: "null"}, " +
                "usingRootFallback=true",
            dedupeKey = "$diagnosticKey/parent",
        )
    }
    val parent = movingContainer ?: aodRoot
    val context = aodRoot.context
    val main = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_CLASSIC_AOD_MAIN_TEXT_SIZE.toFloat(),
        )
    }
    val translation = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(0xCCFFFFFF.toInt())
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
        )
    }
    val backing = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = main.typeface
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_CLASSIC_AOD_BACKING_TEXT_SIZE.toFloat(),
        )
    }
    val backingTranslation = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = translation.typeface
        setTextColor(0xCCFFFFFF.toInt())
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
        )
    }
    val overlappingMain = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = main.typeface
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_CLASSIC_AOD_MAIN_TEXT_SIZE.toFloat(),
        )
    }
    val overlappingTranslation = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = translation.typeface
        setTextColor(0xCCFFFFFF.toInt())
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
        )
    }
    val overlappingBacking = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = main.typeface
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_CLASSIC_AOD_BACKING_TEXT_SIZE.toFloat(),
        )
    }
    val overlappingBackingTranslation = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = translation.typeface
        setTextColor(0xCCFFFFFF.toInt())
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
        )
    }
    val next = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = translation.typeface
        setTextColor(0xCCFFFFFF.toInt())
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
        )
    }
    val sourceIcon = ImageView(context).apply {
        visibility = View.GONE
    }
    val songInfo = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(0xCCFFFFFF.toInt())
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
        )
    }
    val songInfoRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
        addView(
            sourceIcon,
            LinearLayout.LayoutParams(
                (AOD_PLUGIN_SONG_INFO_ICON_DP * resources.displayMetrics.density).toInt(),
                (AOD_PLUGIN_SONG_INFO_ICON_DP * resources.displayMetrics.density).toInt()
            ).apply {
                marginEnd = (
                    AOD_PLUGIN_SONG_INFO_ICON_GAP_DP * resources.displayMetrics.density
                ).toInt()
            }
        )
        addView(
            songInfo,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }
    val root = LinearLayout(context).apply {
        id = View.generateViewId()
        tag = NotificationMediaAodLyricHooker.AOD_PLUGIN_OVERLAY_TAG
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val horizontalPadding = (8f * resources.displayMetrics.density).toInt()
        setPadding(horizontalPadding, 0, horizontalPadding, 0)
        addView(
            songInfoRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (
                    NotificationMediaAodLyricHooker.AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                        resources.displayMetrics.density
                ).toInt()
            }
        )
        addView(
            main,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            translation,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (
                    NotificationMediaAodLyricHooker.AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                        resources.displayMetrics.density
                ).toInt()
            }
        )
        addView(
            backing,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (
                    NotificationMediaAodLyricHooker.AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                        resources.displayMetrics.density
                ).toInt()
            }
        )
        addView(
            backingTranslation,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (
                    NotificationMediaAodLyricHooker.AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                        resources.displayMetrics.density
                ).toInt()
            }
        )
        addView(
            overlappingMain,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (
                    NotificationMediaAodLyricHooker.AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                        resources.displayMetrics.density
                ).toInt()
            }
        )
        addView(
            overlappingTranslation,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (
                    NotificationMediaAodLyricHooker.AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                        resources.displayMetrics.density
                ).toInt()
            }
        )
        addView(
            overlappingBacking,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (
                    NotificationMediaAodLyricHooker.AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                        resources.displayMetrics.density
                ).toInt()
            }
        )
        addView(
            overlappingBackingTranslation,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (
                    NotificationMediaAodLyricHooker.AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                        resources.displayMetrics.density
                ).toInt()
            }
        )
        addView(
            next,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (
                    NotificationMediaAodLyricHooker.AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                        resources.displayMetrics.density
                ).toInt()
            }
        )
    }
    parent.addView(
        root,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        )
    )
    val drawWakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(
        DRAW_WAKE_LOCK_LEVEL,
        "${context.packageName}:HyperLyricsAodDraw"
    ).apply {
        setReferenceCounted(false)
    }
    val overlay = AodPluginOverlay(
        root = root,
        songInfoRow = songInfoRow,
        sourceIcon = sourceIcon,
        songInfo = songInfo,
        main = main,
        translation = translation,
        backing = backing,
        backingTranslation = backingTranslation,
        overlappingMain = overlappingMain,
        overlappingTranslation = overlappingTranslation,
        overlappingBacking = overlappingBacking,
        overlappingBackingTranslation = overlappingBackingTranslation,
        next = next,
        parent = parent,
        aodRoot = aodRoot,
        anchor = anchor,
        drawWakeLock = drawWakeLock
    )
    main.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        updateClassicLineSpacing(overlay)
    }
    val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        positionAodPluginOverlay(overlay)
        true
    }
    overlay.preDrawListener = preDrawListener
    aodRoot.viewTreeObserver.addOnPreDrawListener(preDrawListener)
    positionAodPluginOverlay(overlay)
    HookLogger.i(
        TAG,
        "通知图标式息屏歌词已挂载: parent=${parent.javaClass.name}, " +
            "anchor=${anchor.javaClass.name}"
    )
    return overlay
}

internal fun NotificationMediaAodLyricHooker.updateClassicLineSpacing(overlay: AodPluginOverlay) {
    val mainParams = overlay.main.layoutParams as? LinearLayout.LayoutParams ?: return
    if (
        mainParams.height != ViewGroup.LayoutParams.WRAP_CONTENT ||
        mainParams.weight != 0f
    ) {
        mainParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        mainParams.weight = 0f
        overlay.main.layoutParams = mainParams
    }

    val compact = AodMediaLyricPolicy.shouldCompactClassicMain(overlay.main.lineCount)
    val gapDp = if (compact) {
        NotificationMediaAodLyricHooker.AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP
    } else {
        NotificationMediaAodLyricHooker.AOD_PLUGIN_MULTI_LINE_LYRIC_GAP_DP
    }
    val density = overlay.main.resources.displayMetrics.density
    val contentRows = NotificationMediaAodLyricHooker.orderedLyricViews(
        overlay,
        overlay.appliedTextStyle?.swapTranslation == true,
    )
    val visibleRows = contentRows.filter { it.visibility == View.VISIBLE }
    val firstRow = visibleRows.firstOrNull() ?: return
    val secondRow = visibleRows.getOrNull(1)
    val firstBackingRow = contentRows
        .filter { it === overlay.backing || it === overlay.backingTranslation }
        .firstOrNull { it.visibility == View.VISIBLE }
    val primaryVisibleCount = listOf(overlay.main, overlay.translation)
        .count { it.visibility == View.VISIBLE }
    val normalMargin = (NotificationMediaAodLyricHooker.AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP * density).toInt()
    val groupMargin = (8f * density).toInt()
    val firstMargin = (gapDp * density).toInt()
    contentRows.forEach { view ->
        val params = view.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
        val targetMargin = when {
            view === firstRow -> 0
            view === secondRow && primaryVisibleCount > 0 -> firstMargin
            view === firstBackingRow && primaryVisibleCount > 1 -> groupMargin
            else -> normalMargin
        }
        if (params.topMargin != targetMargin) {
            params.topMargin = targetMargin
            view.layoutParams = params
        }
    }
}

internal fun NotificationMediaAodLyricHooker.positionAodPluginOverlay(overlay: AodPluginOverlay) {
    if (!overlay.aodRoot.isAttachedToWindow || overlay.aodRoot.width <= 0) return
    val density = overlay.aodRoot.resources.displayMetrics.density
    val rootLocation = IntArray(2)
    val parentLocation = IntArray(2)
    val anchorLocation = IntArray(2)
    overlay.aodRoot.getLocationOnScreen(rootLocation)
    overlay.parent.getLocationOnScreen(parentLocation)
    overlay.anchor.getLocationOnScreen(anchorLocation)

    val sideMargin = (NotificationMediaAodLyricHooker.AOD_PLUGIN_SIDE_MARGIN_DP * density).toInt()
    val gap = (NotificationMediaAodLyricHooker.AOD_PLUGIN_GAP_DP * density).toInt()
    val bottomSafe = (NotificationMediaAodLyricHooker.AOD_PLUGIN_BOTTOM_SAFE_DP * density).toInt()
    val maxWidth = (NotificationMediaAodLyricHooker.AOD_PLUGIN_MAX_WIDTH_DP * density).toInt()
    val availableWidth = (overlay.aodRoot.width - sideMargin * 2).coerceAtLeast(1)
    val width = minOf(maxWidth, availableWidth)
    val anchorCenter = anchorLocation[0] + overlay.anchor.width / 2
    val rootLeft = rootLocation[0] + sideMargin
    val rootRight = rootLocation[0] + overlay.aodRoot.width - sideMargin
    val leftOnScreen = (anchorCenter - width / 2).coerceIn(
        rootLeft,
        (rootRight - width).coerceAtLeast(rootLeft)
    )
    val topOnScreen = anchorLocation[1] + overlay.anchor.height + gap
    val bottomLimit = rootLocation[1] + overlay.aodRoot.height - bottomSafe
    val availableHeight = (bottomLimit - topOnScreen).coerceAtLeast(1)
    val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
    val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    updateClassicSongInfoMaxWidth(overlay, width)
    overlay.root.measure(widthSpec, heightSpec)
    updateClassicLineSpacing(overlay)
    overlay.root.measure(widthSpec, heightSpec)
    val contentHeight = overlay.root.measuredHeight
    val height = AodMediaLyricPolicy.classicOverlayHeight(
        contentHeight = contentHeight,
        availableHeight = availableHeight
    )

    val params = overlay.root.layoutParams as FrameLayout.LayoutParams
    val leftMargin = leftOnScreen - parentLocation[0]
    val topMargin = topOnScreen - parentLocation[1]
    if (
        params.width != width ||
        params.height != height ||
        params.leftMargin != leftMargin ||
        params.topMargin != topMargin
    ) {
        params.width = width
        params.height = height
        params.leftMargin = leftMargin
        params.topMargin = topMargin
        overlay.root.layoutParams = params
    }
    if (overlay.appliedHeight != height) {
        overlay.appliedHeight = height
        HookLogger.i(
            TAG,
            "经典 AOD 歌词覆盖层已按内容实测高度调整: " +
                "contentHeight=$contentHeight, availableHeight=$availableHeight, " +
                "targetHeight=$height"
        )
    }
}

