/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.content.Intent
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Rect
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.TypefaceSpan
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleLyricsBlurPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.ApplePronunciationVisibilityPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleSystemFontWeightPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule
import io.github.proify.extensions.android.ScreenStateMonitor
import io.github.proify.extensions.inflate
import io.github.proify.extensions.json
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleContentLocalizationHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleDebugNetworkHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleFrameworkMetadataHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.ApplePlaybackHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.FunctionalAppleMusicHookModule
import io.github.proify.lyricon.amprovider.xposed.lyrics.AppleOnlineSourceMenuHooks
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalReentryGuard
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalStack
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.RemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import android.content.SharedPreferences


internal class AppleLyricsBlurHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val preferences: () -> android.content.SharedPreferences?,
    private val playbackHooks: () -> ApplePlaybackHooks,
    private val currentFragment: () -> Any?,
) {
    private companion object {
        const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
        const val APPLE_LYRICS_INITIAL_ANCHOR_Y_FRACTION = 0.22f
        const val APPLE_LYRICS_SCROLL_STATE_IDLE = 0
        const val APPLE_LYRICS_IDLE_RECHECK_DELAY_MS = 96L
        const val APPLE_LYRICS_OUTGOING_RECHECK_DELAY_MS = 16L
        const val APPLE_LYRICS_BEFORE_FIRST_LINE_RECHECK_MAX_MS = 250L
        const val APPLE_LYRICS_HYPER_OS_SELF_BLUR_TYPE = 0
    }

    private val application: Application
        get() = runtime.application
    private val classLoader: ClassLoader
        get() = runtime.classLoader
    private val hookResolver: AppleMusicHookResolver
        get() = runtime.hookResolver
    private val hookRegistrar
        get() = runtime.hookRegistrar
    private val mainHandler: Handler
        get() = runtime.mainHandler
    private val contentUiLanguagePrefs: android.content.SharedPreferences?
        get() = preferences()

    private val appleLyricsBlurRuntimeStates = Collections.synchronizedMap(
        WeakHashMap<View, AppleLyricsBlurRuntimeState>()
    )
    private val appleLyricsBlurredViews = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    )
    private val appleLyricsRecyclerViewsByAdapter = Collections.synchronizedMap(
        WeakHashMap<Any, WeakReference<View>>()
    )
    private val appleLyricsRecyclerViewClassifications = WeakIdentityMap<View, Boolean>()
    private val appleLyricsRecyclerAdapterClassNames by lazy {
        hookResolver.configuredClassNames(AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER).toSet()
    }
    private val lyricsRecyclerAdapterTargets by lazy {
        hookResolver.resolveClasses(AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER)
    }
    private val lyricsUiTarget by lazy {
        hookResolver.resolveClass(AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW).target
    }
    private val lyricsNativeTarget by lazy {
        hookResolver.resolveClass(AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD).target
    }

    internal fun lyricsAdapterMember(
        adapter: Any,
        member: AppleMusicRuntimeMember,
    ): String = lyricsRecyclerAdapterTargets
        .firstOrNull { resolved -> resolved.clazz.isAssignableFrom(adapter.javaClass) }
        ?.target
        ?.runtimeMemberName(member)
        ?: error("Apple Music lyrics adapter target unavailable: ${adapter.javaClass.name}")

    private fun lyricsUiMember(member: AppleMusicRuntimeMember): String =
        lyricsUiTarget.runtimeMemberName(member)

    private fun lyricsNativeMember(member: AppleMusicRuntimeMember): String =
        lyricsNativeTarget.runtimeMemberName(member)
    private val appleLyricsChildAdapterPositionMethods =
        ConcurrentHashMap<Class<*>, Method>()
    private val appleLyricsHyperOsMethods = Collections.synchronizedMap(
        WeakHashMap<Class<*>, AppleLyricsHyperOsMethods>()
    )

    fun isAppleLyricsRecyclerAdapter(adapter: Any?): Boolean =
        adapter?.javaClass?.name in appleLyricsRecyclerAdapterClassNames

    private fun appleLyricsBlurMode(): Int {
        val prefs = contentUiLanguagePrefs
        val configured = prefs?.getInt(
            RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_BLUR_EFFECT,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_BLUR_EFFECT,
        ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_BLUR_EFFECT
        return AppleLyricsBlurPolicy.normalizeMode(configured)
    }

    private fun appleLyricsBlurRadiusRange(mode: Int): ClosedFloatingPointRange<Float> {
        val prefs = contentUiLanguagePrefs
        val (configuredMin, configuredMax, allowedMin, allowedMax) =
            if (mode == AppleLyricsBlurPolicy.NATIVE) {
                listOf(
                    prefs?.getFloat(
                        RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
                        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
                    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
                    prefs?.getFloat(
                        RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP,
                        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP,
                    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP,
                    RootConstants.MIN_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP,
                    RootConstants.MAX_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP,
                )
            } else {
                listOf(
                    (prefs?.getInt(
                        RootConstants.KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX,
                        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX,
                    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX)
                        .toFloat(),
                    (prefs?.getInt(
                        RootConstants.KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX,
                        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX,
                    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX)
                        .toFloat(),
                    RootConstants.MIN_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_RADIUS_PX.toFloat(),
                    RootConstants.MAX_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_RADIUS_PX.toFloat(),
                )
            }
        val boundedMin = configuredMin.coerceIn(allowedMin, allowedMax)
        val boundedMax = configuredMax.coerceIn(allowedMin, allowedMax)
        return minOf(boundedMin, boundedMax)..maxOf(boundedMin, boundedMax)
    }


    fun refreshAppleLyricsBlurEffect() {
        mainHandler.post {
            val fragment = currentFragment() ?: return@post
            scheduleAppleLyricsBlur(resolveAppleLyricsRecyclerView(fragment))
        }
    }

    fun scheduleAppleLyricsBlur(
        recyclerView: Any?,
        delayMs: Long = 0L,
    ) {
        val recyclerViewAsView = recyclerView as? View ?: return
        val viewRef = WeakReference(recyclerViewAsView)
        lateinit var applyBlur: Runnable
        applyBlur = Runnable {
            val target = viewRef.get() ?: return@Runnable
            val isCurrent = synchronized(appleLyricsBlurRuntimeStates) {
                val state = appleLyricsBlurRuntimeStates[target]
                if (state?.pendingApplyBlur !== applyBlur) {
                    false
                } else {
                    state.pendingApplyBlur = null
                    true
                }
            }
            if (!isCurrent) return@Runnable
            applyAppleLyricsBlur(target)
        }
        val previous = synchronized(appleLyricsBlurRuntimeStates) {
            val state = appleLyricsBlurRuntimeStates.getOrPut(recyclerViewAsView) {
                AppleLyricsBlurRuntimeState()
            }
            state.pendingApplyBlur.also { state.pendingApplyBlur = applyBlur }
        }
        previous?.let(recyclerViewAsView::removeCallbacks)
        if (delayMs > 0L) {
            recyclerViewAsView.postDelayed(applyBlur, delayMs)
        } else {
            recyclerViewAsView.postOnAnimation(applyBlur)
        }
    }

    private fun resetAppleLyricsBlurRuntimeState(recyclerView: Any?) {
        val recyclerViewAsView = recyclerView as? View ?: return
        val previous = synchronized(appleLyricsBlurRuntimeStates) {
            appleLyricsBlurRuntimeStates.remove(recyclerViewAsView)?.pendingApplyBlur
        }
        previous?.let(recyclerViewAsView::removeCallbacks)
        clearAppleLyricsBlurForRecycler(recyclerViewAsView)
        scheduleAppleLyricsBlur(recyclerViewAsView)
    }

    private fun suspendAppleLyricsBlurForScroll(recyclerView: Any?) {
        val recyclerViewAsView = recyclerView as? View ?: return
        val becameSuspended = synchronized(appleLyricsBlurRuntimeStates) {
            val state = appleLyricsBlurRuntimeStates.getOrPut(recyclerViewAsView) {
                AppleLyricsBlurRuntimeState()
            }
            state.pendingProgrammaticRecenterPosition = null
            if (state.suspendedForScroll) {
                false
            } else {
                state.suspendedForScroll = true
                true
            }
        }
        if (becameSuspended) clearAppleLyricsBlurForRecycler(recyclerViewAsView)
    }

    private fun onAppleLyricsProgrammaticRecenterRequested(
        layoutManager: Any?,
        targetPosition: Int,
    ) {
        if (layoutManager == null || targetPosition < 0) return
        val recyclerView = synchronized(appleLyricsBlurRuntimeStates) {
            appleLyricsBlurRuntimeStates.keys.firstOrNull { candidate ->
                runCatching {
                    AppleReflection.call(candidate, "getLayoutManager")
                }.getOrNull() === layoutManager
            }
        } ?: return
        if (appleLyricsRecyclerScrollState(recyclerView) != APPLE_LYRICS_SCROLL_STATE_IDLE) {
            return
        }
        val marked = synchronized(appleLyricsBlurRuntimeStates) {
            val state = appleLyricsBlurRuntimeStates.getOrPut(recyclerView) {
                AppleLyricsBlurRuntimeState()
            }
            if (!state.suspendedForScroll) {
                false
            } else {
                state.pendingProgrammaticRecenterPosition = targetPosition
                true
            }
        }
        if (marked && BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "Apple lyrics blur: programmatic_recenter_requested, " +
                    "target=$targetPosition"
            )
        }
    }

    private fun completeAppleLyricsProgrammaticRecenter(recyclerView: Any?): Boolean {
        val recyclerViewAsView = recyclerView as? View ?: return false
        val adapter = appleRecyclerAdapter(recyclerViewAsView) ?: return false
        val activePositions = appleLyricsActiveAdapterPositions(adapter)
        val positionedChildren = appleLyricsVisiblePositionedChildren(recyclerViewAsView)
        val instrumentalPositions = appleLyricsInstrumentalAdapterPositions(
            adapter = adapter,
            positionedChildren = positionedChildren,
        )
        val writersCreditsPositions = appleLyricsWritersCreditsAdapterPositions(
            adapter = adapter,
            positionedChildren = positionedChildren,
        )
        val focusPositions = appleLyricsBlurFocusPositions(
            activePositions = activePositions,
            instrumentalPositions = instrumentalPositions,
            writersCreditsPositions = writersCreditsPositions,
        )
        val scrollState = appleLyricsRecyclerScrollState(recyclerViewAsView)
        var completedTarget: Int? = null
        val completed = synchronized(appleLyricsBlurRuntimeStates) {
            val state = appleLyricsBlurRuntimeStates[recyclerViewAsView] ?: return@synchronized false
            val targetPosition = state.pendingProgrammaticRecenterPosition
            if (
                !shouldCompleteAppleLyricsProgrammaticRecenter(
                    suspendedForScroll = state.suspendedForScroll,
                    scrollState = scrollState,
                    pendingTargetPosition = targetPosition,
                    focusPositions = focusPositions,
                )
            ) {
                false
            } else {
                completedTarget = targetPosition
                state.pendingProgrammaticRecenterPosition = null
                state.suspendedForScroll = false
                state.settledAnchorTopY = null
                state.lastActivePositions = activePositions
                state.lastDiagnosticSignature = null
                true
            }
        }
        if (completed && BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "Apple lyrics blur: programmatic_recenter_completed, " +
                    "target=$completedTarget, active=${activePositions.sorted()}, " +
                    "instrumental=${instrumentalPositions.sorted()}, " +
                    "writers=${writersCreditsPositions.sorted()}, " +
                    "focus=${focusPositions.sorted()}"
            )
        }
        return completed
    }

    private fun onAppleLyricsScrollStateChanged(
        recyclerView: Any?,
        scrollState: Int,
    ) {
        if (scrollState != APPLE_LYRICS_SCROLL_STATE_IDLE) {
            suspendAppleLyricsBlurForScroll(recyclerView)
            return
        }
        scheduleAppleLyricsBlur(
            recyclerView = recyclerView,
            delayMs = APPLE_LYRICS_IDLE_RECHECK_DELAY_MS,
        )
    }

    private fun onAppleLyricsActiveLinesUpdated(adapter: Any?) {
        adapter ?: return
        val recyclerView = synchronized(appleLyricsRecyclerViewsByAdapter) {
            appleLyricsRecyclerViewsByAdapter[adapter]?.get()
        } ?: return
        val activePositions = appleLyricsActiveAdapterPositions(adapter)
        val changed = synchronized(appleLyricsBlurRuntimeStates) {
            val state = appleLyricsBlurRuntimeStates.getOrPut(recyclerView) {
                AppleLyricsBlurRuntimeState()
            }
            updateAppleLyricsActivePositions(state, activePositions)
        }
        if (changed) scheduleAppleLyricsBlur(recyclerView)
    }

    private fun updateAppleLyricsActivePositions(
        state: AppleLyricsBlurRuntimeState,
        activePositions: Set<Int>,
    ): Boolean {
        val previousPositions = state.lastActivePositions
        if (state.suspendedForScroll) {
            state.pendingOutgoingPositions = emptySet()
            state.outgoingZoneTopByPosition = emptyMap()
            state.lastActivePositions = activePositions
            return previousPositions != activePositions
        }
        val newlyOutgoingPositions = previousPositions - activePositions
        val pendingOutgoingPositions =
            (state.pendingOutgoingPositions + newlyOutgoingPositions) - activePositions
        val outgoingZoneTopByPosition = state.outgoingZoneTopByPosition
            .filterKeys { it in pendingOutgoingPositions }
            .toMutableMap()
        state.settledAnchorTopY?.let { currentZoneTopY ->
            newlyOutgoingPositions.forEach { position ->
                outgoingZoneTopByPosition.putIfAbsent(position, currentZoneTopY)
            }
        }
        state.pendingOutgoingPositions = pendingOutgoingPositions
        state.outgoingZoneTopByPosition = outgoingZoneTopByPosition
        state.lastActivePositions = activePositions
        return previousPositions != activePositions
    }

    private fun applyAppleLyricsBlur(recyclerView: View) {
        val container = recyclerView as? ViewGroup ?: return
        val state = synchronized(appleLyricsBlurRuntimeStates) {
            appleLyricsBlurRuntimeStates.getOrPut(recyclerView) {
                AppleLyricsBlurRuntimeState()
            }
        }
        val mode = appleLyricsBlurMode()
        if (mode == AppleLyricsBlurPolicy.OFF) {
            state.pendingOutgoingPositions = emptySet()
            state.outgoingZoneTopByPosition = emptyMap()
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "off",
                mode = mode,
            )
            return
        }
        val blurRadiusRange = appleLyricsBlurRadiusRange(mode)

        val adapter = appleRecyclerAdapter(recyclerView) ?: run {
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "adapter_missing",
                mode = mode,
            )
            return
        }
        appleLyricsRecyclerViewsByAdapter[adapter] = WeakReference(recyclerView)
        synchronized(appleLyricsBlurRuntimeStates) {
            state.also { currentState ->
                if (currentState.adapterRef?.get() !== adapter) {
                    currentState.adapterRef = WeakReference(adapter)
                    currentState.settledAnchorTopY = null
                    currentState.suspendedForScroll = false
                    currentState.pendingProgrammaticRecenterPosition = null
                    currentState.lastActivePositions = emptySet()
                    currentState.pendingOutgoingPositions = emptySet()
                    currentState.outgoingZoneTopByPosition = emptyMap()
                }
                if (
                    recyclerView.height > 0 &&
                    currentState.recyclerHeight != recyclerView.height
                ) {
                    currentState.recyclerHeight = recyclerView.height
                    currentState.settledAnchorTopY = null
                    currentState.suspendedForScroll = false
                    currentState.pendingProgrammaticRecenterPosition = null
                    currentState.pendingOutgoingPositions = emptySet()
                    currentState.outgoingZoneTopByPosition = emptyMap()
                }
            }
        }
        if (appleLyricsRecyclerScrollState(recyclerView) != APPLE_LYRICS_SCROLL_STATE_IDLE) {
            state.suspendedForScroll = true
            state.pendingProgrammaticRecenterPosition = null
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "scrolling",
                mode = mode,
                adapter = adapter,
            )
            return
        }

        val children = buildList {
            repeat(container.childCount) { index ->
                container.getChildAt(index)
                    ?.takeIf { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
                    ?.let(::add)
            }
        }
        if (children.isEmpty()) {
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "children_empty",
                mode = mode,
                adapter = adapter,
            )
            return
        }

        val positionedChildren = children.mapNotNull { child ->
            appleLyricsChildAdapterPosition(recyclerView, child)
                .takeIf { it >= 0 }
                ?.let { position -> position to child }
        }
        val activePositions = appleLyricsActiveAdapterPositions(adapter)
        updateAppleLyricsActivePositions(state, activePositions)
        val instrumentalPositions = appleLyricsInstrumentalAdapterPositions(
            adapter = adapter,
            positionedChildren = positionedChildren,
        )
        val writersCreditsPositions = appleLyricsWritersCreditsAdapterPositions(
            adapter = adapter,
            positionedChildren = positionedChildren,
        )
        val focusPositions = appleLyricsBlurFocusPositions(
            activePositions = activePositions,
            instrumentalPositions = instrumentalPositions,
            writersCreditsPositions = writersCreditsPositions,
        )
        val firstLineBeginMs = appleLyricsFirstLineBeginMs(adapter)
        val currentPositionMs = appleLyricsCurrentPlaybackPositionMs()
        val shouldBlurBeforeFirstLine = focusPositions.isEmpty() &&
            AppleLyricsBlurPolicy.shouldBlurBeforeFirstLine(
                currentPositionMs = currentPositionMs,
                firstLineBeginMs = firstLineBeginMs,
            )
        if (focusPositions.isEmpty()) {
            if (shouldBlurBeforeFirstLine) {
                state.settledAnchorTopY = null
                state.pendingOutgoingPositions = emptySet()
                state.outgoingZoneTopByPosition = emptyMap()
                val radiusByPosition = linkedMapOf<Int, Int>()
                positionedChildren
                    .sortedBy(Pair<Int, View>::first)
                    .forEachIndexed { visibleRowIndex, (position, child) ->
                        val radiusPx = AppleLyricsBlurPolicy.beforeFirstLineBlurRadiusPx(
                            mode = mode,
                            visibleRowIndex = visibleRowIndex,
                            minRadius = blurRadiusRange.start,
                            maxRadius = blurRadiusRange.endInclusive,
                            density = recyclerView.resources.displayMetrics.density,
                        )
                        radiusByPosition[position] = radiusPx
                        applyAppleLyricsBlur(
                            view = child,
                            mode = mode,
                            radiusPx = radiusPx,
                        )
                    }
                logAppleLyricsBlurDiagnostic(
                    recyclerView = recyclerView,
                    state = state,
                    stage = "before_first_line",
                    mode = mode,
                    adapter = adapter,
                    activePositions = activePositions,
                    instrumentalPositions = instrumentalPositions,
                    writersCreditsPositions = writersCreditsPositions,
                    focusPositions = focusPositions,
                    positionedChildren = positionedChildren,
                    radiusByPosition = radiusByPosition,
                    currentPositionMs = currentPositionMs,
                    firstLineBeginMs = firstLineBeginMs,
                )
                if (playbackHooks().isPlaying() && currentPositionMs != null && firstLineBeginMs != null) {
                    scheduleAppleLyricsBlur(
                        recyclerView = recyclerView,
                        delayMs = (firstLineBeginMs - currentPositionMs)
                            .coerceIn(APPLE_LYRICS_OUTGOING_RECHECK_DELAY_MS, APPLE_LYRICS_BEFORE_FIRST_LINE_RECHECK_MAX_MS),
                    )
                }
                return
            }
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "active_empty",
                mode = mode,
                adapter = adapter,
                activePositions = activePositions,
                instrumentalPositions = instrumentalPositions,
                writersCreditsPositions = writersCreditsPositions,
                focusPositions = focusPositions,
                positionedChildren = positionedChildren,
                currentPositionMs = currentPositionMs,
                firstLineBeginMs = firstLineBeginMs,
            )
            return
        }
        val focusChildren = positionedChildren.filter { (position, _) ->
            position in focusPositions
        }
        if (focusChildren.isEmpty()) {
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "active_not_visible",
                mode = mode,
                adapter = adapter,
                activePositions = activePositions,
                instrumentalPositions = instrumentalPositions,
                writersCreditsPositions = writersCreditsPositions,
                focusPositions = focusPositions,
                positionedChildren = positionedChildren,
            )
            return
        }

        val initialAnchorY = recyclerView.height * APPLE_LYRICS_INITIAL_ANCHOR_Y_FRACTION
        val anchorChild = focusChildren.minByOrNull { (_, child) ->
            abs(appleLyricsChildTopY(child) - (state.settledAnchorTopY ?: initialAnchorY))
        }?.second ?: run {
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "anchor_child_missing",
                mode = mode,
                adapter = adapter,
                activePositions = activePositions,
                instrumentalPositions = instrumentalPositions,
                writersCreditsPositions = writersCreditsPositions,
                focusPositions = focusPositions,
                positionedChildren = positionedChildren,
            )
            return
        }
        val anchorTopY = appleLyricsChildTopY(anchorChild)
        val settledAnchorTopY = state.settledAnchorTopY
        if (state.suspendedForScroll) {
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = if (state.pendingProgrammaticRecenterPosition == null) {
                    "awaiting_recenter_request"
                } else {
                    "awaiting_recenter_layout"
                },
                mode = mode,
                adapter = adapter,
                activePositions = activePositions,
                instrumentalPositions = instrumentalPositions,
                writersCreditsPositions = writersCreditsPositions,
                focusPositions = focusPositions,
                positionedChildren = positionedChildren,
            )
            return
        }
        if (settledAnchorTopY == null) {
            state.settledAnchorTopY = anchorTopY
        }

        val radiusByPosition = linkedMapOf<Int, Int>()
        val pendingOutgoingPositions = state.pendingOutgoingPositions
        val outgoingZoneTopByPosition = state.outgoingZoneTopByPosition.toMutableMap()
        val deferredBlurPositions = linkedSetOf<Int>()
        val outgoingBottomByPosition = linkedMapOf<Int, Int>()
        val currentZoneTopByPosition = linkedMapOf<Int, Int>()
        positionedChildren.forEach { (childPosition, child) ->
            val rowDistance = focusPositions.minOf { focusPosition ->
                abs(childPosition - focusPosition)
            }
            val isPendingOutgoing = childPosition in pendingOutgoingPositions
            val currentZoneTopY = if (isPendingOutgoing) {
                outgoingZoneTopByPosition.getOrPut(childPosition) {
                    state.settledAnchorTopY ?: appleLyricsChildTopY(child)
                }
            } else {
                null
            }
            val rowBottomY = currentZoneTopY?.let { appleLyricsChildBottomY(child) }
            if (rowBottomY != null) {
                outgoingBottomByPosition[childPosition] = rowBottomY.roundToInt()
                currentZoneTopByPosition[childPosition] = currentZoneTopY.roundToInt()
            }
            val deferBlur = shouldDeferAppleLyricsOutgoingBlur(
                isPendingOutgoing = isPendingOutgoing,
                rowBottomY = rowBottomY,
                currentZoneTopY = currentZoneTopY,
            )
            if (deferBlur) deferredBlurPositions += childPosition
            val radiusPx = if (deferBlur) {
                0
            } else {
                AppleLyricsBlurPolicy.blurRadiusPx(
                    mode = mode,
                    rowDistance = rowDistance,
                    minRadius = blurRadiusRange.start,
                    maxRadius = blurRadiusRange.endInclusive,
                    density = recyclerView.resources.displayMetrics.density,
                )
            }
            radiusByPosition[childPosition] = radiusPx
            applyAppleLyricsBlur(
                view = child,
                mode = mode,
                radiusPx = radiusPx,
            )
        }
        state.pendingOutgoingPositions = deferredBlurPositions
        state.outgoingZoneTopByPosition = outgoingZoneTopByPosition
            .filterKeys { it in deferredBlurPositions }
        logAppleLyricsBlurDiagnostic(
            recyclerView = recyclerView,
            state = state,
            stage = "applied",
            mode = mode,
            adapter = adapter,
            activePositions = activePositions,
            instrumentalPositions = instrumentalPositions,
            writersCreditsPositions = writersCreditsPositions,
            focusPositions = focusPositions,
            positionedChildren = positionedChildren,
            radiusByPosition = radiusByPosition,
            deferredBlurPositions = deferredBlurPositions,
            outgoingBottomByPosition = outgoingBottomByPosition,
            currentZoneTopByPosition = currentZoneTopByPosition,
        )
        if (deferredBlurPositions.isNotEmpty()) {
            scheduleAppleLyricsBlur(
                recyclerView = recyclerView,
                delayMs = APPLE_LYRICS_OUTGOING_RECHECK_DELAY_MS,
            )
        }
    }

    private fun appleLyricsChildTopY(view: View): Float =
        view.top + view.translationY

    private fun appleLyricsChildBottomY(view: View): Float =
        view.bottom + view.translationY

    private fun appleLyricsRecyclerScrollState(recyclerView: Any): Int =
        runCatching {
            (AppleReflection.call(recyclerView, "getScrollState") as? Number)?.toInt()
        }.getOrNull() ?: APPLE_LYRICS_SCROLL_STATE_IDLE

    fun appleLyricsActiveAdapterPositions(adapter: Any): Set<Int> =
        ((runCatching {
            AppleReflection.call(
                adapter,
                lyricsAdapterMember(
                    adapter,
                    AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_POSITIONS_METHOD,
                ),
            )
        }.getOrNull() as? Iterable<*>)
            ?.mapNotNull { (it as? Number)?.toInt()?.takeIf { position -> position >= 0 } }
            ?.toSet())
            .orEmpty()

    private fun appleLyricsLineBeginMs(
        adapter: Any,
        lyrics: Any,
        lineIndex: Int,
    ): Long? = runCatching {
        val linePointer = AppleReflection.call(
            lyrics,
            lyricsAdapterMember(adapter, AppleMusicRuntimeMember.LYRICS_ADAPTER_LINE_AT_METHOD),
            lineIndex,
        ) ?: return@runCatching null
        val nativeLine = AppleReflection.call(
            linePointer,
            lyricsNativeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD),
        ) ?: return@runCatching null
        (AppleReflection.call(
            nativeLine,
            lyricsNativeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD),
        ) as? Number)?.toLong()
    }.getOrNull()

    private fun appleLyricsFirstLineBeginMs(adapter: Any): Long? = runCatching {
        val lyrics = AppleReflection.call(
            adapter,
            lyricsAdapterMember(adapter, AppleMusicRuntimeMember.LYRICS_ADAPTER_LYRICS_METHOD),
        ) ?: return@runCatching null
        val lineCount = (
            AppleReflection.call(
                lyrics,
                lyricsAdapterMember(adapter, AppleMusicRuntimeMember.LYRICS_ADAPTER_LINE_COUNT_METHOD),
            ) as? Number
            )?.toInt()
            ?: return@runCatching null
        if (lineCount <= 0) return@runCatching null
        appleLyricsLineBeginMs(adapter, lyrics, 0)
    }.getOrNull()

    fun appleLyricsAdapterPositionForPlayback(
        adapter: Any,
        playbackPositionMs: Long,
    ): Int? = runCatching {
        val lyrics = AppleReflection.call(
            adapter,
            lyricsAdapterMember(adapter, AppleMusicRuntimeMember.LYRICS_ADAPTER_LYRICS_METHOD),
        ) ?: return@runCatching null
        val lineCount = (AppleReflection.call(
            lyrics,
            lyricsAdapterMember(adapter, AppleMusicRuntimeMember.LYRICS_ADAPTER_LINE_COUNT_METHOD),
        ) as? Number)?.toInt() ?: return@runCatching null
        if (lineCount <= 0) return@runCatching null
        val itemCount = appleRecyclerAdapterItemCount(adapter)
        selectAppleLyricsPlaybackAdapterPosition(
            lineBeginsMs = (0 until lineCount).map { lineIndex ->
                appleLyricsLineBeginMs(adapter, lyrics, lineIndex)
            },
            playbackPositionMs = playbackPositionMs,
            itemCount = itemCount,
        )
    }.getOrNull()

    /**
     * Returns a compact, debug-only view of the adapter's lyric time axis and the
     * adapter positions that are relevant to the current layout. Adapter positions
     * are deliberately not assumed to equal lyric indexes: both the direct position
     * probes and the complete logical begin-time list are emitted so that a later
     * diagnosis can prove or falsify that assumption from runtime evidence.
     */
    fun appleLyricsAdapterDebugSnapshot(
        adapter: Any,
        relevantPositions: Iterable<Int> = emptyList(),
        playbackPositionMs: Long? = null,
    ): String {
        val active = appleLyricsActiveAdapterPositions(adapter).sorted()
        val itemCount = runCatching {
            (AppleReflection.call(adapter, "getItemCount") as? Number)?.toInt()
        }.getOrNull() ?: 0
        val lyrics = runCatching {
            AppleReflection.call(
                adapter,
                lyricsAdapterMember(adapter, AppleMusicRuntimeMember.LYRICS_ADAPTER_LYRICS_METHOD),
            )
        }.getOrNull()
        val lineCount = lyrics?.let {
            runCatching {
                (AppleReflection.call(
                    it,
                    lyricsAdapterMember(adapter, AppleMusicRuntimeMember.LYRICS_ADAPTER_LINE_COUNT_METHOD),
                ) as? Number)?.toInt()
            }.getOrNull()
        } ?: 0
        val logicalBegins = if (lyrics == null || lineCount <= 0) {
            emptyList()
        } else {
            (0 until lineCount.coerceAtMost(256)).mapNotNull { index ->
                appleLyricsLineBeginMs(adapter, lyrics, index)?.let { index to it }
            }
        }
        val playbackLine = playbackPositionMs?.let { position ->
            logicalBegins.indexOfLast { (_, begin) -> begin <= position }
                .takeIf { it >= 0 }
                ?.let { logicalBegins[it].first }
        }
        val positions = (relevantPositions.asSequence() + active.asSequence())
            .filter { it >= 0 }
            .distinct()
            .sorted()
            .take(48)
            .toList()
        val viewTypes = positions.joinToString(",") { position ->
            val viewType = appleLyricsAdapterItemViewType(adapter, position)
            "$position:${viewType ?: "?"}"
        }
        val directBegins = if (lyrics == null) {
            emptyList()
        } else {
            positions.mapNotNull { position ->
                appleLyricsLineBeginMs(adapter, lyrics, position)?.let { "$position:$it" }
            }
        }
        val logicalSample = logicalBegins
            .take(16)
            .joinToString(",") { (index, begin) -> "$index:$begin" }
        return "adapter=${adapter.javaClass.name}@${System.identityHashCode(adapter)}," +
            "itemCount=$itemCount,active=$active,viewTypes=[$viewTypes]," +
            "logicalLineCount=$lineCount,logicalBegins=[$logicalSample]," +
            "directPositionBegins=[${directBegins.joinToString(",")}]," +
            "playback=${playbackPositionMs ?: "none"},playbackLine=$playbackLine"
    }

    private fun appleLyricsCurrentPlaybackPositionMs(): Long? = playbackHooks().currentPositionMs()

    private fun appleLyricsVisiblePositionedChildren(
        recyclerView: View,
    ): List<Pair<Int, View>> {
        val container = recyclerView as? ViewGroup ?: return emptyList()
        return buildList {
            repeat(container.childCount) { index ->
                val child = container.getChildAt(index)
                    ?.takeIf {
                        it.visibility == View.VISIBLE && it.width > 0 && it.height > 0
                    }
                    ?: return@repeat
                appleLyricsChildAdapterPosition(recyclerView, child)
                    .takeIf { it >= 0 }
                    ?.let { position -> add(position to child) }
            }
        }
    }

    private fun appleLyricsInstrumentalAdapterPositions(
        adapter: Any,
        positionedChildren: List<Pair<Int, View>>,
    ): Set<Int> = positionedChildren.mapNotNull { (position, child) ->
        position.takeIf {
            appleLyricsAdapterItemViewType(adapter, position) == 2 ||
                appleLyricsIsInstrumentalIndicator(child)
        }
    }.toSet()

    private fun appleLyricsWritersCreditsAdapterPositions(
        adapter: Any,
        positionedChildren: List<Pair<Int, View>>,
    ): Set<Int> = positionedChildren.mapNotNull { (position, _) ->
        position.takeIf { appleLyricsAdapterItemViewType(adapter, position) == 1 }
    }.toSet()

    private fun appleLyricsAdapterItemViewType(adapter: Any, position: Int): Int? =
        runCatching {
            (AppleReflection.call(adapter, "getItemViewType", position) as? Number)?.toInt()
        }.getOrNull() ?: runCatching {
            (AppleReflection.call(
                adapter,
                lyricsAdapterMember(
                    adapter,
                    AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_VIEW_TYPE_METHOD,
                ),
                position,
            ) as? Number)?.toInt()
        }.getOrNull()

    private fun appleLyricsIsInstrumentalIndicator(view: View): Boolean {
        val rootId = runCatching {
            view.resources.getIdentifier(
                "lyrics_instrumental_root",
                "id",
                APPLE_MUSIC_PACKAGE,
            )
        }.getOrDefault(0)
        return rootId != 0 && view.findViewById<View>(rootId) != null
    }

    fun appleLyricsChildAdapterPosition(recyclerView: Any, child: View): Int {
        val recyclerViewAsView = recyclerView as? View ?: return -1
        val namedPosition = runCatching {
            (AppleReflection.call(recyclerViewAsView, "getChildAdapterPosition", child) as? Number)
                ?.toInt()
        }.getOrNull()?.takeIf { it >= 0 }
        if (namedPosition != null) return namedPosition

        val method = appleLyricsChildAdapterPositionMethods[recyclerViewAsView.javaClass]
            ?: findAppleLyricsChildAdapterPositionMethod(recyclerViewAsView.javaClass)?.also {
                appleLyricsChildAdapterPositionMethods[recyclerViewAsView.javaClass] = it
            }
            ?: return -1
        return runCatching {
            (method.invoke(null, child) as? Number)?.toInt()
        }.getOrNull()?.takeIf { it >= 0 } ?: -1
    }

    private fun findAppleLyricsChildAdapterPositionMethod(clazz: Class<*>): Method? =
        generateSequence(clazz) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.returnType == Int::class.javaPrimitiveType &&
                    method.parameterTypes.contentEquals(arrayOf(View::class.java))
            }
            ?.apply { isAccessible = true }

    private fun logAppleLyricsBlurDiagnostic(
        recyclerView: View,
        state: AppleLyricsBlurRuntimeState,
        stage: String,
        mode: Int,
        adapter: Any? = null,
        activePositions: Set<Int> = emptySet(),
        instrumentalPositions: Set<Int> = emptySet(),
        writersCreditsPositions: Set<Int> = emptySet(),
        focusPositions: Set<Int> = activePositions,
        positionedChildren: List<Pair<Int, View>> = emptyList(),
        radiusByPosition: Map<Int, Int> = emptyMap(),
        deferredBlurPositions: Set<Int> = emptySet(),
        outgoingBottomByPosition: Map<Int, Int> = emptyMap(),
        currentZoneTopByPosition: Map<Int, Int> = emptyMap(),
        currentPositionMs: Long? = null,
        firstLineBeginMs: Long? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val visiblePositions = positionedChildren.map(Pair<Int, View>::first)
        val nativeRenderEffectCount = positionedChildren.count { (_, child) ->
            appleLyricsHasNativeRenderEffect(child)
        }
        val signature = listOf(
            stage,
            mode,
            adapter?.javaClass?.name,
            appleLyricsRecyclerScrollState(recyclerView),
            activePositions.sorted(),
            instrumentalPositions.sorted(),
            writersCreditsPositions.sorted(),
            focusPositions.sorted(),
            visiblePositions,
            state.suspendedForScroll,
            state.settledAnchorTopY?.roundToInt(),
            state.pendingProgrammaticRecenterPosition,
            radiusByPosition,
            deferredBlurPositions.sorted(),
            outgoingBottomByPosition,
            currentZoneTopByPosition,
            currentPositionMs,
            firstLineBeginMs,
            nativeRenderEffectCount,
        ).joinToString("|")
        if (state.lastDiagnosticSignature == signature) return
        state.lastDiagnosticSignature = signature
        ProviderLogger.diagnostic(
            "Apple lyrics blur: stage=$stage, mode=$mode, " +
                "adapter=${adapter?.javaClass?.name ?: "none"}, " +
                "scrollState=${appleLyricsRecyclerScrollState(recyclerView)}, " +
                "active=${activePositions.sorted()}, " +
                "instrumental=${instrumentalPositions.sorted()}, " +
                "writers=${writersCreditsPositions.sorted()}, " +
                "focus=${focusPositions.sorted()}, visible=$visiblePositions, " +
                "suspended=${state.suspendedForScroll}, " +
                "anchor=${state.settledAnchorTopY?.roundToInt() ?: "none"}, " +
                "pendingRecenter=${state.pendingProgrammaticRecenterPosition ?: "none"}, " +
                "deferred=${deferredBlurPositions.sorted()}, " +
                "outgoingBottom=$outgoingBottomByPosition, " +
                "currentZoneTop=$currentZoneTopByPosition, radii=$radiusByPosition, " +
                "currentPosition=$currentPositionMs, firstLineBegin=$firstLineBeginMs, " +
                "nativeEffects=$nativeRenderEffectCount"
        )
    }

    private fun findAppleLyricsScrollToPositionWithOffsetMethod(clazz: Class<*>): Method? {
        runCatching {
            AppleReflection.findMethod(
                clazz,
                "scrollToPositionWithOffset",
                parameterCount = 2,
            )
        }.getOrNull()?.takeIf { method ->
            method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                )
        }?.let { return it }

        return generateSequence(clazz) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { method ->
                Modifier.isPublic(method.modifiers) &&
                    !Modifier.isStatic(method.modifiers) &&
                    !Modifier.isFinal(method.modifiers) &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(
                        arrayOf(
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                        )
                    )
            }
            ?.apply { isAccessible = true }
    }

    private fun appleLyricsHasNativeRenderEffect(view: View): Boolean =
        runCatching {
            AppleReflection.call(view, "getRenderEffect") != null
        }.recoverCatching {
            AppleReflection.field(view, "mRenderEffect") != null
        }.getOrDefault(false)

    private fun applyAppleLyricsBlur(view: View, mode: Int, radiusPx: Int) {
        appleLyricsBlurredViews.add(view)
        when (mode) {
            AppleLyricsBlurPolicy.NATIVE -> {
                clearAppleLyricsHyperOsBlur(view)
                applyAppleLyricsNativeBlur(view, radiusPx)
            }
            AppleLyricsBlurPolicy.ADVANCED_MATERIAL -> {
                view.setRenderEffect(null)
                if (!applyAppleLyricsHyperOsBlur(view, radiusPx)) {
                    clearAppleLyricsBlur(view)
                }
            }
            else -> clearAppleLyricsBlur(view)
        }
    }

    private fun applyAppleLyricsNativeBlur(view: View, radiusPx: Int) {
        view.setRenderEffect(
            radiusPx.takeIf { it > 0 }?.let { radius ->
                RenderEffect.createBlurEffect(
                    radius.toFloat(),
                    radius.toFloat(),
                    Shader.TileMode.CLAMP,
                )
            }
        )
    }

    private fun applyAppleLyricsHyperOsBlur(view: View, radiusPx: Int): Boolean {
        val methods = appleLyricsHyperOsMethods(view)
        val setSelfBlur = methods.setSelfBlur ?: return false
        return runCatching {
            methods.setSelfBlurType?.invoke(view, APPLE_LYRICS_HYPER_OS_SELF_BLUR_TYPE)
            setSelfBlur.invoke(view, radiusPx.coerceAtLeast(0), ArrayList<Any>())
            true
        }.getOrElse {
            false
        }
    }

    private fun clearAppleLyricsBlur(view: View) {
        view.setRenderEffect(null)
        clearAppleLyricsHyperOsBlur(view)
    }

    private fun clearAppleLyricsBlurForRecycler(recyclerView: View) {
        (recyclerView as? ViewGroup)?.let { container ->
            repeat(container.childCount) { index ->
                container.getChildAt(index)?.let(::clearAppleLyricsBlur)
            }
        }
        val affectedViews = synchronized(appleLyricsBlurredViews) {
            appleLyricsBlurredViews.toList().also { appleLyricsBlurredViews.clear() }
        }
        affectedViews.forEach(::clearAppleLyricsBlur)
    }

    private fun clearAppleLyricsHyperOsBlur(view: View) {
        val methods = appleLyricsHyperOsMethods(view)
        runCatching {
            methods.setSelfBlur?.invoke(view, 0, ArrayList<Any>())
        }
    }

    private fun appleLyricsHyperOsMethods(view: View): AppleLyricsHyperOsMethods =
        synchronized(appleLyricsHyperOsMethods) {
            appleLyricsHyperOsMethods.getOrPut(view.javaClass) {
                fun findPublicMethod(name: String, vararg parameterTypes: Class<*>): Method? =
                    runCatching {
                        view.javaClass.getMethod(name, *parameterTypes).apply {
                            isAccessible = true
                        }
                    }.getOrNull()

                AppleLyricsHyperOsMethods(
                    setSelfBlur = findPublicMethod(
                        "setMiSelfBlur",
                        Int::class.javaPrimitiveType!!,
                        ArrayList::class.java,
                    ),
                    setSelfBlurType = findPublicMethod(
                        "setMiSelfBlurType",
                        Int::class.javaPrimitiveType!!,
                    ),
                )
            }
        }


    fun isAppleLyricsRecyclerView(recyclerView: Any): Boolean {
        val recyclerViewAsView = recyclerView as? View ?: return false
        appleLyricsRecyclerViewClassifications[recyclerViewAsView]?.let { return it }
        val resourceEntryName = runCatching {
            recyclerViewAsView.resources.getResourceEntryName(recyclerViewAsView.id)
        }.getOrNull()
        val isLyrics = resourceEntryName == "lyrics_main_content" ||
            isAppleLyricsRecyclerAdapter(appleRecyclerAdapter(recyclerView))
        appleLyricsRecyclerViewClassifications[recyclerViewAsView] = isLyrics
        return isLyrics
    }

    fun resolveAppleLyricsRecyclerView(fragment: Any): Any? {
        runCatching {
            AppleReflection.call(
                fragment,
                lyricsUiMember(AppleMusicRuntimeMember.LYRICS_UI_RECYCLER_VIEW_METHOD),
            )
        }.getOrNull()?.takeIf(::isAppleRecyclerViewInstance)?.let { return it }

        runCatching {
            val binding = AppleReflection.field(
                fragment,
                lyricsUiMember(AppleMusicRuntimeMember.LYRICS_UI_BINDING_FIELD),
            ) ?: return@runCatching null
            AppleReflection.field(
                binding,
                lyricsUiMember(AppleMusicRuntimeMember.LYRICS_UI_BINDING_RECYCLER_FIELD),
            )
        }.getOrNull()?.takeIf(::isAppleRecyclerViewInstance)?.let { recyclerView ->
            ProviderLogger.debug(
                "Apple Music 歌词 RecyclerView 已解析: source=PlayerLyricsViewFragment.i0.a0"
            )
            return recyclerView
        }

        instanceFieldValues(fragment)
            .filter(::isDataBindingInstance)
            .firstNotNullOfOrNull(::directRecyclerViewField)
            ?.let { recyclerView ->
                ProviderLogger.debug(
                    "Apple Music 歌词 RecyclerView 已解析: source=databinding_scan"
                )
                return recyclerView
            }
        return directRecyclerViewField(fragment)
    }

    private fun directRecyclerViewField(instance: Any): Any? =
        generateSequence<Class<*>>(instance.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { isAppleRecyclerViewClass(it.type) }
            .firstNotNullOfOrNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(instance)
                }.getOrNull()?.takeIf(::isAppleRecyclerViewInstance)
            }

    private fun isDataBindingInstance(instance: Any): Boolean =
        generateSequence<Class<*>>(instance.javaClass) { it.superclass }
            .any { it.name == "androidx.databinding.ViewDataBinding" }

    private fun instanceFieldValues(instance: Any): Sequence<Any> =
        generateSequence<Class<*>>(instance.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filterNot { Modifier.isStatic(it.modifiers) }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(instance)
                }.getOrNull()
            }

    fun isAppleRecyclerViewInstance(value: Any): Boolean =
        isAppleRecyclerViewClass(value.javaClass)

    fun isAppleRecyclerViewClass(clazz: Class<*>): Boolean =
        generateSequence(clazz) { it.superclass }
            .any { it.name == "androidx.recyclerview.widget.RecyclerView" }

    fun appleRecyclerAdapter(recyclerView: Any): Any? =
        runCatching { AppleReflection.call(recyclerView, "getAdapter") }.getOrNull()

    fun appleRecyclerAdapterItemCount(adapter: Any): Int =
        runCatching {
            AppleReflection.call(adapter, "getItemCount") as? Number
        }.recoverCatching {
            AppleReflection.call(
                adapter,
                lyricsAdapterMember(
                    adapter,
                    AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_COUNT_METHOD,
                ),
            ) as? Number
        }.getOrNull()?.toInt()?.coerceAtLeast(0) ?: 0

    /**
     * Invoke the host's verified two-int scroll method without relying on a
     * decompiler/display name. Apple Music's lyrics layout manager is loaded by
     * the host ClassLoader and may expose an obfuscated method name.
     */
    fun appleLyricsScrollToPositionWithOffset(
        layoutManager: Any,
        position: Int,
        offset: Int,
    ): String? {
        val method = findAppleLyricsScrollToPositionWithOffsetMethod(layoutManager.javaClass)
            ?: return null
        return runCatching {
            method.invoke(layoutManager, position, offset)
            "${method.declaringClass.name}#${method.name}"
        }.getOrNull()
    }

    fun appleRecyclerNotifyDataSetChanged(adapter: Any) {
        runCatching {
            AppleReflection.call(adapter, "notifyDataSetChanged")
        }.recoverCatching {
            AppleReflection.call(
                adapter,
                lyricsAdapterMember(
                    adapter,
                    AppleMusicRuntimeMember.LYRICS_ADAPTER_NOTIFY_DATA_CHANGED_METHOD,
                ),
            )
        }.getOrThrow()
    }

    fun hookAppleLyricsBlurEffect() {
        runCatching {
            val recyclerClass = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
            val passiveLifecycleMethods = listOf(
                "setAdapter" to 1,
                "onAttachedToWindow" to 0,
                "onLayout" to 5,
                "onChildAttachedToWindow" to 1,
            )
            val installedHooks = mutableListOf<String>()
            val failedHooks = mutableListOf<String>()
            passiveLifecycleMethods.forEach { (name, parameterCount) ->
                runCatching {
                    val method = AppleReflection.findMethod(
                        recyclerClass,
                        name,
                        parameterCount = parameterCount,
                    )
                    hookRegistrar.installHook(method, after = { chain, _ ->
                        if (name == "setAdapter") {
                            (chain.thisObject as? View)?.let(
                                appleLyricsRecyclerViewClassifications::remove
                            )
                        }
                        chain.thisObject
                            ?.takeIf(::isAppleLyricsRecyclerView)
                            ?.let { recyclerView ->
                                if (name == "setAdapter") {
                                    resetAppleLyricsBlurRuntimeState(recyclerView)
                                } else {
                                    if (name == "onLayout") {
                                        completeAppleLyricsProgrammaticRecenter(recyclerView)
                                    }
                                    scheduleAppleLyricsBlur(recyclerView)
                                }
                            }
                    })
                    installedHooks += "RecyclerView.$name"
                }.onFailure { throwable ->
                    failedHooks += "RecyclerView.$name:${throwable.javaClass.simpleName}"
                }
            }

            runCatching {
                val method = AppleReflection.findMethod(
                    recyclerClass,
                    "onScrolled",
                    parameterCount = 2,
                )
                hookRegistrar.installHook(method, after = { chain, _ ->
                    chain.thisObject
                        ?.takeIf(::isAppleLyricsRecyclerView)
                        ?.let { recyclerView ->
                            suspendAppleLyricsBlurForScroll(recyclerView)
                            scheduleAppleLyricsBlur(
                                recyclerView = recyclerView,
                                delayMs = APPLE_LYRICS_IDLE_RECHECK_DELAY_MS,
                            )
                        }
                })
                installedHooks += "RecyclerView.onScrolled"
            }.onFailure { throwable ->
                failedHooks += "RecyclerView.onScrolled:${throwable.javaClass.simpleName}"
            }

            runCatching {
                val linearLayoutManagerClass = classLoader.loadClass(
                    "androidx.recyclerview.widget.LinearLayoutManager"
                )
                val method = findAppleLyricsScrollToPositionWithOffsetMethod(
                    linearLayoutManagerClass
                ) ?: error("scrollToPositionWithOffset method not found")
                hookRegistrar.installHook(method, after = { chain, _ ->
                    val targetPosition = (chain.args.firstOrNull() as? Number)?.toInt()
                        ?: return@installHook
                    onAppleLyricsProgrammaticRecenterRequested(
                        layoutManager = chain.thisObject,
                        targetPosition = targetPosition,
                    )
                })
                installedHooks += "LinearLayoutManager.${method.name}"
            }.onFailure { throwable ->
                failedHooks +=
                    "LinearLayoutManager.scrollToPositionWithOffset:" +
                        throwable.javaClass.simpleName
            }

            listOf("onScrollStateChanged", "setScrollState").forEach { name ->
                runCatching {
                    val method = AppleReflection.findMethod(
                        recyclerClass,
                        name,
                        parameterCount = 1,
                    )
                    hookRegistrar.installHook(method, after = { chain, _ ->
                        val scrollState = (chain.args.firstOrNull() as? Number)?.toInt()
                            ?: return@installHook
                        chain.thisObject
                            ?.takeIf(::isAppleLyricsRecyclerView)
                            ?.let { recyclerView ->
                                onAppleLyricsScrollStateChanged(recyclerView, scrollState)
                            }
                    })
                    installedHooks += "RecyclerView.$name"
                }.onFailure { throwable ->
                    failedHooks += "RecyclerView.$name:${throwable.javaClass.simpleName}"
                }
            }

            hookResolver.resolveClasses(AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER)
                .forEach { resolvedClass ->
                val adapterClassName = resolvedClass.target.className
                runCatching {
                    val adapterClass = resolvedClass.clazz
                    val activeLinesMethodName = resolvedClass.target.runtimeMemberName(
                        AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_LINES_UPDATE_METHOD,
                    )
                    val method = AppleReflection.findMethod(
                        adapterClass,
                        activeLinesMethodName,
                        parameterCount = 3,
                    )
                    hookRegistrar.installHook(method, after = { chain, _ ->
                        onAppleLyricsActiveLinesUpdated(chain.thisObject)
                    })
                    installedHooks += "$adapterClassName.$activeLinesMethodName"
                }.onFailure { throwable ->
                    failedHooks += "$adapterClassName.activeLines:${throwable.javaClass.simpleName}"
                }
            }
            check(installedHooks.isNotEmpty()) { "No RecyclerView lifecycle method was hookable" }
            if (BuildConfig.DEBUG) {
                ProviderLogger.diagnostic(
                    "Apple Music 歌词模糊 Hook 明细: " +
                        "installed=$installedHooks, failed=$failedHooks"
                )
            }
            ProviderLogger.debug("Apple Music 歌词模糊 Hook 已安装: methods=${installedHooks.size}")
        }.onFailure {
            ProviderLogger.error("Apple Music 歌词模糊 Hook 安装失败", it)
        }
    }


}
