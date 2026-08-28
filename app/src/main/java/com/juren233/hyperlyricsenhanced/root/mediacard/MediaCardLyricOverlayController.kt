/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.root.SystemUiEnhancementGate
import com.juren233.hyperlyricsenhanced.root.mediacard.geometry.GeometryResolver
import com.juren233.hyperlyricsenhanced.root.mediacard.geometry.MediaCardGeometrySnapshot
import com.juren233.hyperlyricsenhanced.root.mediacard.geometry.FramePlanFactory
import com.juren233.hyperlyricsenhanced.root.mediacard.island.IslandExpandedMediaElements
import com.juren233.hyperlyricsenhanced.root.mediacard.host.NativeHeightLease
import com.juren233.hyperlyricsenhanced.root.mediacard.transition.MediaCardControllerIdentity
import com.juren233.hyperlyricsenhanced.root.mediacard.transition.MediaCardFramePlan
import com.juren233.hyperlyricsenhanced.root.mediacard.transition.MediaCardHostSession
import com.juren233.hyperlyricsenhanced.root.mediacard.transition.MediaCardSessionState
import com.juren233.hyperlyricsenhanced.root.mediacard.transition.MediaCardTransitionToken
import com.juren233.hyperlyricsenhanced.root.mediacard.view.UnifiedMediaLyricRoot
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Snapshot of one host view's pre-module dimensions. */
internal data class MediaCardAodViewSizeBaseline(
    val view: View,
    val originalLayoutHeight: Int,
    val originalMinimumHeight: Int,
    val baseHeight: Int,
)

internal data class MediaCardAodTransitionBaseline(
    val player: MediaCardAodViewSizeBaseline,
    val background: MediaCardAodViewSizeBaseline?,
    val header: MediaCardAodViewSizeBaseline?,
)

internal enum class MediaCardFullAodTransitionMode {
    DEFAULT,
    PAUSED_RESTORE_NATIVE,
    PAUSED_KEEP_LYRICS,
}

/**
 * Compatibility policy retained for the existing policy contract tests. The runtime
 * controller no longer uses a 42% threshold as an animation clock.
 */
internal object MediaCardFullAodTransitionPolicy {
    const val LEADING_CONTENT_END_FRACTION = 0.42f

    fun leadingContentProgress(fraction: Float): Float {
        val raw = (fraction.coerceIn(0f, 1f) / LEADING_CONTENT_END_FRACTION).coerceIn(0f, 1f)
        return 1f - (1f - raw) * (1f - raw)
    }

    fun shouldRetractWholeLyricRoot(mode: MediaCardFullAodTransitionMode): Boolean =
        mode == MediaCardFullAodTransitionMode.PAUSED_RESTORE_NATIVE

    fun shouldFadeActions(mode: MediaCardFullAodTransitionMode): Boolean =
        mode != MediaCardFullAodTransitionMode.PAUSED_RESTORE_NATIVE
}

/**
 * Owns the single lyric root for one media-card/player instance. SystemUI callbacks
 * may arrive on arbitrary threads, but all View and session mutations are serialized
 * on the main thread. Native fraction remains the only transition clock.
 */
internal object MediaCardLyricOverlayController {
    private const val TAG = "MediaCardLyricOverlayController"
    private const val TOP_GAP_DP = 8f
    private const val ROOT_BOTTOM_GAP_DP = 8f
    private const val PREVIEW_REFRESH_INTERVAL_MS = 1_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val geometryResolver = GeometryResolver()
    private val states = Collections.synchronizedMap(WeakHashMap<ViewGroup, State>())
    private var refreshScheduled = false
    private var refreshFrameCallback: Choreographer.FrameCallback? = null

    fun bindNotificationCard(
        player: ViewGroup,
        album: View?,
        actions: List<View>,
        progress: View,
        elapsedTime: View? = null,
        totalTime: View? = null,
        title: TextView,
        packageName: String?,
        background: View? = null,
        outer: View? = null,
    ) {
        bind(
            player = player,
            anchor = album,
            controls = actions.filter { it.visibility != View.GONE }
                .minByOrNull { localTop(it, player) } ?: actions.firstOrNull(),
            actionViews = actions,
            progress = progress,
            elapsedTime = elapsedTime,
            totalTime = totalTime,
            title = title,
            packageName = packageName,
            surface = Surface.NOTIFICATION_CENTER,
            background = background,
            outer = outer ?: player.parent as? View,
            expandedOwner = null,
        )
    }

    fun bindExpandedIslandCard(
        elements: IslandExpandedMediaElements,
        title: TextView,
        packageName: String?,
        outer: View? = null,
        background: View? = null,
        owner: View? = null,
    ) {
        val player = elements.player as? ViewGroup ?: run {
            HookLogger.w(TAG, "展开态媒体卡片歌词跳过: player_not_view_group")
            return
        }
        bind(
            player = player,
            anchor = elements.albumView,
            controls = elements.actionsAnchor,
            actionViews = elements.actionViews,
            progress = elements.seekBar,
            elapsedTime = null,
            totalTime = null,
            title = title,
            packageName = packageName,
            surface = Surface.EXPANDED_ISLAND,
            background = background,
            outer = outer ?: player.parent as? View,
            expandedOwner = owner,
        )
    }

    fun unbind(player: ViewGroup, immediate: Boolean = false) {
        runOnMain {
            states.remove(player)?.restore(immediate)
        }
    }

    /** Kept as a no-op handoff: the unified root is never moved to an overlay. */
    fun transitionNotificationCardToAod(player: ViewGroup) {
        runOnMain {
            states[player]?.markAodStable(true)
        }
    }

    fun attachNotificationNativeHeightLease(player: ViewGroup, lease: NativeHeightLease?) {
        runOnMain { states[player]?.attachHeightLease(lease) }
    }

    fun beginNotificationFullAodTransition(
        player: ViewGroup,
        targetFullAod: Boolean,
        mode: MediaCardFullAodTransitionMode,
        keepSecondLyric: Boolean,
        listener: Any? = player,
    ) {
        runOnMain {
            states[player]?.beginTransition(targetFullAod, mode, keepSecondLyric, listener)
        }
    }

    fun applyNotificationFullAodTransition(
        player: ViewGroup,
        transitionAlpha: Float,
        textColor: Int,
        secondaryTargetColor: Int,
        fraction: Float,
        targetSecondLineTextSizeSp: Float?,
        targetSecondLineTopOffsetPx: Int?,
        targetSecondLineAlpha: Int?,
        targetCardHeight: Int?,
        targetSecondLineVisible: Boolean,
        transitionToken: MediaCardTransitionToken? = null,
    ) {
        runOnMain {
            states[player]?.applyTransition(
                fraction = fraction,
                suppliedToken = transitionToken,
                mainColor = textColor,
                secondaryColor = secondaryTargetColor,
                targetSecondLineTextSizeSp = targetSecondLineTextSizeSp,
                targetSecondLineTopOffsetPx = targetSecondLineTopOffsetPx,
                targetSecondLineAlpha = targetSecondLineAlpha,
                targetCardHeight = targetCardHeight,
                targetSecondLineVisible = targetSecondLineVisible,
            )
        }
    }

    fun activeNotificationTransitionToken(player: ViewGroup): MediaCardTransitionToken? =
        states[player]?.activeToken()

    fun cancelNotificationFullAodTransition(
        player: ViewGroup,
        transitionToken: MediaCardTransitionToken? = null,
    ) {
        runOnMain { states[player]?.cancelTransition(transitionToken) }
    }

    fun finishNotificationFullAodTransition(targetFullAod: Boolean) {
        runOnMain {
            synchronized(states) { states.values.toList() }.forEach { state ->
                state.finishTransition(targetFullAod)
            }
        }
    }

    /**
     * The old API allowed the notification root to be detached and replaced by an
     * AOD root. That operation is intentionally ignored; both surfaces consume the
     * same attached root and the flag is retained only for binary/source callers.
     */
    fun completeNotificationCardToAodTransition(
        player: ViewGroup,
        detachNotificationRoot: Boolean = false,
    ) {
        runOnMain {
            states[player]?.completeHandoff(detachNotificationRoot)
        }
    }

    fun pendingNotificationToAodBaseline(player: ViewGroup): MediaCardAodTransitionBaseline? =
        states[player]?.baseline

    fun restoreNotificationCardAfterFullAod(player: ViewGroup) {
        runOnMain { states[player]?.restoreHostBaseline() }
    }

    /** Native height passed by the host is authoritative; no fallback height is guessed. */
    fun applyNotificationCardNativeFullAodHeight(player: ViewGroup, nativeHeight: Int) {
        runOnMain { states[player]?.applyAuthoritativeNativeHeight(nativeHeight) }
    }

    fun setNotificationCardAodActive(player: ViewGroup, active: Boolean) {
        runOnMain { states[player]?.setAodActive(active) }
    }

    fun suspendNotificationCardForAod(player: ViewGroup) {
        runOnMain { states[player]?.setAodActive(true) }
    }

    fun notificationCardTargetHeight(player: ViewGroup): Int? = states[player]?.targetCardHeight

    /** Coalesces high-frequency position snapshots to one main-thread frame. */
    fun refreshAll() {
        runOnMain {
            if (refreshScheduled) return@runOnMain
            refreshScheduled = true
            val callback = Choreographer.FrameCallback {
                refreshScheduled = false
                refreshFrameCallback = null
                synchronized(states) { states.values.toList() }.forEach { it.refresh() }
            }
            refreshFrameCallback = callback
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }

    fun releaseAll() {
        runOnMain {
            refreshFrameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
            refreshFrameCallback = null
            refreshScheduled = false
            val snapshot = synchronized(states) { states.values.toList() }
            states.clear()
            snapshot.forEach { it.restore(immediate = true) }
        }
    }

    fun applyExpandedViewport(owner: View, views: List<View>): Int? {
        var target: Int? = null
        runOnMain {
            synchronized(states) {
                states.values.toList()
                    .filter { it.surface == Surface.EXPANDED_ISLAND && it.expandedOwner === owner }
                    .forEach { state ->
                        state.applyExpandedViewport(views)?.let { value ->
                            target = maxOf(target ?: 0, value)
                        }
                    }
            }
        }
        return target
    }

    private fun bind(
        player: ViewGroup,
        anchor: View?,
        controls: View?,
        actionViews: List<View>,
        progress: View,
        elapsedTime: View?,
        totalTime: View?,
        title: TextView,
        packageName: String?,
        surface: Surface,
        background: View?,
        outer: View?,
        expandedOwner: View?,
    ) {
        runOnMain {
            val state = states[player] ?: State(player).also { states[player] = it }
            state.bindReferences(
                anchor = anchor,
                controls = controls,
                actionViews = actionViews,
                progress = progress,
                elapsedTime = elapsedTime,
                totalTime = totalTime,
                title = title,
                packageName = packageName,
                surface = surface,
                background = background,
                outer = outer,
                expandedOwner = expandedOwner,
            )
            state.refresh()
        }
    }

    private fun configFor(config: MediaCardLyricConfig): LyricPresentationConfig =
        LyricPresentationConfig(
            translationDisplayMode = when (config.translationDisplayMode) {
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION ->
                    LyricTranslationDisplayMode.TRANSLATION
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION ->
                    LyricTranslationDisplayMode.PRONUNCIATION
                else -> LyricTranslationDisplayMode.OFF
            },
            translationFallback = config.translationFallback,
            swapTranslation = config.swapTranslation,
            showNextLyric = true,
            duetLyrics = config.duetLyrics,
            centerNonDuetSong = config.centerNonDuetSong,
            centerGroupVocals = config.centerGroupVocals,
        )

    private fun createRootLayoutParams(player: ViewGroup, anchor: View?): ViewGroup.LayoutParams {
        val calculatedTopMargin = localBottom(anchor, player) + dp(TOP_GAP_DP, player)
        return runCatching {
            val loader = player.javaClass.classLoader ?: return@runCatching null
            val paramsClass = loader.loadClass(
                "androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams",
            )
            val params = paramsClass.getConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).newInstance(0, ViewGroup.LayoutParams.WRAP_CONTENT) as ViewGroup.LayoutParams
            setInt(params, "startToStart", 0)
            setInt(params, "endToEnd", 0)
            setInt(params, "topToTop", 0)
            setInt(params, "topToBottom", -1)
            (params as? ViewGroup.MarginLayoutParams)?.apply {
                this.topMargin = calculatedTopMargin
                leftMargin = 0
                rightMargin = 0
                setMarginStart(0)
                setMarginEnd(0)
            }
            params
        }.getOrNull() ?: FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        ).apply { topMargin = calculatedTopMargin }
    }

    private fun setInt(params: ViewGroup.LayoutParams, name: String, value: Int) {
        runCatching { params.javaClass.getField(name).setInt(params, value) }
    }

    private fun localBottom(view: View?, ancestor: View): Int =
        view?.let { localBounds(it, ancestor)?.bottom } ?: 0

    private fun localTop(view: View, ancestor: View): Int = localBounds(view, ancestor)?.top ?: 0

    private fun localBounds(view: View, ancestor: View): Bounds? {
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
        return Bounds(left, top, left + width, top + height)
    }

    private fun dp(value: Float, view: View): Int =
        (value * view.resources.displayMetrics.density).roundToInt()

    private fun measuredHeight(view: View): Int = view.height.takeIf { it > 0 }
        ?: view.measuredHeight.takeIf { it > 0 }
        ?: view.layoutParams?.height?.takeIf { it > 0 }
        ?: 0

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private enum class Surface {
        NOTIFICATION_CENTER,
        EXPANDED_ISLAND,
    }

    private class State(private val player: ViewGroup) {
        var anchor: View? = null
        var controls: View? = null
        var actionViews: List<View> = emptyList()
        var progress: View? = null
        var elapsedTime: View? = null
        var totalTime: View? = null
        var title: TextView? = null
        var packageName: String? = null
        var surface: Surface = Surface.NOTIFICATION_CENTER
        var background: View? = null
        var outer: View? = null
        var expandedOwner: View? = null
        var root: UnifiedMediaLyricRoot? = null
        var baseline: MediaCardAodTransitionBaseline? = null
        var targetCardHeight: Int? = null
        private var outerBaseHeight: Int? = null
        private var outerOriginalLayoutHeight: Int? = null
        private var outerOriginalMinimumHeight: Int? = null

        private val session = MediaCardHostSession(
            MediaCardControllerIdentity.of(controller = null, player = player),
        )
        private val expandedBases = IdentityHashMap<View, Int>()
        private val originalAlphas = IdentityHashMap<View, Float>()
        private val originalVisibility = IdentityHashMap<View, Int>()
        private var lastModelKey: ModelKey? = null
        private var lastConfig: LyricPresentationConfig? = null
        private var suspendedForAod = false
        private var stableFullAod = false
        private var nativeHeight: Int? = null
        private var transitionMode = MediaCardFullAodTransitionMode.DEFAULT
        private var transitionKeepSecond = false
        private var transitionToken: MediaCardTransitionToken? = null
        private var transitionFraction = 0f
        private var transitionTargetHeight: Int? = null
        private var transitionSecondSize: Float? = null
        private var transitionSecondOffset: Int? = null
        private var transitionSecondAlpha: Int? = null
        private var transitionSecondVisible = false
        private var transitionMainColor: Int? = null
        private var transitionSecondaryColor: Int? = null
        private var currentPreviewText: String? = null
        private var currentPreviewAlignment = LyricPresentationAlignment.CENTER
        private var rootLayoutListener: View.OnLayoutChangeListener? = null
        private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
        private var previewRefreshScheduled = false
        private val previewRefresh = Runnable {
            previewRefreshScheduled = false
            if (states[player] === this && !suspendedForAod) refresh()
        }

        fun bindReferences(
            anchor: View?,
            controls: View?,
            actionViews: List<View>,
            progress: View,
            elapsedTime: View?,
            totalTime: View?,
            title: TextView,
            packageName: String?,
            surface: Surface,
            background: View?,
            outer: View?,
            expandedOwner: View?,
        ) {
            val changedHost = this.packageName != packageName || this.surface != surface ||
                this.background !== background || this.outer !== outer ||
                this.expandedOwner !== expandedOwner || this.anchor !== anchor
            if (changedHost && root != null && session.coordinator.activeToken() == null) {
                removeRoot()
                session.detach()
                session.attach(null)
                baseline = null
                outerBaseHeight = null
                outerOriginalLayoutHeight = null
                outerOriginalMinimumHeight = null
                lastModelKey = null
            }
            this.anchor = anchor
            this.controls = controls
            this.actionViews = actionViews.distinct()
            this.progress = progress
            this.elapsedTime = elapsedTime
            this.totalTime = totalTime
            this.title = title
            this.packageName = packageName
            this.surface = surface
            this.background = background
            this.outer = outer
            this.expandedOwner = expandedOwner
            captureBaselineIfNeeded()
            ensureRoot()
            actionViews.forEach { view ->
                originalAlphas.putIfAbsent(view, view.alpha)
                originalVisibility.putIfAbsent(view, view.visibility)
            }
            listOf(progress, elapsedTime, totalTime).forEach { view ->
                view?.let { originalAlphas.putIfAbsent(it, it.alpha) }
            }
        }

        fun refresh() {
            val prefs = runCatching { HookEntry.instance?.prefs }.getOrNull()
            val config = MediaCardLyricPreferences.read(prefs)
            if (!SystemUiEnhancementGate.isEnabled() || !config.enabled) {
                hideAndRestore()
                return
            }
            if (!player.isAttachedToWindow || player.width <= 0 || measuredHeight(player) <= 0) return
            refreshBaselineIfNeeded()
            if (surface == Surface.NOTIFICATION_CENTER && suspendedForAod) return
            val snapshot = MediaLyricSnapshotStore.global.current()
            if (!packageName.isNullOrBlank() && !snapshot.packageName.isNullOrBlank() &&
                packageName != snapshot.packageName
            ) {
                hideAndRestore()
                return
            }
            val presentationConfig = configFor(config)
            val model = LyricPresentationAssembler.assemble(snapshot, presentationConfig)
            if (model.isEmpty) {
                hideAndRestore()
                return
            }
            val preview = nextSongPreview(snapshot, config)
            currentPreviewText = preview?.first
            currentPreviewAlignment = preview?.second ?: LyricPresentationAlignment.CENTER
            session.acceptPresentation(model)
            if (session.coordinator.activeToken() != null) return
            val modelKey = ModelKey.from(
                model = model,
                config = presentationConfig,
                previewText = currentPreviewText,
                previewAlignment = currentPreviewAlignment,
                sizeSignature = Triple(config.mainTextSize, config.backingTextSize, config.translationTextSize),
            )
            val currentRoot = root ?: return
            if (lastModelKey != modelKey || lastConfig != presentationConfig) {
                currentRoot.bind(
                    model = model,
                    config = presentationConfig,
                    previewText = currentPreviewText,
                    previewAlignment = currentPreviewAlignment,
                    mainTextSizeSp = config.mainTextSize.toFloat(),
                    backingTextSizeSp = config.backingTextSize.toFloat(),
                    translationTextSizeSp = config.translationTextSize.toFloat(),
                )
                lastModelKey = modelKey
                lastConfig = presentationConfig
            }
            currentRoot.visibility = View.VISIBLE
            currentRoot.resetToStable()
            reconcileStableHeight()
            schedulePreviewRefresh(config.nextSongPreview)
        }

        private fun nextSongPreview(
            snapshot: MediaLyricSnapshot,
            config: MediaCardLyricConfig,
        ): Pair<String, LyricPresentationAlignment>? {
            if (!config.nextSongPreview) return null
            val packageName = snapshot.packageName?.takeIf { it.isNotBlank() } ?: return null
            val duration = snapshot.song?.durationMs?.takeIf { it > 0L } ?: return null
            val lastLyricStart = listOfNotNull(
                snapshot.current?.beginMs,
                snapshot.next?.beginMs,
                snapshot.nextNext?.beginMs,
            ).maxOrNull() ?: -1L
            if (!MediaCardLyricContentPolicy.shouldShowNextSongPreview(
                    enabled = true,
                    positionMs = snapshot.positionMs,
                    durationMs = duration,
                    hasActualLyrics = snapshot.current != null,
                    lastLyricStartMs = lastLyricStart,
                )
            ) return null
            val current = runCatching {
                MediaMetadataHelper.getMediaInfo(player.context, packageName, HookLogger)
            }.getOrNull() ?: return null
            val next = runCatching {
                MediaMetadataHelper.getNextMediaInfo(player.context, packageName, current)
            }.getOrNull() ?: return null
            val text = MediaCardLyricContentPolicy.formatNextSongPreview(next.title, next.artist)
                .trim().takeIf { it.isNotEmpty() } ?: return null
            val alignment = when (config.nextSongPreviewPosition) {
                RootConstants.MEDIA_CARD_LYRIC_NEXT_SONG_PREVIEW_POSITION_LEFT ->
                    LyricPresentationAlignment.LEFT
                RootConstants.MEDIA_CARD_LYRIC_NEXT_SONG_PREVIEW_POSITION_RIGHT ->
                    LyricPresentationAlignment.RIGHT
                else -> LyricPresentationAlignment.CENTER
            }
            return text to alignment
        }

        fun attachHeightLease(lease: NativeHeightLease?) {
            session.attachHeightLease(lease)
        }

        fun beginTransition(
            targetFullAod: Boolean,
            mode: MediaCardFullAodTransitionMode,
            keepSecondLyric: Boolean,
            listener: Any?,
        ) {
            if (root == null) ensureRoot()
            captureBaselineIfNeeded()
            if (session.coordinator.activeToken() != null) {
                session.coordinator.recover(
                    snapshotSequence = session.stablePresentation?.snapshotSequence ?: 0L,
                    stableFullAod = stableFullAod,
                )
            }
            transitionMode = mode
            transitionKeepSecond = keepSecondLyric
            val result = session.begin(
                listener = listener,
                targetFullAod = targetFullAod,
                mode = mode,
            )
            transitionToken = result.token
            transitionFraction = 0f
            transitionTargetHeight = if (targetFullAod) null else baseline?.player?.baseHeight
            transitionSecondSize = null
            transitionSecondOffset = null
            transitionSecondAlpha = null
            transitionSecondVisible = keepSecondLyric
            installPreDraw()
            root?.visibility = View.VISIBLE
            applyFrame(
                FramePlanFactory.create(
                    fraction = 0f,
                    targetFullAod = targetFullAod,
                    mode = mode,
                    geometry = geometry(),
                    targetCardHeight = transitionTargetHeight,
                    keepSecondLyric = keepSecondLyric,
                    secondaryTextSizeSp = null,
                    secondaryTopOffsetPx = null,
                    secondaryAlpha = null,
                    secondaryVisible = keepSecondLyric,
                ),
            )
        }

        fun activeToken(): MediaCardTransitionToken? = transitionToken

        fun applyTransition(
            fraction: Float,
            suppliedToken: MediaCardTransitionToken?,
            mainColor: Int,
            secondaryColor: Int,
            targetSecondLineTextSizeSp: Float?,
            targetSecondLineTopOffsetPx: Int?,
            targetSecondLineAlpha: Int?,
            targetCardHeight: Int?,
            targetSecondLineVisible: Boolean,
        ) {
            val token = transitionToken ?: return
            val result = session.coordinator.update(suppliedToken ?: token, fraction)
            if (!result.accepted) {
                debug("拒绝过期媒体转场帧: reason=${result.reason}")
                return
            }
            transitionFraction = fraction.coerceIn(0f, 1f)
            transitionTargetHeight = targetCardHeight ?: transitionTargetHeight
            transitionSecondSize = targetSecondLineTextSizeSp
            transitionSecondOffset = targetSecondLineTopOffsetPx
            transitionSecondAlpha = targetSecondLineAlpha
            transitionSecondVisible = targetSecondLineVisible
            transitionMainColor = mainColor
            transitionSecondaryColor = secondaryColor
            val frame = FramePlanFactory.create(
                fraction = transitionFraction,
                targetFullAod = token.targetFullAod,
                mode = transitionMode,
                geometry = geometry(),
                targetCardHeight = transitionTargetHeight,
                keepSecondLyric = transitionKeepSecond,
                secondaryTextSizeSp = transitionSecondSize,
                secondaryTopOffsetPx = transitionSecondOffset,
                secondaryAlpha = transitionSecondAlpha,
                secondaryVisible = transitionSecondVisible,
            )
            applyFrame(frame)
        }

        fun cancelTransition(suppliedToken: MediaCardTransitionToken? = null) {
            val token = transitionToken ?: return
            if (suppliedToken != null && suppliedToken != token) {
                debug("拒绝过期媒体转场取消: reason=stale_token")
                return
            }
            val result = session.cancel(token)
            if (!result.accepted) {
                debug("拒绝过期媒体转场取消: reason=${result.reason}")
                return
            }
            removePreDraw()
            transitionToken = null
            stableFullAod = !token.targetFullAod
            suspendedForAod = stableFullAod
            transitionTargetHeight = null
            if (stableFullAod) {
                val plan = MediaCardFramePlan.stable(
                    targetFullAod = true,
                    mode = transitionMode,
                    cardHeight = nativeHeight,
                    keepSecondLyric = transitionKeepSecond,
                )
                applyFrame(plan)
            } else {
                restoreAllNativeVisuals()
                root?.resetToStable()
                suspendedForAod = false
                refresh()
            }
        }

        fun finishTransition(targetFullAod: Boolean) {
            val token = transitionToken
            if (token == null) {
                stableFullAod = targetFullAod
                suspendedForAod = targetFullAod
                return
            }
            val final = MediaCardFramePlan.stable(
                targetFullAod = targetFullAod,
                mode = transitionMode,
                cardHeight = if (targetFullAod) transitionTargetHeight else baseline?.player?.baseHeight,
                keepSecondLyric = transitionKeepSecond,
            )
            applyFrame(final)
            val result = session.complete(token)
            if (!result.accepted) {
                debug("拒绝过期媒体转场完成: reason=${result.reason}")
                return
            }
            removePreDraw()
            transitionToken = null
            stableFullAod = targetFullAod
            suspendedForAod = targetFullAod
            nativeHeight = if (targetFullAod) transitionTargetHeight else null
            if (targetFullAod) {
                root?.visibility = View.VISIBLE
                root?.alpha = final.rootAlpha
                restoreOrFadeActions(final.actionsAlpha)
            } else {
                suspendedForAod = false
                root?.resetToStable()
                restoreAllNativeVisuals()
                refresh()
            }
        }

        fun completeHandoff(detachRequested: Boolean) {
            if (detachRequested) {
                debug("忽略 detachNotificationRoot 请求: unified_root_contract")
            }
            stableFullAod = true
            suspendedForAod = true
            root?.visibility = View.VISIBLE
        }

        fun markAodStable(active: Boolean) {
            stableFullAod = active
            suspendedForAod = active
            root?.visibility = View.VISIBLE
            if (!active) refresh()
        }

        fun setAodActive(active: Boolean) {
            suspendedForAod = active
            stableFullAod = active
            root?.visibility = View.VISIBLE
            if (!active) refresh()
        }

        fun applyAuthoritativeNativeHeight(height: Int) {
            if (height <= 0) return
            nativeHeight = height
            targetCardHeight = height
            // The native host remains the height authority. This method only mirrors
            // the explicitly supplied host value for the fallback callback path.
            setLayoutHeight(player, height)
            player.requestLayout()
        }

        fun applyExpandedViewport(views: List<View>): Int? {
            val currentRoot = root ?: return null
            if (currentRoot.visibility != View.VISIBLE || !currentRoot.hasVisibleContent()) return null
            val contentHeight = currentRoot.measuredContentHeight().takeIf { it > 0 } ?: return null
            val geometry = geometry()
            val topInset = (contentHeight - currentRoot.measuredContentHeight()).coerceAtLeast(0)
            val bottomInset = geometry.safeBottomInset
            val requiredExtra = contentHeight + topInset + bottomInset
            var maxTarget = 0
            views.distinct().forEach { view ->
                val base = expandedBases.getOrPut(view) { measuredHeight(view) }
                if (base <= 0) return@forEach
                val target = base + requiredExtra
                setLayoutHeight(view, target)
                view.requestLayout()
                maxTarget = maxOf(maxTarget, target)
            }
            return maxTarget.takeIf { it > 0 }
        }

        fun restoreHostBaseline() {
            baseline?.let { value ->
                restoreBaseline(value.player)
                value.background?.let(::restoreBaseline)
                value.header?.let(::restoreBaseline)
            }
            val outerView = outer
            if (outerView != null && outerView !== player) {
                val view = outerView
                outerOriginalLayoutHeight?.let { height ->
                    view.layoutParams?.let { params ->
                        params.height = height
                        view.layoutParams = params
                    }
                }
                outerOriginalMinimumHeight?.let { view.minimumHeight = it }
            }
            nativeHeight = null
            targetCardHeight = null
            player.requestLayout()
        }

        fun restore(immediate: Boolean) {
            cancelPreviewRefresh()
            removePreDraw()
            transitionToken?.let { session.cancel(it) }
            transitionToken = null
            session.detach()
            restoreAllNativeVisuals()
            baseline?.let { value ->
                restoreBaseline(value.player)
                value.background?.let(::restoreBaseline)
                value.header?.let(::restoreBaseline)
            }
            removeRoot()
            expandedBases.clear()
            if (!immediate) {
                player.requestLayout()
                background?.requestLayout()
                outer?.requestLayout()
            }
        }

        private fun ensureRoot() {
            if (root != null) return
            val created = UnifiedMediaLyricRoot(player.context)
            created.layoutParams = createRootLayoutParams(player, anchor)
            created.visibility = View.GONE
            root = created
            player.addView(created)
            rootLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (session.coordinator.activeToken() == null && !suspendedForAod) {
                    refreshBaselineIfNeeded()
                    reconcileStableHeight()
                }
            }
            created.addOnLayoutChangeListener(rootLayoutListener)
            session.attach(null)
        }

        private fun removeRoot() {
            val current = root ?: return
            rootLayoutListener?.let(current::removeOnLayoutChangeListener)
            if (current.parent === player) player.removeView(current)
            rootLayoutListener = null
            root = null
        }

        private fun refreshBaselineIfNeeded() {
            val currentBaseline = baseline?.player?.baseHeight ?: return
            if (currentBaseline > 0 || session.coordinator.activeToken() != null) return
            baseline = null
            captureBaselineIfNeeded()
        }

        private fun captureBaselineIfNeeded() {
            if (baseline != null) return
            val playerBase = viewBaseline(player)
            val backgroundBase = background?.takeIf { it !== player }?.let(::viewBaseline)
            val header = findHeader(player)
            val headerBase = header?.let(::viewBaseline)
            outer?.takeIf { it !== player }?.let { value ->
                outerBaseHeight = measuredHeight(value)
                outerOriginalLayoutHeight = value.layoutParams?.height
                    ?: ViewGroup.LayoutParams.WRAP_CONTENT
                outerOriginalMinimumHeight = value.minimumHeight
            }
            baseline = MediaCardAodTransitionBaseline(playerBase, backgroundBase, headerBase)
        }

        private fun viewBaseline(view: View): MediaCardAodViewSizeBaseline =
            MediaCardAodViewSizeBaseline(
                view = view,
                originalLayoutHeight = view.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                originalMinimumHeight = view.minimumHeight,
                baseHeight = measuredHeight(view),
            )

        private fun findHeader(view: View): View? {
            if (view.javaClass.name.endsWith("MiuiMediaHeaderView")) return view
            var found: View? = null
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    found = findHeader(view.getChildAt(index))
                    if (found != null) break
                }
            }
            return found
        }

        private fun hideAndRestore() {
            if (session.coordinator.activeToken() != null) return
            cancelPreviewRefresh()
            restoreAllNativeVisuals()
            root?.visibility = View.GONE
            root?.alpha = 1f
            restoreHostBaseline()
            lastModelKey = null
            lastConfig = null
        }

        private fun reconcileStableHeight() {
            val currentRoot = root ?: return
            if (currentRoot.visibility != View.VISIBLE || !currentRoot.hasVisibleContent()) return
            val base = baseline?.player?.baseHeight ?: return
            val geometry = geometry()
            val rootHeight = currentRoot.measuredContentHeight().takeIf { it > 0 } ?: return
            val topInset = (currentRoot.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin
                ?: dp(TOP_GAP_DP, player)
            val bottomInset = baseline?.let { original ->
                (original.player.baseHeight - geometry.contentBottom).coerceAtLeast(0)
            } ?: dp(ROOT_BOTTOM_GAP_DP, player)
            val target = maxOf(base, base + rootHeight + topInset + bottomInset)
            targetCardHeight = target
            val delta = target - base
            setLayoutHeight(player, target)
            if (background !== player) background?.let { setLayoutHeight(it, (baseline?.background?.baseHeight ?: measuredHeight(it)) + delta) }
            if (outer !== null && outer !== player) outer?.let {
                setLayoutHeight(it, (outerBaseHeight ?: measuredHeight(it)) + delta)
            }
            player.requestLayout()
            background?.requestLayout()
            outer?.requestLayout()
        }

        private fun geometry(): MediaCardGeometrySnapshot = geometryResolver.resolve(
            player = player,
            anchor = anchor,
            controls = controls,
            progress = progress,
            actions = actionViews,
        )

        private fun applyFrame(frame: MediaCardFramePlan) {
            val currentRoot = root ?: return
            currentRoot.visibility = View.VISIBLE
            currentRoot.applyTextColors(
                mainColor = transitionMainColor ?: 0xFFFFFFFF.toInt(),
                secondaryColor = transitionSecondaryColor ?: 0xFFFFFFFF.toInt(),
            )
            currentRoot.applyFrame(frame)
            applyAlpha(progress, frame.progressAlpha)
            applyAlpha(elapsedTime, frame.elapsedAlpha)
            applyAlpha(totalTime, frame.totalAlpha)
            restoreOrFadeActions(frame.actionsAlpha)
            if (BuildConfig.DEBUG) {
                debug(
                    "frame fraction=${frame.fraction}, targetFullAod=${frame.targetFullAod}, " +
                        "state=${session.coordinator.state}, rootAlpha=${frame.rootAlpha}, " +
                        "actionsAlpha=${frame.actionsAlpha}, cardTarget=${frame.targetCardHeight}",
                )
            }
        }

        private fun applyAlpha(view: View?, multiplier: Float) {
            view ?: return
            val base = originalAlphas[view] ?: if (view === player) 1f else 1f
            view.alpha = (base * multiplier.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        }

        private fun restoreOrFadeActions(multiplier: Float) {
            actionViews.forEach { view ->
                val base = originalAlphas[view] ?: view.alpha
                view.alpha = (base * multiplier.coerceIn(0f, 1f)).coerceIn(0f, 1f)
                if (multiplier > 0f) {
                    val original = originalVisibility[view]
                    if (original != null && original != View.GONE) view.visibility = original
                }
            }
        }

        private fun restoreAllNativeVisuals() {
            actionViews.forEach { view ->
                originalAlphas[view]?.let { view.alpha = it }
                originalVisibility[view]?.let { view.visibility = it }
            }
            originalAlphas.forEach { (view, alpha) -> view.alpha = alpha }
            root?.resetToStable()
        }

        private fun installPreDraw() {
            if (preDrawListener != null) return
            val listener = ViewTreeObserver.OnPreDrawListener {
                val token = transitionToken
                if (token != null && session.coordinator.activeToken() == token) {
                    applyTransition(
                        fraction = transitionFraction,
                        suppliedToken = token,
                        mainColor = transitionMainColor ?: 0xFFFFFFFF.toInt(),
                        secondaryColor = transitionSecondaryColor ?: 0xFFFFFFFF.toInt(),
                        targetSecondLineTextSizeSp = transitionSecondSize,
                        targetSecondLineTopOffsetPx = transitionSecondOffset,
                        targetSecondLineAlpha = transitionSecondAlpha,
                        targetCardHeight = transitionTargetHeight,
                        targetSecondLineVisible = transitionSecondVisible,
                    )
                }
                true
            }
            preDrawListener = listener
            player.viewTreeObserver.addOnPreDrawListener(listener)
        }

        private fun removePreDraw() {
            val listener = preDrawListener ?: return
            if (player.viewTreeObserver.isAlive) player.viewTreeObserver.removeOnPreDrawListener(listener)
            preDrawListener = null
        }

        private fun schedulePreviewRefresh(enabled: Boolean) {
            if (!enabled) {
                cancelPreviewRefresh()
                return
            }
            if (previewRefreshScheduled) return
            previewRefreshScheduled = true
            mainHandler.postDelayed(previewRefresh, PREVIEW_REFRESH_INTERVAL_MS)
        }

        private fun cancelPreviewRefresh() {
            if (!previewRefreshScheduled) return
            previewRefreshScheduled = false
            mainHandler.removeCallbacks(previewRefresh)
        }

        private fun restoreBaseline(value: MediaCardAodViewSizeBaseline) {
            value.view.layoutParams?.let { params ->
                params.height = value.originalLayoutHeight
                value.view.layoutParams = params
            }
            value.view.minimumHeight = value.originalMinimumHeight
        }

        private fun setLayoutHeight(view: View, height: Int) {
            val params = view.layoutParams ?: return
            if (params.height != height) {
                params.height = height
                view.layoutParams = params
            }
        }

        private fun debug(message: String) {
            if (BuildConfig.DEBUG) HookLogger.i(TAG, message)
        }

        private data class ModelKey(
            val songKey: String?,
            val content: List<String>,
            val config: LyricPresentationConfig,
            val previewText: String?,
            val previewAlignment: LyricPresentationAlignment,
            val sizeSignature: Triple<Int, Int, Int>,
        ) {
            companion object {
                fun from(
                    model: LyricPresentationModel,
                    config: LyricPresentationConfig,
                    previewText: String?,
                    previewAlignment: LyricPresentationAlignment,
                    sizeSignature: Triple<Int, Int, Int>,
                ): ModelKey = ModelKey(
                    songKey = model.songKey,
                    content = model.linesInStableSlotOrder().map {
                        "${it.slot}:${it.role}:${it.alignment}:${it.text}"
                    },
                    config = config,
                    previewText = previewText,
                    previewAlignment = previewAlignment,
                    sizeSignature = sizeSignature,
                )
            }
        }
    }
}
