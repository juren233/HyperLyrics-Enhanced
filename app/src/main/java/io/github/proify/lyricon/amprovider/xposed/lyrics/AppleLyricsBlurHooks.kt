/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt


internal class AppleLyricsBlurHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val preferences: () -> android.content.SharedPreferences?,
    internal val playbackHooks: () -> ApplePlaybackHooks,
    private val currentFragment: () -> Any?,
) {
    internal companion object {
        const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
        const val APPLE_LYRICS_INITIAL_ANCHOR_Y_FRACTION = 0.22f
        const val APPLE_LYRICS_SCROLL_STATE_IDLE = 0
        const val APPLE_LYRICS_IDLE_RECHECK_DELAY_MS = 96L
        const val APPLE_LYRICS_OUTGOING_RECHECK_DELAY_MS = 16L
        const val APPLE_LYRICS_BEFORE_FIRST_LINE_RECHECK_MAX_MS = 250L
        const val APPLE_LYRICS_HYPER_OS_SELF_BLUR_TYPE = 0
        const val APPLE_LYRICS_BLUR_ANIMATION_DURATION_MS = 300L
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

    /** 滚动态与去重集合的持有者；Hook 发现/安装/事件转交仍由本类负责。 */
    internal val blurState = AppleLyricsBlurState()
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

    internal fun lyricsUiMember(member: AppleMusicRuntimeMember): String =
        lyricsUiTarget.runtimeMemberName(member)

    internal fun lyricsNativeMember(member: AppleMusicRuntimeMember): String =
        lyricsNativeTarget.runtimeMemberName(member)


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
    internal fun appleLyricsBlurAnimationEnabled(): Boolean {
        val prefs = contentUiLanguagePrefs
        return prefs?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_BLUR_ANIMATION,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_BLUR_ANIMATION,
        ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_BLUR_ANIMATION
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
            val isCurrent = blurState.withRuntimeState(target) { state ->
                if (state.pendingApplyBlur !== applyBlur) {
                    false
                } else {
                    state.pendingApplyBlur = null
                    true
                }
            }
            if (!isCurrent) return@Runnable
            applyAppleLyricsBlur(target)
        }
        val previous = blurState.withRuntimeState(recyclerViewAsView) { state ->
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
        val previous = blurState.removeRuntimeState(recyclerViewAsView)?.pendingApplyBlur
        previous?.let(recyclerViewAsView::removeCallbacks)
        clearAppleLyricsBlurForRecycler(recyclerViewAsView)
        scheduleAppleLyricsBlur(recyclerViewAsView)
    }

    private fun suspendAppleLyricsBlurForScroll(recyclerView: Any?) {
        val recyclerViewAsView = recyclerView as? View ?: return
        val becameSuspended = blurState.withRuntimeState(recyclerViewAsView) { state ->
            state.pendingProgrammaticRecenterPosition = null
            if (state.suspendedForScroll) {
                false
            } else {
                state.suspendedForScroll = true
                true
            }
        }
        if (becameSuspended) {
            if (BuildConfig.DEBUG) {
                ProviderLogger.debug(
                    "[LyricsScrollDiag] suspendAppleLyricsBlurForScroll: recycler=${System.identityHashCode(recyclerViewAsView)}"
                )
            }
            clearAppleLyricsBlurForRecycler(recyclerViewAsView)
        }
    }

    private fun onAppleLyricsProgrammaticRecenterRequested(
        layoutManager: Any?,
        targetPosition: Int,
    ) {
        if (layoutManager == null || targetPosition < 0) return
        val recyclerView = blurState.firstRuntimeStateKey { candidate ->
            runCatching {
                AppleReflection.call(candidate, "getLayoutManager")
            }.getOrNull() === layoutManager
        } ?: return
        if (appleLyricsRecyclerScrollState(recyclerView) != APPLE_LYRICS_SCROLL_STATE_IDLE) {
            return
        }
        val marked = blurState.withRuntimeState(recyclerView) { state ->
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
        val completed = blurState.withExistingRuntimeState(recyclerViewAsView) { state ->
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
                state.suspendedForScroll = false
                state.pendingProgrammaticRecenterPosition = null
                completedTarget = targetPosition
                true
            }
        } ?: false
        if (completed) {
            scheduleAppleLyricsBlur(recyclerViewAsView)
            ProviderLogger.debug(
                "Apple Music 歌词滚动恢复完成: " +
                    "target=${completedTarget ?: "none"}, " +
                    "positioned=${positionedChildren.map { it.first }.sorted()}, " +
                    "active=${activePositions.sorted()}, " +
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
        if (BuildConfig.DEBUG) {
            ProviderLogger.debug(
                "[LyricsScrollDiag] onAppleLyricsScrollStateChanged: scrollState=$scrollState (idle=${scrollState == APPLE_LYRICS_SCROLL_STATE_IDLE})"
            )
        }
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
        val recyclerView = blurState.recyclerFor(adapter) ?: return
        val activePositions = appleLyricsActiveAdapterPositions(adapter)
        val changed = blurState.withRuntimeState(recyclerView) { state ->
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
        val state = blurState.withRuntimeState(recyclerView) { it }
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
        blurState.rememberRecycler(adapter, recyclerView)
        blurState.withRuntimeState(recyclerView) { currentState ->
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
                            (chain.thisObject as? View)?.let(blurState::forgetClassification)
                        }
                        chain.thisObject
                            ?.takeIf(::isAppleLyricsRecyclerView)
                            ?.let { recyclerView ->
                                if (name == "setAdapter") {
                                    resetAppleLyricsBlurRuntimeState(recyclerView)
                                } else {
                                    if (name == "onChildAttachedToWindow") {
                                        // 只有复用且带过模糊状态的视图才可能有残留效果或未结束的动画；
                                        // 无状态视图直接跳过，避免每次挂载都执行效果清除
                                        (chain.args.firstOrNull() as? View)?.let { child ->
                                            val hasBlurState =
                                                blurState.containsRuntimeState(child)
                                            if (hasBlurState) {
                                                clearAppleLyricsBlur(child)
                                            }
                                        }
                                    }
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
