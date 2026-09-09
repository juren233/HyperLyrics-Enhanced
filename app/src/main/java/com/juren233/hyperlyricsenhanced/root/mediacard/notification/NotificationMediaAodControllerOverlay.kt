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

internal fun NotificationMediaAodLyricHooker.createOverlay(
    api: NativeApi,
    holder: Any,
    actions: List<View>
): LyricOverlay? {
    val player = api.getPlayer(holder)
    if (actions.isEmpty()) return null
    val title = api.getTitleText(holder)
    val artist = api.getArtistText(holder)
    val album = api.getAlbumView(holder) ?: artist
    val context = player.context

    val main = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = title.typeface
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE.toFloat(),
        )
        setTextColor(title.currentTextColor)
    }
    val translation = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = artist.typeface
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
        )
        setTextColor(artist.currentTextColor)
    }
    val backing = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = title.typeface
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_BACKING_TEXT_SIZE.toFloat(),
        )
        setTextColor(title.currentTextColor)
    }
    val backingTranslation = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = artist.typeface
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
        )
        setTextColor(artist.currentTextColor)
    }
    val overlappingMain = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = title.typeface
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE.toFloat(),
        )
        setTextColor(title.currentTextColor)
    }
    val overlappingTranslation = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = artist.typeface
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
        )
        setTextColor(artist.currentTextColor)
    }
    val overlappingBacking = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = title.typeface
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_BACKING_TEXT_SIZE.toFloat(),
        )
        setTextColor(title.currentTextColor)
    }
    val overlappingBackingTranslation = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = artist.typeface
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
        )
        setTextColor(artist.currentTextColor)
    }
    val next = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = artist.typeface
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
        )
        setTextColor(artist.currentTextColor)
    }
    val root = LinearLayout(context).apply {
        id = View.generateViewId()
        tag = NotificationMediaAodLyricHooker.OVERLAY_TAG
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val density = resources.displayMetrics.density
        addView(main, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        addView(translation, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (NotificationMediaAodLyricHooker.LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
        })
        addView(backing, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (NotificationMediaAodLyricHooker.LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
        })
        addView(backingTranslation, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (NotificationMediaAodLyricHooker.LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
        })
        addView(overlappingMain, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (NotificationMediaAodLyricHooker.LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
        })
        addView(overlappingTranslation, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (NotificationMediaAodLyricHooker.LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
        })
        addView(overlappingBacking, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (NotificationMediaAodLyricHooker.LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
        })
        addView(overlappingBackingTranslation, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (NotificationMediaAodLyricHooker.LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
        })
        addView(next, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (NotificationMediaAodLyricHooker.LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
        })
    }

    val params = createConstraintLayoutParams(player, album) ?: return null
    val playerSize = captureViewSize(player)
    val backgroundView = api.getMediaBackground(holder) ?: player
    val backgroundSize = captureViewSize(backgroundView)
    val backgroundConstraints = BackgroundConstraints.create(backgroundView)
    val headerHeightController = MediaHeaderHeightController.create(player.parent as? View)
    val fullAodHeightId = context.resources.getIdentifier(
        "qs_media_session_height_expanded_fullAod",
        "dimen",
        context.packageName
    )
    if (fullAodHeightId != 0) {
        backgroundSize.baseHeight = context.resources.getDimensionPixelSize(fullAodHeightId)
    }
    player.addView(root, params)
    val powerManager = context.getSystemService(PowerManager::class.java)
    val drawWakeLock = powerManager.newWakeLock(
        DRAW_WAKE_LOCK_LEVEL,
        "${context.packageName}:HyperLyricsAodDraw"
    ).apply {
        setReferenceCounted(false)
    }
    val overlay = LyricOverlay(
        root = root,
        main = main,
        translation = translation,
        backing = backing,
        backingTranslation = backingTranslation,
        overlappingMain = overlappingMain,
        overlappingTranslation = overlappingTranslation,
        overlappingBacking = overlappingBacking,
        overlappingBackingTranslation = overlappingBackingTranslation,
        next = next,
        artist = artist,
        album = album,
        player = player,
        playerSize = playerSize,
        backgroundSize = backgroundSize,
        backgroundConstraints = backgroundConstraints,
        headerHeightController = headerHeightController,
        drawWakeLock = drawWakeLock
    )
    root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        updateLockScreenCardHeight(overlay)
    }
    HookLogger.i(
        TAG,
        "锁屏 AOD 媒体卡片动态高度已挂载: player=${player.javaClass.name}, " +
            "playerBase=${playerSize.baseHeight}, " +
            "playerLayout=${playerSize.originalLayoutHeight}, " +
            "background=${backgroundSize.view.javaClass.name}, " +
            "backgroundBase=${backgroundSize.baseHeight}, " +
            "backgroundLayout=${backgroundSize.originalLayoutHeight}, " +
            "header=${headerHeightController?.view?.javaClass?.name.orEmpty()}, " +
            "headerBase=${headerHeightController?.originalHeight ?: 0}"
    )
    return overlay
}

internal fun NotificationMediaAodLyricHooker.createConstraintLayoutParams(
    player: ViewGroup,
    metadataAnchor: View
): ViewGroup.LayoutParams? = runCatching {
    val loader = requireNotNull(player.javaClass.classLoader) {
        "ConstraintLayout class loader unavailable"
    }
    val paramsClass = loader.loadClass(
        "androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams"
    )
    val params = paramsClass.getConstructor(
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType
    ).newInstance(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ) as ViewGroup.LayoutParams
    paramsClass.getField("startToStart").setInt(params, 0)
    paramsClass.getField("endToEnd").setInt(params, 0)
    paramsClass.getField("topToBottom").setInt(params, metadataAnchor.id)
    (params as ViewGroup.MarginLayoutParams).topMargin = (
        LOCK_SCREEN_AOD_TOP_GAP_DP * player.resources.displayMetrics.density
        ).toInt()
    params
}.onFailure {
    HookLogger.e(TAG, "创建息屏歌词布局参数失败", it)
}.getOrNull()

internal fun NotificationMediaAodLyricHooker.restoreActions(state: ControllerState) {
    if (state.actionVisibilities.isEmpty()) return
    state.actionVisibilities.forEach { (view, visibility) -> view.visibility = visibility }
    state.actionVisibilities.clear()
}

internal fun NotificationMediaAodLyricHooker.safeApply(controller: Any, state: ControllerState) {
    MediaCardDiagnosticLogger.log(
        stage = "aod_lockscreen_media",
        event = "apply_begin",
        details = "controller=${MediaCardDiagnosticLogger.identity(controller)},state=${MediaCardDiagnosticLogger.identity(state)},fullAod=${state.fullAod},aodActive=${state.aodActive},playing=${state.playing},holder=${MediaCardDiagnosticLogger.identity(state.holder)},mediaData=${MediaCardDiagnosticLogger.identity(state.mediaData)},overlay=${MediaCardDiagnosticLogger.view(state.overlay?.root)}",
    )
    runCatching { applyState(controller, state) }
        .onSuccess {
            MediaCardDiagnosticLogger.log(
                stage = "aod_lockscreen_media",
                event = "apply_complete",
                details = "controller=${MediaCardDiagnosticLogger.identity(controller)},fullAod=${state.fullAod},aodActive=${state.aodActive},playing=${state.playing},overlay=${MediaCardDiagnosticLogger.view(state.overlay?.root)}",
            )
        }
        .onFailure {
            MediaCardDiagnosticLogger.log(
                stage = "aod_lockscreen_media",
                event = "apply_failed",
                reason = "exception",
                details = "controller=${MediaCardDiagnosticLogger.identity(controller)},error=${MediaCardDiagnosticLogger.sanitize(it.message)}",
            )
            restoreActions(state)
            state.overlay?.let { overlay ->
                overlay.root.visibility = View.GONE
                restorePlayerHeight(overlay, state.fullAod)
            }
            HookLogger.e(TAG, "应用息屏歌词失败", it)
        }
}

internal fun NotificationMediaAodLyricHooker.shouldPollPosition(state: ControllerState): Boolean {
    return NotificationMediaAodLyricHooker.isEnabled() && state.aodActive && state.playing &&
        LyriconDataBridge.currentSong != null
}

internal fun NotificationMediaAodLyricHooker.hasActiveAodPluginState(): Boolean {
    return NotificationMediaAodLyricHooker.isEnabled() &&
        LyriconDataBridge.currentSong != null &&
        synchronized(NotificationMediaAodLyricHooker.aodPluginStates) {
            NotificationMediaAodLyricHooker.aodPluginStates.values.any { it.attached && it.playing }
        }
}

internal fun NotificationMediaAodLyricHooker.shouldRefreshNoLyricPreview(position: Long): Boolean {
    if (currentActualLyrics().isNotEmpty()) return false
    val currentPrefs = prefs ?: return false
    val previewEnabled = currentPrefs.getBoolean(
        RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_NEXT_SONG_PREVIEW,
        RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW,
    ) || currentPrefs.getBoolean(
        RootConstants.KEY_HOOK_CLASSIC_AOD_NEXT_SONG_PREVIEW,
        RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW,
    )
    if (!previewEnabled) return false
    val duration = LyriconDataBridge.currentSong?.duration?.takeIf { it > 0L }
        ?: return false
    if (position < (duration - 5_000L).coerceAtLeast(0L) || position >= duration) {
        return false
    }
    val now = SystemClock.elapsedRealtime()
    if (now - NotificationMediaAodLyricHooker.lastNoLyricPreviewRefreshAt < NotificationMediaAodLyricHooker.NO_LYRIC_PREVIEW_REFRESH_INTERVAL_MS) {
        return false
    }
    lastNoLyricPreviewRefreshAt = now
    return true
}

internal fun NotificationMediaAodLyricHooker.updatePositionPolling() {
    val hasTarget = synchronized(NotificationMediaAodLyricHooker.states) { NotificationMediaAodLyricHooker.states.values.any(::shouldPollPosition) } ||
        hasActiveAodPluginState()
    if (hasTarget) {
        schedulePositionPoll()
    } else {
    NotificationMediaAodLyricHooker.mainHandler.removeCallbacks(NotificationMediaAodLyricHooker.positionPollRunnable)
    positionPollScheduled = false
    DisplayDiagnosticLogger.clear("AOD_LOCK")
    DisplayDiagnosticLogger.clear("AOD_CLASSIC")
}
}

internal fun NotificationMediaAodLyricHooker.schedulePositionPoll() {
    if (NotificationMediaAodLyricHooker.positionPollScheduled) return
    positionPollScheduled = true
    NotificationMediaAodLyricHooker.mainHandler.postDelayed(NotificationMediaAodLyricHooker.positionPollRunnable, NotificationMediaAodLyricHooker.POSITION_POLL_INTERVAL_MS)
}

internal fun NotificationMediaAodLyricHooker.scheduleAodPluginInitialRefresh(aodView: Any, state: AodPluginState) {
    state.initialRefreshGeneration++
    val generation = state.initialRefreshGeneration
    val viewReference = WeakReference(aodView)
    DisplayDiagnosticLogger.log(
        channel = "AOD_CLASSIC",
        result = "pending",
        reason = "initial_refresh_scheduled",
        extra = "attempts=${NotificationMediaAodLyricHooker.AOD_PLUGIN_INITIAL_REFRESH_DELAYS_MS.size}, generation=$generation",
        dedupeKey = "AOD_CLASSIC/${System.identityHashCode(aodView)}/initial",
    )
    NotificationMediaAodLyricHooker.AOD_PLUGIN_INITIAL_REFRESH_DELAYS_MS.forEach { delay ->
        NotificationMediaAodLyricHooker.mainHandler.postDelayed(
            {
                val target = viewReference.get() ?: return@postDelayed
                val currentState = synchronized(NotificationMediaAodLyricHooker.aodPluginStates) {
                    NotificationMediaAodLyricHooker.aodPluginStates[target]
                }
                if (currentState !== state || state.initialRefreshGeneration != generation) {
                    return@postDelayed
                }
                safeApplyAodPlugin(target, state)
                updatePositionPolling()
            },
            delay
        )
    }
}

internal fun NotificationMediaAodLyricHooker.synchronizeLyricPosition(
    preferredApi: NativeApi? = null,
    preferredController: Any? = null
) {
    val mediaPosition = if (preferredApi != null && preferredController != null) {
        preferredApi.currentPlaybackPosition(preferredController)
    } else {
        synchronized(NotificationMediaAodLyricHooker.states) { NotificationMediaAodLyricHooker.states.entries.toList() }
            .firstNotNullOfOrNull { (controller, state) ->
                if (!state.playing) return@firstNotNullOfOrNull null
                NotificationMediaAodLyricHooker.resolveApi(controller.javaClass.classLoader)
                    ?.currentPlaybackPosition(controller)
            }
    }
    val position = LyriconDataBridge.estimatedPosition() ?: mediaPosition ?: return
    LyriconDataBridge.updateEstimatedPosition(position)
}

