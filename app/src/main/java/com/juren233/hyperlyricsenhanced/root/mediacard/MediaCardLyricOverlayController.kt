/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard

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
import com.juren233.hyperlyricsenhanced.root.mediacard.transition.MediaCardHostBinding
import com.juren233.hyperlyricsenhanced.root.mediacard.transition.RootLayoutParamsFactory
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

    /**
     * Strict notification-card entry. A concrete SystemUI controller and a verified
     * HostBinding are mandatory; this prevents a player-only session from accepting
     * callbacks after controller replacement or SystemUI reload.
     */
    fun bindNotificationCard(
        controller: Any,
        player: ViewGroup,
        album: View?,
        actions: List<View>,
        progress: View,
        elapsedTime: View? = null,
        totalTime: View? = null,
        title: TextView,
        packageName: String?,
        hostBinding: MediaCardHostBinding,
        background: View? = null,
        outer: View? = null,
    ) {
        bind(
            controller = controller,
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
            hostBinding = hostBinding,
        )
    }

    /**
     * Rebinds a live player to a concrete controller and verified HostBinding. The
     * old session is invalidated before the new identity is accepted.
     */
    fun rebindControllerIdentity(
        controller: Any,
        player: ViewGroup,
        hostBinding: MediaCardHostBinding,
    ): Boolean = runOnMainResult {
        if (!hostBinding.isCompatibleWith(player)) return@runOnMainResult false
        val existing = states[player]
        if (existing != null && existing.controller !== controller) {
            existing.restore(immediate = true)
            states.remove(player)
        }
        true
    } ?: false

    /**
     * Creates the B-side host binding from an already verified Agent A binding.
     * No descriptor lookup occurs here; the caller remains responsible for choosing
     * the exact profile/loader and for supplying an evidence-backed height index.
     */
    fun createHostBinding(
        binding: com.juren233.hyperlyricsenhanced.root.mediacard.host.SystemUiMediaHostAdapter.Binding,
        rootLayoutParamsFactory: RootLayoutParamsFactory,
        nativeHeightIndex: Int? = null,
        verifiedNativeTargetHeightFactory: ((ViewGroup, Boolean) -> Int?)? = null,
    ): MediaCardHostBinding? = MediaCardHostBinding.fromVerifiedBinding(
        binding = binding,
        rootLayoutParamsFactory = rootLayoutParamsFactory,
        nativeHeightIndex = nativeHeightIndex,
        verifiedNativeTargetHeightFactory = verifiedNativeTargetHeightFactory,
    )

    /**
     * Legacy player-only notification entry. It is intentionally fail-closed: the
     * old signature cannot establish controller identity or verified host binding.
     * Round 7 must migrate the caller to the strict overload above.
     */
    @Deprecated(
        message = "Pass controller and MediaCardHostBinding; player-only binding is unsafe",
        level = DeprecationLevel.WARNING,
    )
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
        HookLogger.w(TAG, "拒绝 legacy notification bind: controller_or_host_binding_missing")
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
        // Expanded Island has no Full-AOD native callback. Its concrete owner (or
        // the player when no owner object exists) still gives the per-surface session
        // a non-zero identity without borrowing notification/AOD state.
        bind(
            controller = owner ?: player,
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
            hostBinding = null,
        )
    }

    fun unbind(player: ViewGroup, immediate: Boolean = false) {
        runOnMain {
            states.remove(player)?.restore(immediate)
        }
    }

    /** Kept as a desired-state marker; it never completes or hides a live session. */
    fun transitionNotificationCardToAod(player: ViewGroup) {
        runOnMain { states[player]?.markAodDesired(true) }
    }

    /** Attach a lease only when the caller has an evidence-backed mHeightList index. */
    fun attachNotificationNativeHeightLease(
        player: ViewGroup,
        lease: NativeHeightLease,
        nativeHeightIndex: Int,
    ) {
        runOnMain { states[player]?.attachHeightLease(lease, nativeHeightIndex) }
    }

    /** Uses the evidence-backed index carried by the strict HostBinding. */
    fun attachVerifiedNotificationNativeHeightLease(
        player: ViewGroup,
        lease: NativeHeightLease,
    ): Boolean = runOnMainResult {
        val state = states[player] ?: return@runOnMainResult false
        val index = state.hostBinding?.nativeHeightIndex ?: return@runOnMainResult false
        state.attachHeightLease(lease, index)
        true
    } ?: false

    /**
     * Legacy lease entry is fail-closed because an index-less lease cannot be safely
     * committed to mHeightList. It is retained only for source compatibility.
     */
    @Deprecated(
        message = "Pass the verified mHeightList index",
        level = DeprecationLevel.WARNING,
    )
    fun attachNotificationNativeHeightLease(player: ViewGroup, lease: NativeHeightLease?) {
        HookLogger.w(TAG, "拒绝 index-less native height lease: mHeightList_index_missing")
        lease?.close()
    }

    fun beginNotificationFullAodTransition(
        controller: Any,
        player: ViewGroup,
        targetFullAod: Boolean,
        mode: MediaCardFullAodTransitionMode,
        keepSecondLyric: Boolean,
        listener: Any,
    ): MediaCardTransitionToken? = runOnMainResult {
        states[player]?.takeIf { it.controller === controller }
            ?.beginTransition(targetFullAod, mode, keepSecondLyric, listener)
    }

    /** Legacy player-only begin cannot create a safe session and is fail-closed. */
    @Deprecated(
        message = "Pass controller and non-null listener",
        level = DeprecationLevel.WARNING,
    )
    fun beginNotificationFullAodTransition(
        player: ViewGroup,
        targetFullAod: Boolean,
        mode: MediaCardFullAodTransitionMode,
        keepSecondLyric: Boolean,
        listener: Any? = null,
    ): MediaCardTransitionToken? {
        HookLogger.w(TAG, "拒绝 legacy transition begin: controller_or_listener_missing")
        return null
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
        transitionToken: MediaCardTransitionToken,
    ): Boolean = runOnMainResult {
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
        ) == true
    } ?: false

    fun activeNotificationTransitionToken(player: ViewGroup): MediaCardTransitionToken? =
        states[player]?.activeToken()

    fun cancelNotificationFullAodTransition(
        player: ViewGroup,
        transitionToken: MediaCardTransitionToken,
    ): Boolean = runOnMainResult { states[player]?.cancelTransition(transitionToken) == true } ?: false

    fun completeNotificationFullAodTransition(
        player: ViewGroup,
        transitionToken: MediaCardTransitionToken,
    ): Boolean = runOnMainResult { states[player]?.completeTransition(transitionToken) == true } ?: false

    /**
     * Deprecated global completion is deliberately inert. A callback without its
     * player/token must never settle another card's session.
     */
    @Deprecated(
        message = "Use completeNotificationFullAodTransition(player, token)",
        level = DeprecationLevel.WARNING,
    )
    fun finishNotificationFullAodTransition(targetFullAod: Boolean) {
        HookLogger.w(TAG, "拒绝 legacy global transition finish: scoped_token_missing target=$targetFullAod")
    }

    /**
     * Legacy root-handoff entry is also inert. The unified root remains attached and
     * completion is performed only by the scoped token API.
     */
    @Deprecated(
        message = "Use completeNotificationFullAodTransition(player, token)",
        level = DeprecationLevel.WARNING,
    )
    fun completeNotificationCardToAodTransition(
        player: ViewGroup,
        detachNotificationRoot: Boolean = false,
    ) {
        HookLogger.w(
            TAG,
            "忽略 legacy root handoff: scoped_token_missing detach=$detachNotificationRoot " +
                "player=${System.identityHashCode(player)}",
        )
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
        controller: Any,
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
        hostBinding: MediaCardHostBinding?,
    ) {
        if (surface == Surface.NOTIFICATION_CENTER &&
            (hostBinding == null || !hostBinding.isCompatibleWith(player))
        ) {
            HookLogger.w(TAG, "拒绝 notification bind: verified_host_binding_missing_or_mismatched")
            return
        }
        runOnMain {
            val existing = states[player]
            if (existing != null && existing.controller !== controller) {
                existing.restore(immediate = true)
                states.remove(player)
            }
            val state = states[player] ?: State(player, controller).also { states[player] = it }
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
                hostBinding = hostBinding,
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

    private fun <T> runOnMainResult(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        // Cross-thread callers cannot safely receive a synchronous state result.
        // They must use the native callback's main-thread dispatch boundary.
        mainHandler.post { block() }
        return null
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private enum class Surface {
        NOTIFICATION_CENTER,
        EXPANDED_ISLAND,
    }

    private class State(
        private val player: ViewGroup,
        val controller: Any,
    ) {
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
        var hostBinding: MediaCardHostBinding? = null
        var root: UnifiedMediaLyricRoot? = null
        var baseline: MediaCardAodTransitionBaseline? = null
        var targetCardHeight: Int? = null
        private var outerBaseHeight: Int? = null
        private var outerOriginalLayoutHeight: Int? = null
        private var outerOriginalMinimumHeight: Int? = null
        private var nativeHeightLease: NativeHeightLease? = null
        private var nativeHeightIndex: Int? = null
        private var lastFrame: MediaCardFramePlan? = null
        private var lastGeometry: MediaCardGeometrySnapshot? = null
        private var transitionStartFrame: MediaCardFramePlan? = null
        private var transitionTargetFrame: MediaCardFramePlan? = null

        private val session = MediaCardHostSession(
            MediaCardControllerIdentity.of(controller = controller, player = player),
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

        private fun sameHostBinding(next: MediaCardHostBinding?): Boolean = when {
            hostBinding == null && next == null -> true
            else -> hostBinding?.isEquivalentTo(next) == true
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
            hostBinding: MediaCardHostBinding?,
        ) {
            val changedHost = this.packageName != packageName || this.surface != surface ||
                this.background !== background || this.outer !== outer ||
                this.expandedOwner !== expandedOwner || this.anchor !== anchor ||
                !sameHostBinding(hostBinding)
            if (changedHost) {
                val hadActiveTransition = session.coordinator.activeToken() != null
                if (hadActiveTransition) {
                    removePreDraw()
                    session.rebind(stableFullAod)
                    transitionToken = null
                    transitionStartFrame = null
                    transitionTargetFrame = null
                } else if (root != null) {
                    session.detach()
                    session.attach(null)
                }
                if (root != null) removeRoot()
                baseline = null
                outerBaseHeight = null
                outerOriginalLayoutHeight = null
                outerOriginalMinimumHeight = null
                lastModelKey = null
                lastConfig = null
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
            this.hostBinding = hostBinding
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

        fun attachHeightLease(lease: NativeHeightLease?, index: Int?) {
            if (lease == null || index == null || index < 0) {
                debug("拒绝 native height lease: owner_or_index_missing")
                lease?.close()
                return
            }
            if (session.coordinator.activeToken() != null && nativeHeightLease !== lease) {
                debug("拒绝替换 active native height lease: transition_active")
                lease.close()
                return
            }
            nativeHeightLease = lease
            nativeHeightIndex = index
            session.attachHeightLease(lease)
        }

        fun beginTransition(
            targetFullAod: Boolean,
            mode: MediaCardFullAodTransitionMode,
            keepSecondLyric: Boolean,
            listener: Any,
        ): MediaCardTransitionToken? {
            ensureRoot()
            if (root == null) {
                debug("拒绝开始媒体转场: unified_root_unavailable")
                return null
            }
            captureBaselineIfNeeded()
            val previousFrame = lastFrame ?: MediaCardFramePlan.stable(
                targetFullAod = stableFullAod,
                mode = transitionMode,
                cardHeight = nativeHeight ?: measuredHeight(player),
                keepSecondLyric = transitionKeepSecond,
            )
            val wasActive = session.coordinator.activeToken() != null
            // Always interpolate from the last rendered frame. For a stable begin
            // this is the stable endpoint; for a reversal it is the non-endpoint
            // frame already on screen. Both paths therefore share one commit model.
            transitionStartFrame = previousFrame
            transitionMode = mode
            transitionKeepSecond = keepSecondLyric
            val result = session.begin(
                listener = listener,
                targetFullAod = targetFullAod,
                mode = mode,
            )
            val token = result.token ?: return null
            transitionToken = token
            transitionFraction = 0f
            transitionTargetHeight = resolveTargetCardHeight(targetFullAod)
            transitionTargetFrame = MediaCardFramePlan.stable(
                targetFullAod = targetFullAod,
                mode = mode,
                cardHeight = transitionTargetHeight,
                keepSecondLyric = keepSecondLyric,
            )
            transitionSecondSize = null
            transitionSecondOffset = null
            transitionSecondAlpha = null
            transitionSecondVisible = keepSecondLyric
            installPreDraw()
            syncRootLayoutToGeometry()
            root?.visibility = View.VISIBLE
            applyFrame(makeFrame(0f, null))
            debug(
                "transition_begin token=${token.sessionId}/${token.epoch} target=${token.targetFullAod} " +
                    "reverse=$wasActive startFraction=${previousFrame.fraction} targetHeight=$transitionTargetHeight",
            )
            return token
        }

        fun activeToken(): MediaCardTransitionToken? = transitionToken

        fun applyTransition(
            fraction: Float,
            suppliedToken: MediaCardTransitionToken,
            mainColor: Int,
            secondaryColor: Int,
            targetSecondLineTextSizeSp: Float?,
            targetSecondLineTopOffsetPx: Int?,
            targetSecondLineAlpha: Int?,
            @Suppress("UNUSED_PARAMETER") targetCardHeight: Int?,
            targetSecondLineVisible: Boolean,
        ): Boolean {
            val token = transitionToken ?: return false
            val result = session.coordinator.update(suppliedToken, fraction)
            if (!result.accepted) {
                debug("拒绝过期媒体转场帧: reason=${result.reason}")
                return false
            }
            transitionFraction = fraction.coerceIn(0f, 1f)
            transitionSecondSize = targetSecondLineTextSizeSp
            transitionSecondOffset = targetSecondLineTopOffsetPx
            transitionSecondAlpha = targetSecondLineAlpha
            transitionSecondVisible = targetSecondLineVisible
            transitionMainColor = mainColor
            transitionSecondaryColor = secondaryColor
            syncRootLayoutToGeometry()
            if (transitionTargetHeight == null) {
                transitionTargetHeight = resolveTargetCardHeight(token.targetFullAod)
                transitionTargetFrame = MediaCardFramePlan.stable(
                    targetFullAod = token.targetFullAod,
                    mode = transitionMode,
                    cardHeight = transitionTargetHeight,
                    keepSecondLyric = transitionKeepSecond,
                )
            }
            applyFrame(makeFrame(transitionFraction, transitionTargetStyle()))
            return true
        }

        fun cancelTransition(token: MediaCardTransitionToken): Boolean {
            if (transitionToken !== token) {
                debug("拒绝过期媒体转场取消: reason=stale_token")
                return false
            }
            val result = session.cancel(token)
            if (!result.accepted) {
                debug("拒绝过期媒体转场取消: reason=${result.reason}")
                return false
            }
            removePreDraw()
            transitionToken = null
            stableFullAod = !token.targetFullAod
            suspendedForAod = stableFullAod
            transitionTargetHeight = null
            transitionStartFrame = null
            transitionTargetFrame = null
            nativeHeightLease = null
            nativeHeightIndex = null
            val recovery = MediaCardFramePlan.stable(
                targetFullAod = stableFullAod,
                mode = transitionMode,
                cardHeight = if (stableFullAod) nativeHeight else resolveTargetCardHeight(false),
                keepSecondLyric = transitionKeepSecond,
            )
            applyFrame(recovery)
            if (!stableFullAod) {
                restoreAllNativeVisuals()
                suspendedForAod = false
                refresh()
            }
            return true
        }

        fun completeTransition(token: MediaCardTransitionToken): Boolean {
            if (transitionToken !== token) {
                debug("拒绝过期媒体转场完成: reason=stale_token")
                return false
            }
            val finalHeight = transitionTargetHeight ?: resolveTargetCardHeight(token.targetFullAod)
            val final = MediaCardFramePlan.stable(
                targetFullAod = token.targetFullAod,
                mode = transitionMode,
                cardHeight = finalHeight,
                keepSecondLyric = transitionKeepSecond,
            )
            // The final frame is committed while the lease is still owned by this
            // session. complete() then restores/releases the native snapshot.
            applyFrame(final)
            val result = session.complete(token)
            if (!result.accepted) {
                debug("拒绝过期媒体转场完成: reason=${result.reason}")
                return false
            }
            removePreDraw()
            transitionToken = null
            transitionStartFrame = null
            transitionTargetFrame = null
            transitionTargetHeight = null
            nativeHeightLease = null
            nativeHeightIndex = null
            stableFullAod = token.targetFullAod
            suspendedForAod = token.targetFullAod
            nativeHeight = if (token.targetFullAod) finalHeight else null
            if (token.targetFullAod) {
                root?.visibility = if (final.lyricVisible) View.VISIBLE else View.INVISIBLE
            } else {
                suspendedForAod = false
                restoreAllNativeVisuals()
                refresh()
            }
            debug(
                "transition_complete token=${token.sessionId}/${token.epoch} " +
                    "target=${token.targetFullAod} height=$finalHeight leaseReleased=${result.releaseHeightLease}",
            )
            return true
        }

        /** Desired-state compatibility entry; it never settles an active token. */
        fun markAodDesired(active: Boolean) {
            if (session.coordinator.activeToken() != null) return
            stableFullAod = active
            suspendedForAod = active
            root?.visibility = if (active) View.VISIBLE else root?.visibility ?: View.VISIBLE
            if (!active) refresh()
        }

        fun setAodActive(active: Boolean) = markAodDesired(active)

        fun applyAuthoritativeNativeHeight(height: Int) {
            if (height <= 0) return
            // This legacy path has no verified lease/index and therefore cannot write
            // any host LayoutParams. It only records an observed native value.
            nativeHeight = height
            targetCardHeight = height
            debug("记录 native height=$height;未写入 player 因 lease/index 缺失")
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
            nativeHeightLease = null
            nativeHeightIndex = null
            player.requestLayout()
        }

        fun restore(immediate: Boolean) {
            cancelPreviewRefresh()
            removePreDraw()
            transitionToken?.let { session.cancel(it) }
            transitionToken = null
            session.detach()
            nativeHeightLease = null
            nativeHeightIndex = null
            transitionStartFrame = null
            transitionTargetFrame = null
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
            val current = root
            if (current != null && current.parent === player) {
                syncRootLayoutToGeometry()
                return
            }
            if (current != null) {
                rootLayoutListener?.let(current::removeOnLayoutChangeListener)
                (current.parent as? ViewGroup)?.removeView(current)
                rootLayoutListener = null
                root = null
                // A host subtree rebind invalidates native callbacks. Preserve the
                // content model, release the old lease, and require a new begin.
                removePreDraw()
                session.rebind(stableFullAod)
                nativeHeightLease = null
                nativeHeightIndex = null
                transitionToken = null
                transitionStartFrame = null
                transitionTargetFrame = null
                transitionTargetHeight = null
            }
            val params = createRootLayoutParams() ?: run {
                debug("拒绝创建歌词 root: host_layout_params_unavailable")
                return
            }
            val created = UnifiedMediaLyricRoot(player.context).apply {
                layoutParams = params
                visibility = View.GONE
            }
            root = created
            player.addView(created)
            rootLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                syncRootLayoutToGeometry()
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
            (current.parent as? ViewGroup)?.removeView(current)
            rootLayoutListener = null
            root = null
        }

        private fun createRootLayoutParams(): ViewGroup.LayoutParams? {
            val topGap = dp(TOP_GAP_DP, player)
            hostBinding?.let { binding ->
                if (!binding.isCompatibleWith(player)) return null
                return binding.createRootLayoutParams(player, anchor, topGap)
            }
            if (surface != Surface.EXPANDED_ISLAND) return null
            return FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ).apply {
                topMargin = localBottom(anchor, player) + topGap
                leftMargin = 0
                rightMargin = 0
            }
        }

        private fun syncRootLayoutToGeometry() {
            val currentRoot = root ?: return
            if (currentRoot.parent !== player) {
                ensureRoot()
                return
            }
            val next = createRootLayoutParams() ?: return
            val current = currentRoot.layoutParams
            val currentMargins = current as? ViewGroup.MarginLayoutParams
            val nextMargins = next as? ViewGroup.MarginLayoutParams
            if (currentMargins != null && nextMargins != null &&
                currentMargins.topMargin == nextMargins.topMargin &&
                currentMargins.leftMargin == nextMargins.leftMargin &&
                currentMargins.rightMargin == nextMargins.rightMargin &&
                currentMargins.bottomMargin == nextMargins.bottomMargin &&
                current.javaClass === next.javaClass
            ) return
            currentRoot.layoutParams = next
            lastGeometry = geometry()
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

        private fun findHeader(view: View): View? = hostBinding?.findHeader(view)

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

        private fun resolveTargetCardHeight(targetFullAod: Boolean): Int? {
            if (targetFullAod) {
                hostBinding?.verifiedNativeTargetHeight(player, true)?.let { return it }
            }
            val currentRoot = root ?: return null
            val rootHeight = currentRoot.measuredContentHeight().takeIf { it > 0 } ?: return null
            val currentGeometry = geometry()
            val baseHeight = baseline?.player?.baseHeight ?: currentGeometry.playerHeight
            val baseGeometry = currentGeometry.copy(
                playerHeight = baseHeight,
                cardBottom = baseHeight,
            )
            return geometryResolver.targetCardHeight(
                geometry = baseGeometry,
                lyricHeight = rootHeight,
                topInset = dp(TOP_GAP_DP, player),
                bottomInset = dp(ROOT_BOTTOM_GAP_DP, player),
            )
        }

        private fun transitionTargetStyle(): MediaCardFramePlan =
            (transitionTargetFrame ?: MediaCardFramePlan.stable(
                targetFullAod = transitionToken?.targetFullAod == true,
                mode = transitionMode,
                cardHeight = transitionTargetHeight,
                keepSecondLyric = transitionKeepSecond,
            )).let { target ->
                val alpha = transitionSecondAlpha?.let { (it / 255f).coerceIn(0f, 1f) }
                target.copy(
                    secondaryTextSizeSp = transitionSecondSize ?: target.secondaryTextSizeSp,
                    secondaryTopOffsetPx = transitionSecondOffset ?: target.secondaryTopOffsetPx,
                    secondaryTranslationY = transitionSecondOffset?.toFloat()
                        ?: target.secondaryTranslationY,
                    secondaryAlpha = transitionSecondAlpha ?: target.secondaryAlpha,
                    resolvedSecondaryAlpha = alpha ?: target.resolvedSecondaryAlpha,
                    secondaryVisible = transitionSecondVisible && target.secondaryVisible,
                )
            }

        private fun makeFrame(
            fraction: Float,
            targetStyle: MediaCardFramePlan?,
        ): MediaCardFramePlan {
            val token = transitionToken ?: return lastFrame ?: MediaCardFramePlan.stable(
                targetFullAod = stableFullAod,
                mode = transitionMode,
                cardHeight = targetCardHeight,
                keepSecondLyric = transitionKeepSecond,
            )
            val target = targetStyle ?: transitionTargetStyle()
            val start = transitionStartFrame
            return if (start != null) {
                MediaCardFramePlan.interpolateFrom(start, target, fraction)
            } else {
                FramePlanFactory.create(
                    fraction = fraction,
                    targetFullAod = token.targetFullAod,
                    mode = transitionMode,
                    geometry = geometry(),
                    targetCardHeight = transitionTargetHeight,
                    keepSecondLyric = transitionKeepSecond,
                    secondaryTextSizeSp = transitionSecondSize,
                    secondaryTopOffsetPx = transitionSecondOffset,
                    secondaryAlpha = transitionSecondAlpha,
                    secondaryVisible = transitionSecondVisible,
                    startSecondaryTextSizeSp = root?.visibleSecondaryTextSizeSp(),
                    startSecondaryAlpha = lastFrame?.resolvedSecondaryAlpha ?: 1f,
                    startSecondaryTranslationY = lastFrame?.secondaryTranslationY ?: 0f,
                )
            }
        }

        private fun reconcileStableHeight() {
            if (session.coordinator.activeToken() != null || stableFullAod) return
            val currentRoot = root ?: return
            if (currentRoot.visibility != View.VISIBLE || !currentRoot.hasVisibleContent()) return
            val geometry = geometry()
            val rootHeight = currentRoot.measuredContentHeight().takeIf { it > 0 } ?: return
            val target = resolveTargetCardHeight(false) ?: return
            val base = baseline?.player?.baseHeight ?: geometry.playerHeight
            targetCardHeight = target
            val delta = target - base
            setLayoutHeight(player, target)
            if (background !== player) {
                val backgroundBase = baseline?.background?.baseHeight ?: measuredHeight(background ?: player)
                background?.let { setLayoutHeight(it, backgroundBase + delta) }
            }
            if (outer != null && outer !== player) {
                setLayoutHeight(outer!!, (outerBaseHeight ?: measuredHeight(outer!!)) + delta)
            }
            player.requestLayout()
            background?.requestLayout()
            outer?.requestLayout()
            debug(
                "stable_height target=$target base=$base rootTop=${geometry.lyricTop + dp(TOP_GAP_DP, player)} " +
                    "rootHeight=$rootHeight contentBottom=${geometry.contentBottom}",
            )
        }

        private fun geometry(): MediaCardGeometrySnapshot {
            val value = geometryResolver.resolve(
                player = player,
                anchor = anchor,
                controls = controls,
                progress = progress,
                actions = actionViews,
            )
            lastGeometry = value
            return value
        }

        private fun applyFrame(frame: MediaCardFramePlan) {
            val currentRoot = root ?: return
            if (currentRoot.parent !== player) {
                ensureRoot()
                return
            }
            syncRootLayoutToGeometry()
            currentRoot.applyTextColors(
                mainColor = transitionMainColor ?: 0xFFFFFFFF.toInt(),
                secondaryColor = transitionSecondaryColor ?: 0xFFFFFFFF.toInt(),
            )
            currentRoot.applyFrame(frame)
            applyAlpha(progress, frame.progressAlpha)
            applyAlpha(elapsedTime, frame.elapsedAlpha)
            applyAlpha(totalTime, frame.totalAlpha)
            restoreOrFadeActions(frame.actionsAlpha)
            frame.targetCardHeight?.let { height ->
                targetCardHeight = height
                val lease = nativeHeightLease
                val index = nativeHeightIndex
                if (session.coordinator.activeToken() != null) {
                    if (lease != null && index != null) {
                        val committed = runCatching { lease.setTargetHeight(index, height) }.getOrDefault(false)
                        if (!committed) debug("native height commit rejected index=$index height=$height")
                    } else {
                        debug("native height commit blocked: verified_lease_or_index_missing height=$height")
                    }
                }
            }
            lastFrame = frame
            lastGeometry = geometry()
            if (BuildConfig.DEBUG) {
                debug(
                    "frame fraction=${frame.fraction}, targetFullAod=${frame.targetFullAod}, " +
                        "state=${session.coordinator.state}, rootAlpha=${frame.rootAlpha}, " +
                        "actionsAlpha=${frame.actionsAlpha}, cardTarget=${frame.targetCardHeight}, " +
                        "stable=${frame.stableAfterCommit}, leaseIndex=${nativeHeightIndex}",
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
