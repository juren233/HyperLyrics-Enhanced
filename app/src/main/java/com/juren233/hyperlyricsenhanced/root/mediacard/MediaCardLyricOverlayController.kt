/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.lyric.AppleLyricsBlurPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleLyricsBlurRenderer
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.SystemUiEnhancementGate
import com.juren233.hyperlyricsenhanced.lyric.view.SongPreprocessor
import com.juren233.hyperlyricsenhanced.root.mediacard.island.IslandExpandedMediaElements
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.math.roundToInt

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

/**
 * Renders three lyric lines as a real child of the notification-center media card.
 * The whole card is increased in height to reserve the lyric region between the
 * cover and the lower progress/control area; the region itself is not a stretched
 * background or overlay. A ViewGroup overlay cannot participate in the ConstraintLayout
 * height contract used by these cards.
 */
internal object MediaCardLyricOverlayController {
    private const val TAG = "MediaCardLyricOverlayController"
    private const val MEDIA_HEADER_VIEW_CLASS =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView"
    private const val TOP_GAP_DP = 8f
    private const val NOTIFICATION_LYRIC_TOP_GAP_DP = 16f
    private const val BOTTOM_GAP_DP = 8f
    private const val NOTIFICATION_PROGRESS_GAP_DP = 20f
    private const val LINE_HEIGHT_DP = 24f
    private const val LINE_GAP_DP = 4f
    // Keep notification-center lyric/translation spacing identical to lock-screen AOD.
    private const val NOTIFICATION_LYRIC_ROW_GAP_DP = 4f
    // Give separate lyric lines more breathing room without enlarging their
    // translation, pronunciation, or backing-vocal rows inside a lyric group.
    private const val NOTIFICATION_LYRIC_GROUP_GAP_DP = 10f
    private const val MEDIA_CARD_GROUP_ROW_GAP_DP = 2f
    private const val ACTION_TOP_GAP_DP = 12f
    private const val NOTIFICATION_ACTION_TOP_GAP_DP = 0f
    private const val ACTION_BOTTOM_GAP_DP = 12f
    private const val NOTIFICATION_ACTION_BOTTOM_GAP_DP = 14f
    private const val HEIGHT_ANIMATION_MS = 220L
    private const val PREVIEW_REFRESH_INTERVAL_MS = 1_000L
    private const val EXTRA_HEIGHT_DP =
        TOP_GAP_DP + LINE_HEIGHT_DP * 3f + LINE_GAP_DP * 2f + BOTTOM_GAP_DP

    private val mainHandler = Handler(Looper.getMainLooper())
    private val states = Collections.synchronizedMap(
        WeakHashMap<ViewGroup, State>()
    )
    private val pendingNotificationToAodRoots = Collections.synchronizedMap(
        WeakHashMap<ViewGroup, PendingNotificationToAodTransition>()
    )
    private val completedNotificationToAodBaselines = Collections.synchronizedMap(
        WeakHashMap<ViewGroup, MediaCardAodTransitionBaseline>()
    )
    private val notificationCardAodSuspensions = Collections.synchronizedMap(
        WeakHashMap<ViewGroup, Boolean>()
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
        val control = actions.filter { it.visibility != View.GONE }
            .minByOrNull { relativeTop(it, player) }
            ?: actions.firstOrNull()
            ?: return
        bind(
            player = player,
            anchor = album,
            controls = control,
            actionViews = actions,
            progress = progress,
            elapsedTime = elapsedTime,
            totalTime = totalTime,
            title = title,
            packageName = packageName,
            surface = Surface.NOTIFICATION_CENTER,
            background = background,
            outer = outer ?: player.parent as? View,
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
            removePendingNotificationToAodRoot(player)
            states.remove(player)?.restore(immediate = immediate)
        }
    }

    fun transitionNotificationCardToAod(player: ViewGroup) {
        runOnMain {
            removePendingNotificationToAodRoot(player)
            states.remove(player)?.transitionToAod()
        }
    }

    fun beginNotificationFullAodTransition(
        player: ViewGroup,
        targetFullAod: Boolean,
        retainLyrics: Boolean,
    ) {
        runOnMain {
            // Keep late bind/pre-draw callbacks suspended as well; otherwise a
            // rebinding controller could recreate the lyric root while native
            // full AOD is already changing the card height.
            notificationCardAodSuspensions[player] = true
            states[player]?.beginNativeFullAodTransition(
                targetFullAod = targetFullAod,
                retainLyrics = retainLyrics,
            )
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
    ) {
        runOnMain {
            states[player]?.applyNativeFullAodTransition(
                transitionAlpha = transitionAlpha,
                textColor = textColor,
                secondaryTargetColor = secondaryTargetColor,
                fraction = fraction,
                targetSecondLineTextSizeSp = targetSecondLineTextSizeSp,
                targetSecondLineTopOffsetPx = targetSecondLineTopOffsetPx,
                targetSecondLineAlpha = targetSecondLineAlpha,
                targetCardHeight = targetCardHeight,
            )
        }
    }

    fun finishNotificationFullAodTransition() {
        runOnMain {
            synchronized(states) { states.values.toList() }.forEach { state ->
                state.finishNativeFullAodTransition()
            }
        }
    }

    fun completeNotificationCardToAodTransition(player: ViewGroup) {
        runOnMain { removePendingNotificationToAodRoot(player) }
    }

    fun pendingNotificationToAodBaseline(
        player: ViewGroup,
    ): MediaCardAodTransitionBaseline? = pendingNotificationToAodRoots[player]?.baseline

    fun restoreNotificationCardAfterFullAod(player: ViewGroup) {
        runOnMain {
            val baseline = completedNotificationToAodBaselines.remove(player) ?: return@runOnMain
            restoreViewBaseline(baseline.player)
            baseline.background?.takeIf { it.view !== player }?.let(::restoreViewBaseline)
            baseline.header?.let { header ->
                MediaHeaderHeightController.create(
                    view = header.view,
                    baseline = header,
                )?.restoreHeight()
                restoreViewBaseline(header)
            }
            player.requestLayout()
            baseline.background?.view?.requestLayout()
            baseline.header?.view?.requestLayout()
        }
    }

    /**
     * Apply the native full-AOD card height when SystemUI hides the media
     * header without delivering a full-AOD=true callback. This is the paused
     * lock-screen AOD path observed on the device; it must be reversible when
     * the header becomes visible again.
     */
    fun applyNotificationCardNativeFullAodHeight(
        player: ViewGroup,
        nativeHeight: Int,
    ) {
        runOnMain {
            states[player]?.applyNativeFullAodHeight(nativeHeight)
        }
    }

    fun setNotificationCardAodActive(player: ViewGroup, active: Boolean) {
        runOnMain {
            if (active) {
                notificationCardAodSuspensions[player] = true
            } else {
                notificationCardAodSuspensions.remove(player)
            }
            val state = states[player] ?: return@runOnMain
            state.suspendedForLockScreenAod = active
            if (active) {
                // Native full AOD owns the card height during its transition.
                // Hide only the notification lyric content and restore its child
                // constraints; do not restore player/background/header heights.
                state.suspendForLockScreenAod()
            } else {
                refresh(state)
            }
            if (BuildConfig.DEBUG) {
                HookLogger.i(
                    TAG,
                    "通知中心媒体卡片歌词${if (active) "暂停" else "恢复"} full AOD: " +
                        "player=${System.identityHashCode(player).toString(16)}, " +
                        "size=${player.width}x${player.height}/${player.layoutParams?.height}",
                )
            }
        }
    }

    fun notificationCardTargetHeight(player: ViewGroup): Int? =
        states[player]?.targetPlayerHeight

    /**
     * Mark the notification lyric state as paused without restoring any card
     * dimensions. Native full-AOD owns the card height during its transition;
     * restoring the notification baseline here would create the unwanted
     * default-height intermediate frame.
     */
    fun suspendNotificationCardForAod(player: ViewGroup) {
        runOnMain {
            notificationCardAodSuspensions[player] = true
            states[player]?.suspendedForLockScreenAod = true
        }
    }

    fun refreshAll() {
        runOnMain { synchronized(states) { states.values.toList() }.forEach(::refresh) }
    }

    fun releaseAll() {
        runOnMain {
            val pendingRoots = synchronized(pendingNotificationToAodRoots) {
                pendingNotificationToAodRoots.entries.toList()
            }
            pendingNotificationToAodRoots.clear()
            completedNotificationToAodBaselines.clear()
            notificationCardAodSuspensions.clear()
            pendingRoots.forEach { (player, pending) ->
                player.overlay.remove(pending.root)
            }
            val snapshot = synchronized(states) { states.values.toList() }
            states.clear()
            snapshot.forEach(State::restore)
        }
    }

    private fun removePendingNotificationToAodRoot(player: ViewGroup) {
        val pending = pendingNotificationToAodRoots.remove(player) ?: return
        completedNotificationToAodBaselines[player] = pending.baseline
        pending.root.alpha = 1f
        player.overlay.remove(pending.root)
    }

    private fun restoreViewBaseline(baseline: MediaCardAodViewSizeBaseline) {
        val params = baseline.view.layoutParams
        if (params != null && params.height != baseline.originalLayoutHeight) {
            params.height = baseline.originalLayoutHeight
            baseline.view.layoutParams = params
        }
        if (baseline.view.minimumHeight != baseline.originalMinimumHeight) {
            baseline.view.minimumHeight = baseline.originalMinimumHeight
        }
    }

    fun applyExpandedViewport(owner: View, views: List<View>): Int? {
        var targetHeight: Int? = null
        runOnMain {
            synchronized(states) {
                states.values.toList()
                    .filter { it.surface == Surface.EXPANDED_ISLAND && it.expandedOwner === owner }
                    .forEach { state ->
                        state.applyExpandedViewport(views)?.let { target ->
                            targetHeight = maxOf(targetHeight ?: 0, target)
                        }
                    }
            }
        }
        return targetHeight
    }

    private fun bind(
        player: ViewGroup,
        anchor: View?,
        controls: View,
        actionViews: List<View>,
        progress: View,
        elapsedTime: View?,
        totalTime: View?,
        title: TextView,
        packageName: String?,
        surface: Surface,
        background: View?,
        outer: View?,
        expandedOwner: View? = null,
    ) {
        runOnMain {
            val state = states[player] ?: State(player).also { states[player] = it }
            if (
                state.packageName != packageName ||
                state.surface != surface ||
                state.background !== background ||
                state.outer !== outer ||
                state.expandedOwner !== expandedOwner
            ) {
                state.restore()
                state.reset()
            }
            state.anchor = anchor
            state.controls = controls
            state.actionViews = actionViews
            state.progress = progress
            state.elapsedTime = elapsedTime
            state.totalTime = totalTime
            state.title = title
            state.packageName = packageName
            state.surface = surface
            state.background = background
            state.outer = outer
            state.expandedOwner = expandedOwner
            state.suspendedForLockScreenAod = notificationCardAodSuspensions[player] == true
            refresh(state)
        }
    }

    private fun refresh(state: State) {
        if (state.surface == Surface.NOTIFICATION_CENTER && state.suspendedForLockScreenAod) {
            state.cancelPreviewRefresh()
            return
        }
        val player = state.player
        val prefs = runCatching { HookEntry.instance?.prefs }.getOrNull()
        val config = MediaCardLyricPreferences.read(prefs)
        if (!SystemUiEnhancementGate.isEnabled() || !config.enabled) {
            state.cancelPreviewRefresh()
            state.hideAndRestore()
            return
        }
        if (!player.isAttachedToWindow || player.width <= 0 || player.height <= 0) {
            state.cancelPreviewRefresh()
            return
        }
        state.updatePreviewRefresh(config.nextSongPreview)
        if (state.basePlayerHeight == null) state.captureSizes()
        if (state.basePlayerHeight == null) return

        val lyricPackage = LyriconDataBridge.currentLyricPackageName
        if (
            !state.packageName.isNullOrBlank() &&
            !lyricPackage.isNullOrBlank() &&
            state.packageName != lyricPackage
        ) {
            state.hideAndRestore()
            return
        }

        val songHasDuet = LyriconDataBridge.currentSong?.lyrics.orEmpty().any {
            it.isAlignedRight
        }
        val lyricGroups = listOf(
            LyriconDataBridge.currentLyricLine to
                LyriconDataBridge.currentLyric?.trim().orEmpty(),
            LyriconDataBridge.currentNextLyricLine to "",
            LyriconDataBridge.currentNextNextLyricLine to "",
        ).mapIndexed { index, (line, fallbackMain) ->
            MediaCardLyricContentPolicy.lyricGroup(
                line = line,
                fallbackMain = fallbackMain,
                config = config,
                songHasDuet = songHasDuet,
                blurDistance = index,
            )
        }.filter { it.rows.isNotEmpty() }
        val previewGroup = nextSongPreviewGroup(state, config)
        val content = MediaCardLyricContent(
            groups = if (previewGroup != null) {
                buildList {
                    lyricGroups.firstOrNull()?.let(::add)
                    add(previewGroup)
                }
            } else {
                lyricGroups
            }
        )
        if (content.isEmpty()) {
            state.hideAndRestore()
            return
        }

        state.ensureRoot()
        val root = state.root ?: return
        val renderContent = RenderContent(content = content, config = config)
        val contentChanged = state.beginContentUpdate(renderContent)
        root.bind(content, state.title, config)
        val premeasuredRootHeight = if (contentChanged) {
            state.measureNotificationRootTargetHeight()
        } else {
            null
        }
        root.visibility = View.VISIBLE
        root.bringToFront()
        state.applyLyricRegionLayout()
        state.applyHeightIfNeeded(premeasuredRootHeight)
        root.post {
            if (root.visibility == View.VISIBLE) {
                state.applyLyricRegionLayout()
                state.applyHeightIfNeeded()
            }
        }
    }

    private fun nextSongPreviewGroup(
        state: State,
        config: MediaCardLyricConfig,
    ): MediaCardLyricGroupContent? {
        if (!config.nextSongPreview) return null
        val packageName = state.packageName?.takeIf { it.isNotBlank() } ?: return null
        val actualLyrics = LyriconDataBridge.currentSong?.lyrics.orEmpty().filterNot { line ->
            line.metadata?.getBoolean(SongPreprocessor.KEY_TITLE_LINE) == true
        }
        val currentMediaInfo = runCatching {
            MediaMetadataHelper.getMediaInfo(state.player.context, packageName, HookLogger)
        }.getOrNull()
        val duration = LyriconDataBridge.currentSong?.duration?.takeIf { it > 0L }
            ?: currentMediaInfo?.duration
            ?: -1L
        val position = LyriconDataBridge.estimatedPosition()
            ?: LyriconDataBridge.currentPosition
        if (
            !MediaCardLyricContentPolicy.shouldShowNextSongPreview(
                enabled = true,
                positionMs = position,
                durationMs = duration,
                hasActualLyrics = actualLyrics.isNotEmpty(),
                lastLyricStartMs = actualLyrics.maxOfOrNull { it.begin } ?: -1L,
            )
        ) {
            return null
        }
        val current = currentMediaInfo ?: return null
        val next = runCatching {
            MediaMetadataHelper.getNextMediaInfo(
                context = state.player.context,
                packageName = packageName,
                current = current,
            )
        }.getOrNull() ?: return null
        val preview = MediaCardLyricContentPolicy.formatNextSongPreview(
            title = next.title,
            artist = next.artist,
        )
        return preview.takeIf { it.isNotBlank() }?.let {
            MediaCardLyricContentPolicy.previewGroup(
                text = it,
                position = config.nextSongPreviewPosition,
            )
        }
    }

    private fun createConstraintLayoutParams(
        player: ViewGroup,
        anchor: View?,
        horizontalMargin: Int = 0,
        topGapDp: Float = TOP_GAP_DP,
    ): ViewGroup.LayoutParams? = runCatching {
        val anchorId = anchor?.id?.takeIf { it != View.NO_ID && it != 0 }
            ?: return@runCatching null
        val loader = requireNotNull(player.javaClass.classLoader)
        val paramsClass = loader.loadClass(
            "androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams"
        )
        val params = paramsClass.getConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).newInstance(0, ViewGroup.LayoutParams.WRAP_CONTENT) as ViewGroup.LayoutParams
        paramsClass.getField("startToStart").setInt(params, 0)
        paramsClass.getField("endToEnd").setInt(params, 0)
        paramsClass.getField("topToBottom").setInt(params, anchorId)
        (params as ViewGroup.MarginLayoutParams).apply {
            topMargin = dp(topGapDp, player.resources.displayMetrics.density)
            // ConstraintLayout resolves the physical left/right margins for this
            // SystemUI layout. Set both physical and logical margins so the
            // measured bounds, not just the stored start/end fields, match.
            leftMargin = horizontalMargin
            rightMargin = horizontalMargin
            setMarginStart(horizontalMargin)
            setMarginEnd(horizontalMargin)
        }
        params
    }.getOrElse {
        HookLogger.e(TAG, "创建媒体卡片歌词 ConstraintLayout 参数失败", it)
        null
    }

    /**
     * Returns the descendant's untransformed layout offset inside [ancestor].
     * ConstraintLayout resolves child constraints in this coordinate space;
     * screen coordinates are invalid while the notification shade scales or
     * translates the card during expansion/collapse.
     */
    private fun localLayoutOffset(view: View?, ancestor: View): Pair<Int, Int>? {
        var current = view ?: return null
        var x = 0
        var y = 0
        while (current !== ancestor) {
            x += current.left
            y += current.top
            current = current.parent as? View ?: return null
        }
        return x to y
    }

    private fun horizontalGap(view: View?, parent: View): Int =
        localLayoutOffset(view, parent)?.first?.coerceAtLeast(0) ?: 0

    private fun transformedHorizontalGap(view: View?, parent: View): Int {
        if (view == null) return 0
        val viewLocation = IntArray(2)
        val parentLocation = IntArray(2)
        view.getLocationOnScreen(viewLocation)
        parent.getLocationOnScreen(parentLocation)
        return (viewLocation[0] - parentLocation[0]).coerceAtLeast(0)
    }

    private fun relativeBounds(view: View?, parent: View): String {
        if (view == null) return "null"
        val viewLocation = IntArray(2)
        val parentLocation = IntArray(2)
        view.getLocationOnScreen(viewLocation)
        parent.getLocationOnScreen(parentLocation)
        return "x=${viewLocation[0] - parentLocation[0]}," +
            "y=${viewLocation[1] - parentLocation[1]}," +
            "w=${view.width},h=${view.height}"
    }

    private fun relativeHorizontalGaps(view: View?, parent: View): String {
        if (view == null) return "null"
        val viewLocation = IntArray(2)
        val parentLocation = IntArray(2)
        view.getLocationOnScreen(viewLocation)
        parent.getLocationOnScreen(parentLocation)
        val left = viewLocation[0] - parentLocation[0]
        val right = (parent.width - left - view.width).coerceAtLeast(0)
        return "left=$left,right=$right"
    }

    private fun relativeTop(view: View, parent: View): Int {
        val viewLocation = IntArray(2)
        val parentLocation = IntArray(2)
        view.getLocationOnScreen(viewLocation)
        parent.getLocationOnScreen(parentLocation)
        return viewLocation[1] - parentLocation[1]
    }

    private fun localLayoutBottom(view: View?, parent: View): Int =
        localLayoutOffset(view, parent)?.let { (_, top) ->
            top + measuredViewHeight(requireNotNull(view))
        } ?: 0

    /**
     * Debug-only transformed comparison. Do not use this mixed screen/local
     * value for layout targets: ancestor scaling changes relativeTop() while
     * measuredViewHeight() remains unscaled.
     */
    private fun relativeBottom(view: View?, parent: View): Int {
        if (view == null) return 0
        return relativeTop(view, parent) + measuredViewHeight(view)
    }

    /**
     * Debug-only layout evidence. View.top/left are local to the direct parent,
     * while relativeTop()/relativeBounds() are screen-coordinate based. Keep
     * both values visible so a nested ConstraintLayout cannot be mistaken for
     * the player itself.
     */
    private fun describeCoordinateContext(view: View?, player: View): String {
        if (view == null) return "null"
        val viewLocation = IntArray(2)
        val playerLocation = IntArray(2)
        view.getLocationOnScreen(viewLocation)
        player.getLocationOnScreen(playerLocation)
        val directParent = view.parent as? View
        val parentLocation = directParent?.let { IntArray(2).also(it::getLocationOnScreen) }
        val parentDescription = directParent?.let { parent ->
            val parentScreen = parentLocation ?: intArrayOf(0, 0)
            "${parent.javaClass.simpleName}@${viewId(parent)}" +
                "[local=${parent.left},${parent.top},${parent.width}x${parent.height}" +
                ",screen=${parentScreen[0]},${parentScreen[1]}" +
                ",relativePlayer=${parentScreen[0] - playerLocation[0]},${parentScreen[1] - playerLocation[1]}]"
        } ?: "null"
        val chain = buildList {
            var current: View? = view
            repeat(8) {
                val item = current ?: return@repeat
                val location = IntArray(2)
                item.getLocationOnScreen(location)
                add(
                    "${item.javaClass.simpleName}@${viewId(item)}" +
                        "[local=${item.left},${item.top},${item.width}x${item.height}" +
                        ",screen=${location[0]},${location[1]}" +
                        ",relativePlayer=${location[0] - playerLocation[0]},${location[1] - playerLocation[1]}]",
                )
                current = item.parent as? View
            }
        }.joinToString(" <- ")
        return "view=${view.javaClass.simpleName}@${viewId(view)}" +
            ",local=${view.left},${view.top},${view.width}x${view.height}" +
            ",screen=${viewLocation[0]},${viewLocation[1]}" +
            ",relativePlayer=${viewLocation[0] - playerLocation[0]},${viewLocation[1] - playerLocation[1]}" +
            ",directParent=${parentDescription}" +
            ",isDirectPlayer=${directParent === player}" +
            ",chain=$chain"
    }

    private fun viewId(view: View): String =
        view.id.takeIf { it != View.NO_ID && it != 0 }?.toString() ?: "no_id"

    private fun describeTransformContext(view: View, player: View): String {
        val viewLocation = IntArray(2)
        val playerLocation = IntArray(2)
        view.getLocationOnScreen(viewLocation)
        player.getLocationOnScreen(playerLocation)
        val relativeX = viewLocation[0] - playerLocation[0]
        val relativeY = viewLocation[1] - playerLocation[1]
        val parent = view.parent as? View
        val animation = view.animation
        return "${view.javaClass.simpleName}@${viewId(view)}" +
            "[local=${view.left},${view.top},relative=$relativeX,$relativeY" +
            ",localToRelativeOffset=${relativeX - view.left},${relativeY - view.top}" +
            ",translation=${view.translationX},${view.translationY}" +
            ",scale=${view.scaleX},${view.scaleY}" +
            ",rotation=${view.rotation},${view.rotationX},${view.rotationY}" +
            ",pivot=${view.pivotX},${view.pivotY}" +
            ",scroll=${view.scrollX},${view.scrollY}" +
            ",parentScroll=${parent?.scrollX ?: 0},${parent?.scrollY ?: 0}" +
            ",parentTranslation=${parent?.translationX ?: 0f},${parent?.translationY ?: 0f}" +
            ",matrix=${view.matrix.toShortString()}" +
            ",animation=${animation?.javaClass?.name ?: "null"}" +
            ",animationStarted=${animation?.hasStarted() ?: false}" +
            ",animationEnded=${animation?.hasEnded() ?: false}]"
    }

    private fun measuredViewHeight(view: View): Int =
        view.height.takeIf { it > 0 }
            ?: view.measuredHeight.takeIf { it > 0 }
            ?: view.layoutParams?.height?.takeIf { it > 0 }
            ?: 0

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let { field ->
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }

    private fun readDrawableField(view: View, name: String): Drawable? =
        findField(view.javaClass, name)?.let { field ->
            runCatching { field.get(view) as? Drawable }.getOrNull()
        }

    private fun describeDrawable(drawable: Drawable?): String {
        if (drawable == null) return "null"
        return "class=${drawable.javaClass.simpleName},bounds=${drawable.bounds}," +
            "intrinsic=${drawable.intrinsicWidth}x${drawable.intrinsicHeight}"
    }

    private fun describeConstraintLayoutParams(view: View): String {
        val params = view.layoutParams ?: return "lp=null"
        val fields = listOf(
            "topToTop",
            "topToBottom",
            "bottomToTop",
            "bottomToBottom",
            "verticalBias",
        ).mapNotNull { name ->
            runCatching {
                val field = params.javaClass.getField(name)
                "$name=${field.get(params)}"
            }.getOrNull()
        }.joinToString(",")
        val margins = (params as? ViewGroup.MarginLayoutParams)?.let {
            "margins=${it.leftMargin}/${it.topMargin}/${it.rightMargin}/${it.bottomMargin}"
        } ?: "margins=null"
        return "lpClass=${params.javaClass.simpleName},$fields,$margins"
    }

    private fun describeProgressVisual(view: View): String {
        val seekBar = view as? SeekBar
        val progressDrawable = readDrawableField(view, "mProgressDrawable")
        val thumb = readDrawableField(view, "mThumb")
        return "view=${view.javaClass.simpleName}," +
            "bounds=${view.left},${view.top}-${view.right},${view.bottom}," +
            "size=${view.width}x${view.height},measured=${view.measuredWidth}x${view.measuredHeight}," +
            "padding=${view.paddingLeft}/${view.paddingTop}/${view.paddingRight}/${view.paddingBottom}," +
            "seekBar=${seekBar != null}," +
            "progressDrawable={${describeDrawable(progressDrawable)}}," +
            "thumb={${describeDrawable(thumb)}}"
    }

    private fun progressVisualBottom(view: View, parent: View): Int {
        val top = relativeTop(view, parent)
        val drawableBottom = readDrawableField(view, "mProgressDrawable")
            ?.bounds
            ?.bottom
            ?.takeIf { it > 0 }
        return top + (drawableBottom ?: measuredViewHeight(view))
    }

    private fun dp(value: Float, density: Float): Int = (value * density).roundToInt()

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private enum class Surface {
        NOTIFICATION_CENTER,
        EXPANDED_ISLAND,
    }

    private data class RenderContent(
        val content: MediaCardLyricContent,
        val config: MediaCardLyricConfig,
    )

    private data class PendingNotificationToAodTransition(
        val root: MediaCardLyricRoot,
        val baseline: MediaCardAodTransitionBaseline,
    )

    private class State(val player: ViewGroup) {
        var anchor: View? = null
        var controls: View? = null
        var actionViews: List<View> = emptyList()
        var progress: View? = null
        var elapsedTime: View? = null
        var totalTime: View? = null
        var title: TextView? = null
        var packageName: String? = null
        var surface: Surface = Surface.NOTIFICATION_CENTER
        var suspendedForLockScreenAod: Boolean = false
        var background: View? = null
        var outer: View? = null
        var expandedOwner: View? = null
        var root: MediaCardLyricRoot? = null
        private var rootLayoutListener: View.OnLayoutChangeListener? = null
        private var playerLayoutListener: View.OnLayoutChangeListener? = null
        private var preDrawGeometryListener: ViewTreeObserver.OnPreDrawListener? = null
        private var preDrawGeometryPending = false
        private var lastGeometryLogKey: String? = null
        private var lastParentContextKey: String? = null
        var basePlayerHeight: Int? = null
        var baseBackgroundHeight: Int? = null
        var baseOuterHeight: Int? = null
        var originalPlayerLayoutHeight: Int? = null
        var originalBackgroundLayoutHeight: Int? = null
        var originalOuterLayoutHeight: Int? = null
        var originalPlayerMinimumHeight: Int? = null
        var originalBackgroundMinimumHeight: Int? = null
        var originalOuterMinimumHeight: Int? = null
        var targetPlayerHeight: Int? = null
        var targetBackgroundHeight: Int? = null
        var targetOuterHeight: Int? = null
        var backgroundConstraints: BackgroundConstraints? = null
        var headerHeightController: MediaHeaderHeightController? = null
        var reassertPending = false
        private var heightAnimator: ValueAnimator? = null
        private var stableNotificationRootHeight: Int? = null
        private var targetNotificationRootHeight: Int? = null
        private var notificationProgressGapPx: Int = 0
        private var lastRenderContent: RenderContent? = null
        private var nativeFullAodTransitionActive = false
        private var nativeFullAodHeightApplied: Int? = null
        private var previewRefreshScheduled = false
        private val previewRefreshRunnable = Runnable {
            previewRefreshScheduled = false
            val current = synchronized(states) { states[player] }
            if (current === this) refresh(this)
        }
        private val viewportBaseHeights = IdentityHashMap<View, Int>()
        private val viewportTargets = IdentityHashMap<View, Int>()
        private val verticalSnapshots = IdentityHashMap<View, ActionConstraintSnapshot>()
        private val actionTranslationBases = IdentityHashMap<View, Float>()

        fun updatePreviewRefresh(enabled: Boolean) {
            if (!enabled) {
                cancelPreviewRefresh()
                return
            }
            if (!previewRefreshScheduled) {
                previewRefreshScheduled = true
                mainHandler.postDelayed(previewRefreshRunnable, PREVIEW_REFRESH_INTERVAL_MS)
            }
        }

        fun cancelPreviewRefresh() {
            if (previewRefreshScheduled) {
                mainHandler.removeCallbacks(previewRefreshRunnable)
                previewRefreshScheduled = false
            }
        }

        fun suspendForLockScreenAod() {
            cancelPreviewRefresh()
            nativeFullAodTransitionActive = false
            root?.resetFullAodTransitionScale()
            root?.visibility = View.GONE
            // Restore only the child constraints changed by the notification
            // lyric feature. Leave all card heights to native full AOD.
            restoreActionLayout()
            player.requestLayout()
            background?.requestLayout()
            outer?.requestLayout()
        }

        fun captureSizes() {
            if (basePlayerHeight != null) return
            originalPlayerLayoutHeight = player.layoutParams?.height
            originalPlayerMinimumHeight = player.minimumHeight
            basePlayerHeight = measuredHeight(player)
            background?.let { view ->
                originalBackgroundLayoutHeight = view.layoutParams?.height
                originalBackgroundMinimumHeight = view.minimumHeight
                baseBackgroundHeight = measuredHeight(view)
                backgroundConstraints = BackgroundConstraints.create(view)
            }
            outer?.let { view ->
                originalOuterLayoutHeight = view.layoutParams?.height
                originalOuterMinimumHeight = view.minimumHeight
                baseOuterHeight = measuredHeight(view)
                headerHeightController = MediaHeaderHeightController.create(view)
            }
        }

        fun reset() {
            cancelPreviewRefresh()
            anchor = null
            controls = null
            actionViews = emptyList()
            progress = null
            elapsedTime = null
            totalTime = null
            title = null
            packageName = null
            background = null
            outer = null
            expandedOwner = null
            root = null
            basePlayerHeight = null
            baseBackgroundHeight = null
            baseOuterHeight = null
            originalPlayerLayoutHeight = null
            originalBackgroundLayoutHeight = null
            originalOuterLayoutHeight = null
            originalPlayerMinimumHeight = null
            originalBackgroundMinimumHeight = null
            originalOuterMinimumHeight = null
            targetPlayerHeight = null
            targetBackgroundHeight = null
            targetOuterHeight = null
            backgroundConstraints = null
            headerHeightController = null
            reassertPending = false
            heightAnimator?.cancel()
            heightAnimator = null
            stableNotificationRootHeight = null
            targetNotificationRootHeight = null
            notificationProgressGapPx = 0
            lastRenderContent = null
            nativeFullAodTransitionActive = false
            nativeFullAodHeightApplied = null
            rootLayoutListener = null
            playerLayoutListener = null
            preDrawGeometryListener = null
            preDrawGeometryPending = false
            lastGeometryLogKey = null
            lastParentContextKey = null
            viewportBaseHeights.clear()
            viewportTargets.clear()
            verticalSnapshots.clear()
            actionTranslationBases.clear()
        }

        fun ensureRoot() {
            if (root == null) {
                val notificationSurface = surface == Surface.NOTIFICATION_CENTER
                val nextRoot = MediaCardLyricRoot(
                    context = player.context,
                    clipLyricsToBounds = notificationSurface,
                    wrapLyrics = notificationSurface,
                )
                val horizontalMargin = if (notificationSurface) {
                    horizontalGap(anchor, player)
                } else {
                    0
                }
                val params = createConstraintLayoutParams(
                    player = player,
                    anchor = anchor,
                    horizontalMargin = horizontalMargin,
                    topGapDp = if (notificationSurface) {
                        NOTIFICATION_LYRIC_TOP_GAP_DP
                    } else {
                        TOP_GAP_DP
                    },
                ) ?: ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                nextRoot.visibility = View.GONE
                if (notificationSurface) {
                    val listener = View.OnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                        if (
                            bottom - top != oldBottom - oldTop &&
                            nextRoot.visibility == View.VISIBLE &&
                            heightAnimator?.isRunning != true
                        ) {
                            scheduleDynamicRelayout()
                        }
                    }
                    nextRoot.addOnLayoutChangeListener(listener)
                    rootLayoutListener = listener
                    val playerListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        logNotificationGeometry("player_layout")
                        schedulePreDrawGeometryLog()
                    }
                    player.addOnLayoutChangeListener(playerListener)
                    playerLayoutListener = playerListener
                }
                player.addView(nextRoot, params)
                root = nextRoot
            }
            applyRootHorizontalMargin()
        }

        fun beginContentUpdate(content: RenderContent): Boolean {
            val changed = lastRenderContent != content
            if (
                changed &&
                surface == Surface.NOTIFICATION_CENTER &&
                root?.visibility == View.VISIBLE
            ) {
                freezeNotificationRootAtCurrentHeight()
            }
            lastRenderContent = content
            return changed
        }

        private fun freezeNotificationRootAtCurrentHeight() {
            val rootView = root ?: return
            val params = rootView.layoutParams ?: return
            val currentHeight = params.height.takeIf { it > 0 }
                ?: rootView.height.takeIf { it > 0 }
                ?: stableNotificationRootHeight
                ?: return
            if (params.height != currentHeight) {
                params.height = currentHeight
                rootView.layoutParams = params
            }
            if (BuildConfig.DEBUG) {
                HookLogger.i(
                    TAG,
                    "通知中心歌词内容更新前冻结旧高度: root=$currentHeight, " +
                        "player=${player.height}, target=$targetNotificationRootHeight",
                )
            }
        }

        fun measureNotificationRootTargetHeight(): Int? {
            if (surface != Surface.NOTIFICATION_CENTER) return null
            val rootView = root ?: return null
            val params = rootView.layoutParams as? ViewGroup.MarginLayoutParams
            val availableWidth = rootView.width.takeIf { it > 0 }
                ?: (player.width -
                    (params?.marginStart ?: 0) -
                    (params?.marginEnd ?: 0)).coerceAtLeast(1)
            val target = rootView.measureWrappedContentHeight(availableWidth)
            if (BuildConfig.DEBUG && target != null) {
                HookLogger.i(
                    TAG,
                    "通知中心歌词新高度已在绘制前预测量: target=$target, " +
                        "current=${rootView.height}, layout=${rootView.layoutParams?.height}, " +
                        "width=$availableWidth, player=${player.width}x${player.height}",
                )
            }
            return target
        }

        private fun scheduleDynamicRelayout() {
            if (surface != Surface.NOTIFICATION_CENTER || reassertPending) return
            reassertPending = true
            player.post {
                reassertPending = false
                if (root?.visibility == View.VISIBLE) {
                    applyLyricRegionLayout()
                    applyHeightIfNeeded()
                }
            }
        }

        /**
         * A layout-change callback can observe the new local bounds before the
         * current frame's global/render bounds have settled. Apply the visual
         * non-overlap correction once more immediately before drawing, which is
         * the last notification-card-only synchronization point before the user
         * sees the frame.
         */
        private fun schedulePreDrawGeometryLog() {
            if (!BuildConfig.DEBUG || surface != Surface.NOTIFICATION_CENTER) return
            if (preDrawGeometryPending || !player.viewTreeObserver.isAlive) return
            preDrawGeometryPending = true
            lateinit var listener: ViewTreeObserver.OnPreDrawListener
            listener = ViewTreeObserver.OnPreDrawListener {
                if (player.viewTreeObserver.isAlive) {
                    player.viewTreeObserver.removeOnPreDrawListener(listener)
                }
                if (preDrawGeometryListener === listener) {
                    preDrawGeometryListener = null
                    preDrawGeometryPending = false
                }
                if (root?.visibility == View.VISIBLE) {
                    logNotificationGeometry("pre_draw")
                }
                true
            }
            preDrawGeometryListener = listener
            player.viewTreeObserver.addOnPreDrawListener(listener)
        }

        private fun applyRootHorizontalMargin() {
            val rootView = root ?: return
            if (surface != Surface.NOTIFICATION_CENTER) return
            val params = rootView.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val horizontalMargin = horizontalGap(anchor, player)
            if (
                params.leftMargin != horizontalMargin ||
                params.rightMargin != horizontalMargin ||
                params.marginStart != horizontalMargin ||
                params.marginEnd != horizontalMargin
            ) {
                params.leftMargin = horizontalMargin
                params.rightMargin = horizontalMargin
                params.setMarginStart(horizontalMargin)
                params.setMarginEnd(horizontalMargin)
                rootView.layoutParams = params
            }
            if (BuildConfig.DEBUG) {
                HookLogger.i(
                    TAG,
                    "通知中心歌词区域横向内边距已应用: " +
                        "coverCardGap=$horizontalMargin, " +
                        "coverScreenGap=${transformedHorizontalGap(anchor, player)}, " +
                        "rootMargins=left:${params.leftMargin},right:${params.rightMargin}," +
                        "start:${params.marginStart},end:${params.marginEnd}, " +
                        "root=${relativeBounds(rootView, player)}, " +
                        "actualGaps=${relativeHorizontalGaps(rootView, player)}, " +
                        "cover=${relativeBounds(anchor, player)}, " +
                        "player=${player.width}x${player.height}",
                )
            }
        }

        private fun logNotificationGeometry(reason: String) {
            if (!BuildConfig.DEBUG || surface != Surface.NOTIFICATION_CENTER) return
            val progressView = progress ?: return
            val visibleActions = actionViews.filter { it.visibility == View.VISIBLE }
            val parentContextKey = buildString {
                append(describeCoordinateContext(player, player))
                append('|')
                append(describeCoordinateContext(root, player))
                append('|')
                append(describeCoordinateContext(progressView, player))
                visibleActions.forEach { action ->
                    append('|')
                    append(describeCoordinateContext(action, player))
                }
            }
            if (parentContextKey != lastParentContextKey) {
                lastParentContextKey = parentContextKey
                val sharedConstraintParent = visibleActions
                    .mapNotNull { it.parent }
                    .firstOrNull { parent -> visibleActions.all { it.parent === parent } }
                HookLogger.i(
                    TAG,
                    "通知中心媒体卡片父链诊断: " +
                        "player=${describeCoordinateContext(player, player)}, " +
                        "root=${describeCoordinateContext(root, player)}, " +
                        "progress=${describeCoordinateContext(progressView, player)}, " +
                        "actions=${visibleActions.joinToString(" || ") { describeCoordinateContext(it, player) }}, " +
                        "progressDirectParent=${progressView.parent?.javaClass?.name ?: "null"}, " +
                        "actionSharedDirectParent=${sharedConstraintParent?.javaClass?.name ?: "null"}, " +
                        "progressAndActionsShareParent=${visibleActions.any { it.parent === progressView.parent }}, " +
                        "progressLp=${describeConstraintLayoutParams(progressView)}, " +
                        "actionLp=${visibleActions.joinToString(" | ", transform = ::describeConstraintLayoutParams)}",
                )
            }
            val progressTop = relativeTop(progressView, player)
            val progressBottom = progressTop + measuredViewHeight(progressView)
            val visualBottom = progressVisualBottom(progressView, player)
            val rawActionTop = visibleActions.minOfOrNull { relativeTop(it, player) }
            val rawActionBottom = visibleActions.maxOfOrNull {
                relativeTop(it, player) + measuredViewHeight(it)
            }
            val rawLayoutGap = rawActionTop?.let { it - progressBottom }
            val rawVisualGap = rawActionTop?.let { it - visualBottom }
            // Diagnostic logging must never alter layout. Previous candidates
            // wrote translationY from this method using transformed top values
            // plus unscaled heights, which made the logger itself part of the bug.
            val maxCorrectionPx = visibleActions.maxOfOrNull { action ->
                val baseTranslation = actionTranslationBases[action] ?: action.translationY
                (action.translationY - baseTranslation).roundToInt()
            } ?: 0
            val actionTop = visibleActions.minOfOrNull { relativeTop(it, player) }
            val actionBottom = visibleActions.maxOfOrNull {
                relativeTop(it, player) + measuredViewHeight(it)
            }
            val layoutGap = actionTop?.let { it - progressBottom }
            val visualGap = actionTop?.let { it - visualBottom }
            val cardBottomGap = actionBottom?.let { player.height - it }
            val localLayoutGap = visibleActions.minOfOrNull(View::getTop)
                ?.let { it - progressView.bottom }
            val localCardBottomGap = visibleActions.maxOfOrNull(View::getBottom)
                ?.let { player.height - it }
            val key = listOf(
                reason,
                player.width,
                player.height,
                progressTop,
                progressBottom,
                visualBottom,
                actionTop,
                actionBottom,
                rawLayoutGap,
                rawVisualGap,
                layoutGap,
                visualGap,
                cardBottomGap,
                localLayoutGap,
                localCardBottomGap,
                maxCorrectionPx,
                notificationProgressGapPx,
                progressView.paddingTop,
                progressView.paddingBottom,
            ).joinToString("/")
            if (lastGeometryLogKey == key) return
            lastGeometryLogKey = key
            HookLogger.i(
                TAG,
                "通知中心媒体卡片实际几何诊断: reason=$reason, " +
                    "player=${player.width}x${player.height}, " +
                    "root=${relativeBounds(root, player)}, " +
                    "progressRelative=${relativeBounds(progressView, player)}, " +
                    "progressTop=$progressTop,progressBottom=$progressBottom, " +
                    "progressVisualBottom=$visualBottom, " +
                    "rawActionTop=$rawActionTop,rawActionBottom=$rawActionBottom, " +
                    "actionTop=$actionTop,actionBottom=$actionBottom, " +
                    "rawLayoutGap=$rawLayoutGap,rawVisualGap=$rawVisualGap, " +
                    "layoutGap=$layoutGap,visualGap=$visualGap,cardBottomGap=$cardBottomGap, " +
                    "localLayoutGap=$localLayoutGap,localCardBottomGap=$localCardBottomGap, " +
                    "translationCorrectionPx=$maxCorrectionPx, " +
                    "configuredProgressGapPx=$notificationProgressGapPx, " +
                    "actionTopGapDp=$NOTIFICATION_ACTION_TOP_GAP_DP, " +
                    "actionBottomGapDp=$NOTIFICATION_ACTION_BOTTOM_GAP_DP, " +
                    "progressVisual={${describeProgressVisual(progressView)}}, " +
                    "progressTransform={${describeTransformContext(progressView, player)}}, " +
                    "actionTransforms=${visibleActions.joinToString(" | ") {
                        describeTransformContext(it, player)
                    }}, " +
                    "progressLp={${describeConstraintLayoutParams(progressView)}}, " +
                    "actionLp=${visibleActions.joinToString(" | ", transform = ::describeConstraintLayoutParams)}, " +
                    "animatorRunning=${heightAnimator?.isRunning == true}, " +
                    "targetPlayerHeight=$targetPlayerHeight",
            )
        }

        fun applyHeightIfNeeded(premeasuredNotificationRootHeight: Int? = null) {
            if (nativeFullAodTransitionActive) return
            val basePlayer = basePlayerHeight ?: return
            val measuredRootHeight = premeasuredNotificationRootHeight?.takeIf { it > 0 }
                ?: if (
                    surface == Surface.NOTIFICATION_CENTER &&
                    heightAnimator?.isRunning == true
                ) {
                    targetNotificationRootHeight
                } else {
                    root?.measuredHeight?.takeIf { it > 0 }
                }
            // The newly inserted notification lyric root has no reliable natural
            // height until its first real layout. Starting an animation from the
            // fallback height produced the observed two-stage jump (small target,
            // then the real wrapped target) and desynchronized the lower controls.
            if (surface == Surface.NOTIFICATION_CENTER && measuredRootHeight == null) return
            val fallbackRootHeight = if (surface == Surface.NOTIFICATION_CENTER) {
                dp(LINE_HEIGHT_DP, player.resources.displayMetrics.density)
            } else {
                dp(LINE_HEIGHT_DP * 3f + LINE_GAP_DP * 2f, player.resources.displayMetrics.density)
            }
            val rootHeight = measuredRootHeight ?: fallbackRootHeight
            val density = player.resources.displayMetrics.density
            val playerTarget: Int
            val requiredExtra: Int
            if (surface == Surface.NOTIFICATION_CENTER) {
                // Compute the card's required bottom from the actual lyric chain instead
                // of adding the lyric height to the whole native card. This keeps the
                // lower media controls at a stable bottom inset and only grows the card
                // when wrapped lyrics genuinely need more room.
                val anchorBottom = localLayoutBottom(anchor, player)
                val progressHeight = progress?.let(::measuredHeight) ?: 0
                val actionHeight = actionViews
                    .asSequence()
                    .filter { it.visibility != View.GONE }
                    .map(::measuredHeight)
                    .maxOrNull()
                    ?: controls?.let(::measuredHeight)
                    ?: 0
                val chainWithoutProgressGap = anchorBottom +
                    dp(NOTIFICATION_LYRIC_TOP_GAP_DP, density) +
                    rootHeight +
                    progressHeight +
                    dp(NOTIFICATION_ACTION_TOP_GAP_DP, density) +
                    actionHeight +
                    dp(NOTIFICATION_ACTION_BOTTOM_GAP_DP, density)
                val minimumProgressGap = dp(
                    NOTIFICATION_PROGRESS_GAP_DP,
                    density,
                )
                // If the native card has spare space, consume that space above
                // the progress bar. This keeps the progress-to-controls gap and
                // the controls-to-card-bottom gap equal instead of leaving a
                // variable empty area between the progress bar and the buttons.
                val stableTarget = maxOf(
                    basePlayer,
                    chainWithoutProgressGap + minimumProgressGap,
                )
                notificationProgressGapPx = maxOf(
                    minimumProgressGap,
                    stableTarget - chainWithoutProgressGap,
                )
                playerTarget = chainWithoutProgressGap + notificationProgressGapPx
                requiredExtra = (playerTarget - basePlayer).coerceAtLeast(0)
            } else {
                requiredExtra = maxOf(
                    dp(EXTRA_HEIGHT_DP, density),
                    rootHeight + dp(TOP_GAP_DP + BOTTOM_GAP_DP, density),
                )
                playerTarget = basePlayer + requiredExtra
            }
            val heightDelta = (playerTarget - basePlayer).coerceAtLeast(0)
            val outerTarget = if (surface == Surface.EXPANDED_ISLAND) {
                null
            } else {
                baseOuterHeight?.let { base ->
                    if (outer === player || outer == null) playerTarget else base + heightDelta
                }
            }
            val backgroundTarget = if (surface == Surface.EXPANDED_ISLAND) {
                null
            } else baseBackgroundHeight?.let { base ->
                val parent = background?.parent
                when {
                    background == null -> null
                    parent === outer && outerTarget != null -> outerTarget
                    parent === player -> playerTarget
                    else -> base + heightDelta
                }
            }
            val unchangedTarget =
                targetPlayerHeight == playerTarget &&
                    targetOuterHeight == outerTarget &&
                    targetBackgroundHeight == backgroundTarget &&
                    (
                        surface != Surface.NOTIFICATION_CENTER ||
                            targetNotificationRootHeight == rootHeight
                    )
            targetPlayerHeight = playerTarget
            targetOuterHeight = outerTarget
            targetBackgroundHeight = backgroundTarget
            if (surface == Surface.NOTIFICATION_CENTER) {
                targetNotificationRootHeight = rootHeight
            }
            background?.parent?.let { parent ->
                (parent as? View)?.let { backgroundConstraints?.pinToParentTop(it) }
            }
            if (surface == Surface.NOTIFICATION_CENTER) {
                val rootId = root?.id?.takeIf { it != View.NO_ID && it != 0 }
                val progressView = progress
                if (rootId != null && progressView != null) {
                    applyProgressLayout(
                        progressView = progressView,
                        rootId = rootId,
                        progressGapPx = notificationProgressGapPx,
                    )
                }
            }
            // Position/lyric refreshes can arrive while the same height animation
            // is still running. Do not cancel and restart it on every frame; doing so
            // makes the transition feel stiff and can prevent it from reaching the
            // stable bottom inset.
            if (!(unchangedTarget && heightAnimator?.isRunning == true)) {
                animateHeightsTo(
                    playerTarget = playerTarget,
                    backgroundTarget = backgroundTarget,
                    outerTarget = outerTarget,
                    notificationRootTarget = rootHeight.takeIf {
                        surface == Surface.NOTIFICATION_CENTER
                    },
                )
            }
            if (BuildConfig.DEBUG) {
                HookLogger.i(
                    TAG,
                    "媒体卡片歌词高度目标已应用: surface=$surface, " +
                        "player=${player.height}/${player.measuredHeight}/$playerTarget, " +
                        "background=${background?.height}/${background?.measuredHeight}/$backgroundTarget, " +
                        "outer=${outer?.height}/${outer?.measuredHeight}/$outerTarget, " +
                        "root=${root?.measuredWidth}x${root?.measuredHeight}, " +
                        "requiredExtra=$requiredExtra, " +
                        "lyricTopGap=${if (surface == Surface.NOTIFICATION_CENTER) NOTIFICATION_LYRIC_TOP_GAP_DP else TOP_GAP_DP}dp, " +
                        "progressGap=${if (surface == Surface.NOTIFICATION_CENTER) {
                            "${notificationProgressGapPx}px"
                        } else {
                            "${BOTTOM_GAP_DP}dp"
                        }}, " +
                        "actionTopGap=${if (surface == Surface.NOTIFICATION_CENTER) {
                            NOTIFICATION_ACTION_TOP_GAP_DP
                        } else {
                            ACTION_TOP_GAP_DP
                        }}dp, " +
                        "actionBottomGap=${if (surface == Surface.NOTIFICATION_CENTER) {
                            NOTIFICATION_ACTION_BOTTOM_GAP_DP
                        } else {
                            ACTION_BOTTOM_GAP_DP
                        }}dp, " +
                        "coverBottom=${localLayoutBottom(anchor, player)}, " +
                        "coverScreenMixedBottom=${relativeBottom(anchor, player)}, " +
                        "progressBottom=${progress?.bottom}, controlsTop=${controls?.top}",
                )
            }
            if (BuildConfig.DEBUG) {
                player.post { logNotificationGeometry("post_apply_height") }
            }
        }

        fun applyLyricRegionLayout() {
            val rootView = root ?: return
            if (surface != Surface.NOTIFICATION_CENTER) {
                applyActionLayout()
                return
            }
            val rootId = rootView.id.takeIf { it != View.NO_ID && it != 0 } ?: return
            val progressView = progress ?: return
            applyProgressLayout(
                progressView = progressView,
                rootId = rootId,
                progressGapPx = notificationProgressGapPx.takeIf { it > 0 }
                    ?: dp(NOTIFICATION_PROGRESS_GAP_DP, progressView.resources.displayMetrics.density),
            )
            val progressId = progressView.id.takeIf { it != View.NO_ID && it != 0 }
            if (progressId != null) {
                listOfNotNull(elapsedTime, totalTime).forEach { view ->
                    applyDurationLayout(view, progressId)
                }
            } else if (BuildConfig.DEBUG) {
                HookLogger.w(TAG, "通知中心进度条没有有效 ID，无法对齐时长文字")
            }
            applyActionLayout()
            player.requestLayout()
            schedulePreDrawGeometryLog()
            if (BuildConfig.DEBUG) {
                player.post { logNotificationGeometry("post_apply_layout") }
            }
            if (BuildConfig.DEBUG) {
                HookLogger.i(
                    TAG,
                    "通知中心整体卡片已为歌词区域预留空间: " +
                        "lyricTop=${rootView.top}, lyricBottom=${rootView.bottom}, " +
                        "lyric=${relativeBounds(rootView, player)}, " +
                        "progress=${relativeBounds(progressView, player)}, " +
                        "elapsed=${relativeBounds(elapsedTime, player)}, " +
                        "total=${relativeBounds(totalTime, player)}, " +
                        "player=${player.height}/${player.measuredHeight}",
                )
            }
        }

        private fun applyProgressLayout(
            progressView: View,
            rootId: Int,
            progressGapPx: Int,
        ) {
            val params = progressView.layoutParams ?: return
            if (!params.javaClass.name.contains("ConstraintLayout")) {
                if (BuildConfig.DEBUG) {
                    HookLogger.w(
                        TAG,
                        "通知中心歌词区域无法重排进度条: " +
                            "view=${progressView.javaClass.name}, " +
                            "params=${params.javaClass.name}",
                    )
                }
                return
            }
            if (progressView !in verticalSnapshots) {
                verticalSnapshots[progressView] = ActionConstraintSnapshot.capture(params)
            }
            setConstraintField(params, "topToTop", -1)
            setConstraintField(params, "topToBottom", rootId)
            setConstraintField(params, "bottomToTop", -1)
            setConstraintField(params, "bottomToBottom", -1)
            setConstraintField(params, "baselineToBaseline", -1)
            setConstraintField(params, "baselineToTop", -1)
            setConstraintField(params, "baselineToBottom", -1)
            (params as? ViewGroup.MarginLayoutParams)?.topMargin = progressGapPx
            progressView.layoutParams = params
        }

        private fun applyDurationLayout(view: View, progressId: Int) {
            val params = view.layoutParams ?: return
            if (!params.javaClass.name.contains("ConstraintLayout")) {
                if (BuildConfig.DEBUG) {
                    HookLogger.w(
                        TAG,
                        "通知中心时长文字不是 ConstraintLayout.LayoutParams，跳过对齐: " +
                            "view=${view.javaClass.name}, " +
                            "params=${params.javaClass.name}",
                    )
                }
                return
            }
            if (view !in verticalSnapshots) {
                verticalSnapshots[view] = ActionConstraintSnapshot.capture(params)
            }
            // The time labels flank the seek bar. Center them in the seek-bar
            // row instead of giving them the seek-bar row's top edge.
            setConstraintField(params, "topToTop", progressId)
            setConstraintField(params, "topToBottom", -1)
            setConstraintField(params, "bottomToTop", -1)
            setConstraintField(params, "bottomToBottom", progressId)
            setConstraintField(params, "baselineToBaseline", -1)
            setConstraintField(params, "baselineToTop", -1)
            setConstraintField(params, "baselineToBottom", -1)
            (params as? ViewGroup.MarginLayoutParams)?.apply {
                topMargin = 0
                bottomMargin = 0
            }
            view.layoutParams = params
        }

        private fun applyActionLayout() {
            val progressView = progress ?: return
            val progressId = progressView.id.takeIf { it != View.NO_ID && it != 0 } ?: return
            if (actionViews.isEmpty()) return
            actionViews.forEach { action ->
                val params = action.layoutParams ?: return@forEach
                if (!params.javaClass.name.contains("ConstraintLayout")) {
                    if (BuildConfig.DEBUG) {
                        HookLogger.w(
                            TAG,
                            "通知中心播放按钮不是 ConstraintLayout.LayoutParams，跳过下移: " +
                                "view=${action.javaClass.name}, parent=${action.parent?.javaClass?.name}",
                        )
                    }
                    return@forEach
                }
                if (action !in verticalSnapshots) {
                    verticalSnapshots[action] = ActionConstraintSnapshot.capture(params)
                }
                actionTranslationBases.putIfAbsent(action, action.translationY)
                setConstraintField(params, "topToTop", -1)
                setConstraintField(params, "topToBottom", progressId)
                setConstraintField(params, "bottomToTop", -1)
                // Anchor the controls to the card bottom as well as below the
                // progress bar. A bottom-biased chain keeps the visible bottom
                // inset stable while the card grows for wrapped lyrics.
                setConstraintField(params, "bottomToBottom", 0)
                setConstraintField(params, "baselineToBaseline", -1)
                setConstraintField(params, "baselineToTop", -1)
                setConstraintField(params, "baselineToBottom", -1)
                setConstraintFloatField(params, "verticalBias", 1f)
                val actionTopGapDp = if (surface == Surface.NOTIFICATION_CENTER) {
                    NOTIFICATION_ACTION_TOP_GAP_DP
                } else {
                    ACTION_TOP_GAP_DP
                }
                val actionBottomGapDp = if (surface == Surface.NOTIFICATION_CENTER) {
                    NOTIFICATION_ACTION_BOTTOM_GAP_DP
                } else {
                    ACTION_BOTTOM_GAP_DP
                }
                (params as? ViewGroup.MarginLayoutParams)?.apply {
                    topMargin = dp(actionTopGapDp, action.resources.displayMetrics.density)
                    bottomMargin = dp(actionBottomGapDp, action.resources.displayMetrics.density)
                }
                action.layoutParams = params
            }
            player.requestLayout()
            if (BuildConfig.DEBUG) {
                HookLogger.i(
                    TAG,
                    "通知中心播放控制按钮已放到进度条下方: " +
                        "progressBottom=${progressView.bottom}, " +
                        "actions=${actionViews.joinToString { it.top.toString() }}",
                )
            }
        }

        fun applyExpandedViewport(views: List<View>): Int? {
            if (surface != Surface.EXPANDED_ISLAND || root?.visibility != View.VISIBLE) {
                return null
            }
            val rootHeight = root?.measuredHeight?.takeIf { it > 0 } ?: return null
            val requiredExtra = maxOf(
                dp(EXTRA_HEIGHT_DP, player.resources.displayMetrics.density),
                rootHeight + dp(
                    TOP_GAP_DP + BOTTOM_GAP_DP,
                    player.resources.displayMetrics.density,
                ),
            )
            var maxTarget = 0
            views.distinct().forEach { view ->
                val currentHeight = view.layoutParams?.height?.takeIf { it > 0 }
                    ?: view.height.takeIf { it > 0 }
                    ?: return@forEach
                val previousTarget = viewportTargets[view]
                if (previousTarget == null || previousTarget != currentHeight) {
                    viewportBaseHeights[view] = currentHeight
                }
                val target = viewportBaseHeights[view]!! + requiredExtra
                viewportTargets[view] = target
                resizeIfNeeded(view, target)
                view.requestLayout()
                maxTarget = maxOf(maxTarget, target)
            }
            return maxTarget.takeIf { it > 0 }
        }

        private fun animateHeightsTo(
            playerTarget: Int,
            backgroundTarget: Int?,
            outerTarget: Int?,
            notificationRootTarget: Int? = null,
        ) {
            val previousAnimator = heightAnimator
            heightAnimator = null
            previousAnimator?.cancel()

            val startPlayer = measuredHeight(player)
            val startBackground = background?.let(::measuredHeight)
            val startOuter = if (outer !== player) outer?.let(::measuredHeight) else null
            val notificationRoot = root?.takeIf {
                surface == Surface.NOTIFICATION_CENTER &&
                    it.visibility == View.VISIBLE &&
                    notificationRootTarget != null
            }
            val stableRoot = stableNotificationRootHeight
            val startRoot = notificationRoot?.let { rootView ->
                rootView.layoutParams?.height?.takeIf { it > 0 }
                    ?: stableRoot
                    ?: rootView.height.takeIf { it > 0 }
            }

            fun applyFinalState() {
                resizeIfNeeded(player, playerTarget)
                if (backgroundTarget != null) {
                    background?.let { resizeIfNeeded(it, backgroundTarget) }
                }
                if (outerTarget != null && outer !== player) {
                    outer?.let { resizeIfNeeded(it, outerTarget) }
                }
                if (notificationRoot != null && notificationRootTarget != null) {
                    val params = notificationRoot.layoutParams
                    if (params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        notificationRoot.layoutParams = params
                    }
                    stableNotificationRootHeight = notificationRootTarget
                    resetActionTranslations()
                    applyActionLayout()
                }
                headerHeightController?.applyFinalHeight(playerTarget)
                player.requestLayout()
                background?.requestLayout()
                outer?.requestLayout()
                notificationRoot?.requestLayout()
            }

            // On the first real notification lyric measurement there is no
            // coherent previous lyric-root height to animate from. Applying the
            // complete target in one layout is safer than animating the parent
            // from a fallback while its children already use the real target.
            if (notificationRoot != null && stableRoot == null) {
                applyFinalState()
                if (BuildConfig.DEBUG) {
                    HookLogger.i(
                        TAG,
                        "通知中心首次歌词高度直接应用: player=$playerTarget, " +
                            "root=$notificationRootTarget",
                    )
                }
                return
            }

            val rootChanges = notificationRoot != null &&
                notificationRootTarget != null &&
                startRoot != null &&
                startRoot != notificationRootTarget
            val allAlreadyAtTarget =
                startPlayer == playerTarget &&
                    (backgroundTarget == null || startBackground == backgroundTarget) &&
                    (outerTarget == null || outer === player || startOuter == outerTarget) &&
                    !rootChanges
            if (allAlreadyAtTarget) {
                applyFinalState()
                return
            }

            // A notification height animation is only coherent when the lyric
            // root and the card change together. If the card target changed for
            // another host reason while the lyric height stayed the same, apply
            // the final layout atomically instead of letting the parent chase
            // already-positioned children.
            if (notificationRoot != null && !rootChanges) {
                applyFinalState()
                return
            }

            if (notificationRoot != null && startRoot != null) {
                resetActionTranslations()
                resizeIfNeeded(notificationRoot, startRoot)
                // Keep both the progress relation and the 14dp card-bottom
                // anchor. The synchronized root/player deltas make both
                // constraints satisfiable throughout the animation.
                applyActionLayout()
            }

            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = HEIGHT_ANIMATION_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { update ->
                    if (heightAnimator !== update) return@addUpdateListener
                    val fraction = update.animatedValue as Float
                    if (
                        notificationRoot != null &&
                        startRoot != null &&
                        notificationRootTarget != null
                    ) {
                        resizeIfNeeded(
                            notificationRoot,
                            lerp(startRoot, notificationRootTarget, fraction),
                        )
                    }
                    resizeIfNeeded(player, lerp(startPlayer, playerTarget, fraction))
                    if (backgroundTarget != null && startBackground != null) {
                        background?.let { view ->
                            resizeIfNeeded(view, lerp(startBackground, backgroundTarget, fraction))
                        }
                    }
                    if (outerTarget != null && outer !== player && startOuter != null) {
                        outer?.let { view ->
                            resizeIfNeeded(view, lerp(startOuter, outerTarget, fraction))
                        }
                    }
                    headerHeightController?.applyAnimatedHeight(
                        lerp(startOuter ?: startPlayer, outerTarget ?: playerTarget, fraction),
                    )
                    player.requestLayout()
                    background?.requestLayout()
                    outer?.requestLayout()
                    notificationRoot?.requestLayout()
                    schedulePreDrawGeometryLog()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (heightAnimator !== this@apply) return
                        heightAnimator = null
                        applyFinalState()
                        if (surface == Surface.NOTIFICATION_CENTER && root?.visibility == View.VISIBLE) {
                            player.post {
                                if (heightAnimator == null && root?.visibility == View.VISIBLE) {
                                    logNotificationGeometry("post_coordinated_height_animation")
                                }
                            }
                        }
                        if (BuildConfig.DEBUG) {
                            HookLogger.i(
                                TAG,
                                "媒体卡片歌词高度动画完成: surface=$surface, " +
                                    "player=$playerTarget, background=$backgroundTarget, " +
                                    "outer=$outerTarget, root=$notificationRootTarget",
                            )
                        }
                    }
                })
            }
            heightAnimator = animator
            animator.start()
        }

        private fun lerp(start: Int, end: Int, fraction: Float): Int =
            (start + (end - start) * fraction).roundToInt()

        fun beginNativeFullAodTransition(
            targetFullAod: Boolean,
            retainLyrics: Boolean,
        ) {
            if (surface != Surface.NOTIFICATION_CENTER) return
            // Freeze notification-card lyric data and height refreshes for the
            // entire native transition. The notification lyric root belongs to
            // the lock-screen card, not to the native full-AOD card. In the
            // pause style that restores native controls, hide it before
            // SystemUI measures the transition; the keep-lyrics/playing paths
            // retain it for the native lyric visual until the final state.
            suspendedForLockScreenAod = true
            cancelPreviewRefresh()
            root?.resetFullAodTransitionScale()
            if (targetFullAod && !retainLyrics) {
                root?.visibility = View.GONE
            }
            nativeFullAodTransitionActive = true
            heightAnimator?.let { animator ->
                heightAnimator = null
                animator.cancel()
            }

            // Hand the card dimensions back to SystemUI at the native
            // onBegin boundary. Restoring only LayoutParams/minimumHeight is
            // intentional: restoring MediaHeaderHeightController's actual
            // height here would create the lock-screen default-height frame
            // that the native transition is supposed to animate away from.
            backgroundConstraints?.restore()
            restoreView(player, originalPlayerLayoutHeight, originalPlayerMinimumHeight)
            background?.takeIf { it !== player }?.let {
                restoreView(it, originalBackgroundLayoutHeight, originalBackgroundMinimumHeight)
            }
            if (outer !== player) {
                outer?.let { restoreView(it, originalOuterLayoutHeight, originalOuterMinimumHeight) }
            }
            restoreActionLayout()
            targetPlayerHeight = null
            targetBackgroundHeight = null
            targetOuterHeight = null
            stableNotificationRootHeight = null
            targetNotificationRootHeight = null
            notificationProgressGapPx = 0
            nativeFullAodHeightApplied = null
            player.requestLayout()
            background?.requestLayout()
            outer?.requestLayout()
            if (BuildConfig.DEBUG) {
                HookLogger.i(
                    TAG,
                    "AOD_CARD_OWNER handoff_notification_card_to_native: " +
                        "player=${player.height}/${player.layoutParams?.height}, " +
                        "background=${background?.height}/${background?.layoutParams?.height}, " +
                        "outer=${outer?.height}/${outer?.layoutParams?.height}",
                )
            }
        }

        fun transitionToAod() {
            if (surface != Surface.NOTIFICATION_CENTER) {
                restore(immediate = true)
                return
            }
            if (pendingNotificationToAodRoots[player] != null) {
                // The root was already moved at the native onBegin boundary.
                // Keep the pending baseline until the AOD overlay has replaced it.
                restoreNotificationCardBaselineForNativeAod()
                return
            }
            if (root?.takeIf { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 } == null) {
                restore(immediate = true)
                return
            }
            moveRootToAodOverlay()
            restoreNotificationCardBaselineForNativeAod()
        }

        private fun moveRootToAodOverlay() {
            val transitionRoot = root?.takeIf {
                it.visibility == View.VISIBLE && it.width > 0 && it.height > 0
            } ?: return
            val baseline = aodTransitionBaseline()
            rootLayoutListener?.let(transitionRoot::removeOnLayoutChangeListener)
            rootLayoutListener = null
            detachForAodTransition(transitionRoot)
            val addedToOverlay = runCatching { player.overlay.add(transitionRoot) }
                .onFailure {
                    HookLogger.w(TAG, "AOD 转场歌词移入 overlay 失败", it)
                }
                .isSuccess
            if (!addedToOverlay) return
            transitionRoot.visibility = View.VISIBLE
            transitionRoot.alpha = 1f
            // Keep the final native-transition frame until the real lock-screen AOD
            // lyric root is visible. The AOD hook removes it in the same UI turn, so
            // there is no independently timed fade or blank frame between two roots.
            pendingNotificationToAodRoots[player] = PendingNotificationToAodTransition(
                root = transitionRoot,
                baseline = baseline,
            )
        }

        fun applyNativeFullAodHeight(nativeHeight: Int) {
            if (surface != Surface.NOTIFICATION_CENTER || nativeHeight <= 0) return
            if (nativeFullAodHeightApplied == nativeHeight) return
            heightAnimator?.let { animator ->
                heightAnimator = null
                animator.cancel()
            }
            cancelPreviewRefresh()
            root?.resetFullAodTransitionScale()
            root?.visibility = View.GONE
            restoreActionLayout()
            backgroundConstraints?.restore()
            resizeIfNeeded(player, nativeHeight)
            background?.takeIf { it !== player }?.let { resizeIfNeeded(it, nativeHeight) }
            if (outer !== player) {
                headerHeightController?.applyFinalHeight(nativeHeight)
            }
            targetPlayerHeight = nativeHeight
            targetBackgroundHeight = nativeHeight
            targetOuterHeight = nativeHeight.takeIf { outer !== player }
            nativeFullAodHeightApplied = nativeHeight
            player.requestLayout()
            background?.requestLayout()
            outer?.requestLayout()
            if (BuildConfig.DEBUG) {
                HookLogger.i(
                    TAG,
                    "暂停态锁屏 AOD 应用原生媒体卡片高度: " +
                        "nativeHeight=$nativeHeight, " +
                        "player=${player.height}/${player.layoutParams?.height}, " +
                        "background=${background?.height}/${background?.layoutParams?.height}, " +
                        "outer=${outer?.height}/${outer?.layoutParams?.height}",
                )
            }
        }

        private fun restoreNotificationCardBaselineForNativeAod() {
            heightAnimator?.let { animator ->
                heightAnimator = null
                animator.cancel()
            }
            restoreActionLayout()
            backgroundConstraints?.restore()
            restoreView(player, originalPlayerLayoutHeight, originalPlayerMinimumHeight)
            background?.takeIf { it !== player }?.let {
                restoreView(it, originalBackgroundLayoutHeight, originalBackgroundMinimumHeight)
            }
            if (outer !== player) {
                headerHeightController?.restoreHeight()
                outer?.let { restoreView(it, originalOuterLayoutHeight, originalOuterMinimumHeight) }
            }
            targetPlayerHeight = null
            targetBackgroundHeight = null
            targetOuterHeight = null
            stableNotificationRootHeight = null
            targetNotificationRootHeight = null
            nativeFullAodHeightApplied = null
            reassertPending = false
            player.requestLayout()
            background?.requestLayout()
            outer?.requestLayout()
            if (BuildConfig.DEBUG) {
                HookLogger.i(
                    TAG,
                    "媒体卡片恢复原生 full AOD 基线: " +
                        "player=${player.height}/${player.layoutParams?.height}, " +
                        "background=${background?.height}/${background?.layoutParams?.height}, " +
                        "outer=${outer?.height}/${outer?.layoutParams?.height}",
                )
            }
        }

        fun applyNativeFullAodTransition(
            transitionAlpha: Float,
            textColor: Int,
            secondaryTargetColor: Int,
            fraction: Float,
            targetSecondLineTextSizeSp: Float?,
            targetSecondLineTopOffsetPx: Int?,
            targetSecondLineAlpha: Int?,
            targetCardHeight: Int?,
        ) {
            if (surface != Surface.NOTIFICATION_CENTER) return
            if (!nativeFullAodTransitionActive) {
                beginNativeFullAodTransition(
                    targetFullAod = true,
                    retainLyrics = true,
                )
            }
            root?.takeIf { it.visibility == View.VISIBLE }?.let { lyricRoot ->
                lyricRoot.alpha = transitionAlpha.coerceIn(0f, 1f)
                lyricRoot.applyTransitionTextColor(textColor)
                lyricRoot.applyFullAodSecondLineScale(
                    fraction = fraction,
                    targetTextSizeSp = targetSecondLineTextSizeSp,
                    targetTopOffsetPx = targetSecondLineTopOffsetPx,
                    sourceColor = textColor,
                    targetColor = secondaryTargetColor,
                    targetAlpha = targetSecondLineAlpha,
                )
            }
            if (BuildConfig.DEBUG && targetCardHeight != null) {
                HookLogger.i(
                    TAG,
                    "通知中心原生 full AOD 高度交接延后到 AOD 回调: " +
                        "fraction=${"%.3f".format(fraction)}, " +
                        "target=$targetCardHeight, " +
                        "player=${player.height}/${player.layoutParams?.height}",
                )
            }
        }

        fun finishNativeFullAodTransition() {
            if (surface != Surface.NOTIFICATION_CENTER) return
            nativeFullAodTransitionActive = false
            root?.let { lyricRoot ->
                lyricRoot.alpha = 1f
                lyricRoot.resetFullAodTransitionScale()
                title?.let { lyricRoot.applyTransitionTextColor(it.currentTextColor) }
            }
        }

        private fun aodTransitionBaseline(): MediaCardAodTransitionBaseline =
            MediaCardAodTransitionBaseline(
                player = viewSizeBaseline(
                    view = player,
                    originalLayoutHeight = originalPlayerLayoutHeight,
                    originalMinimumHeight = originalPlayerMinimumHeight,
                    baseHeight = basePlayerHeight,
                ),
                background = background?.let { view ->
                    viewSizeBaseline(
                        view = view,
                        originalLayoutHeight = originalBackgroundLayoutHeight,
                        originalMinimumHeight = originalBackgroundMinimumHeight,
                        baseHeight = baseBackgroundHeight,
                    )
                },
                header = outer?.takeIf { it !== player }?.let { view ->
                    viewSizeBaseline(
                        view = view,
                        originalLayoutHeight = originalOuterLayoutHeight,
                        originalMinimumHeight = originalOuterMinimumHeight,
                        baseHeight = baseOuterHeight,
                    )
                },
            )

        private fun viewSizeBaseline(
            view: View,
            originalLayoutHeight: Int?,
            originalMinimumHeight: Int?,
            baseHeight: Int?,
        ): MediaCardAodViewSizeBaseline = MediaCardAodViewSizeBaseline(
            view = view,
            originalLayoutHeight = originalLayoutHeight
                ?: view.layoutParams?.height
                ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            originalMinimumHeight = originalMinimumHeight ?: view.minimumHeight,
            baseHeight = baseHeight?.takeIf { it > 0 } ?: measuredHeight(view),
        )

        private fun detachForAodTransition(transitionRoot: MediaCardLyricRoot) {
            cancelPreviewRefresh()
            heightAnimator?.let { animator ->
                heightAnimator = null
                animator.cancel()
            }
            restoreActionLayout()
            viewportBaseHeights.forEach { (view, height) -> resizeIfNeeded(view, height) }
            viewportBaseHeights.keys.forEach(View::requestLayout)
            viewportBaseHeights.clear()
            viewportTargets.clear()
            backgroundConstraints?.restore()
            preDrawGeometryListener?.let { listener ->
                if (player.viewTreeObserver.isAlive) {
                    player.viewTreeObserver.removeOnPreDrawListener(listener)
                }
            }
            preDrawGeometryListener = null
            preDrawGeometryPending = false
            playerLayoutListener?.let(player::removeOnLayoutChangeListener)
            playerLayoutListener = null
            lastGeometryLogKey = null
            if (transitionRoot.parent === player) {
                player.removeView(transitionRoot)
            }
            root = null
        }

        fun hideAndRestore(immediate: Boolean = false) {
            nativeFullAodTransitionActive = false
            root?.resetFullAodTransitionScale()
            root?.visibility = View.GONE
            root?.layoutParams?.let { params ->
                if (params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    root?.layoutParams = params
                }
            }
            stableNotificationRootHeight = null
            targetNotificationRootHeight = null
            lastRenderContent = null
            restoreActionLayout()
            val hadCustomHeight = targetPlayerHeight != null
            val restorePlayerHeight = originalPlayerLayoutHeight
                ?: basePlayerHeight
                ?: measuredHeight(player)
            val restoreBackgroundHeight = originalBackgroundLayoutHeight
                ?: baseBackgroundHeight
            val restoreOuterHeight = originalOuterLayoutHeight
                ?: baseOuterHeight
            if (hadCustomHeight && !immediate) {
                animateHeightsTo(
                    playerTarget = restorePlayerHeight,
                    backgroundTarget = restoreBackgroundHeight,
                    outerTarget = if (outer !== player) restoreOuterHeight else null,
                )
            } else {
                heightAnimator?.let { heightAnimator = null; it.cancel() }
                restoreView(player, originalPlayerLayoutHeight, originalPlayerMinimumHeight)
                background?.let {
                    restoreView(it, originalBackgroundLayoutHeight, originalBackgroundMinimumHeight)
                }
                if (outer !== player) outer?.let {
                    restoreView(it, originalOuterLayoutHeight, originalOuterMinimumHeight)
                }
                headerHeightController?.restoreHeight()
            }
            viewportBaseHeights.forEach { (view, height) -> resizeIfNeeded(view, height) }
            viewportBaseHeights.keys.forEach(View::requestLayout)
            viewportBaseHeights.clear()
            viewportTargets.clear()
            backgroundConstraints?.restore()
            targetPlayerHeight = null
            targetBackgroundHeight = null
            targetOuterHeight = null
            player.requestLayout()
            background?.requestLayout()
            outer?.requestLayout()
        }

        fun restore(immediate: Boolean = false) {
            cancelPreviewRefresh()
            hideAndRestore(immediate)
            root?.let { current ->
                rootLayoutListener?.let(current::removeOnLayoutChangeListener)
                if (current.parent === player) player.removeView(current)
            }
            preDrawGeometryListener?.let { listener ->
                if (player.viewTreeObserver.isAlive) {
                    player.viewTreeObserver.removeOnPreDrawListener(listener)
                }
            }
            preDrawGeometryListener = null
            preDrawGeometryPending = false
            playerLayoutListener?.let(player::removeOnLayoutChangeListener)
            rootLayoutListener = null
            playerLayoutListener = null
            lastGeometryLogKey = null
            root = null
        }

        private fun resetActionTranslations() {
            actionTranslationBases.forEach { (view, baseTranslation) ->
                if (view.translationY != baseTranslation) {
                    view.translationY = baseTranslation
                }
            }
        }

        private fun restoreActionLayout() {
            verticalSnapshots.forEach { (view, snapshot) ->
                val params = view.layoutParams ?: return@forEach
                snapshot.restore(params)
                view.layoutParams = params
            }
            resetActionTranslations()
            verticalSnapshots.clear()
            actionTranslationBases.clear()
            player.requestLayout()
        }

        private fun restoreView(view: View, layoutHeight: Int?, minimumHeight: Int?) {
            val params = view.layoutParams ?: return
            layoutHeight?.let { params.height = it }
            view.layoutParams = params
            minimumHeight?.let { view.minimumHeight = it }
        }

        private fun resizeIfNeeded(view: View, target: Int) {
            val params = view.layoutParams ?: return
            if (params.height != target) {
                params.height = target
                view.layoutParams = params
            }
        }

        private fun measuredHeight(view: View): Int =
            view.height.takeIf { it > 0 }
                ?: view.measuredHeight.takeIf { it > 0 }
                ?: view.layoutParams?.height?.takeIf { it > 0 }
                ?: 0

        private fun setConstraintField(params: ViewGroup.LayoutParams, name: String, value: Int) {
            runCatching { params.javaClass.getField(name).setInt(params, value) }
        }

        private fun setConstraintFloatField(
            params: ViewGroup.LayoutParams,
            name: String,
            value: Float,
        ) {
            runCatching { params.javaClass.getField(name).setFloat(params, value) }
        }
    }

    private data class ActionConstraintSnapshot(
        val verticalFields: Map<String, Int>,
        val topMargin: Int?,
        val bottomMargin: Int?,
        val verticalBias: Float?,
    ) {
        fun restore(params: ViewGroup.LayoutParams) {
            verticalFields.forEach { (name, value) ->
                runCatching { params.javaClass.getField(name).setInt(params, value) }
            }
            (params as? ViewGroup.MarginLayoutParams)?.let { marginParams ->
                topMargin?.let { marginParams.topMargin = it }
                bottomMargin?.let { marginParams.bottomMargin = it }
            }
            verticalBias?.let { value ->
                runCatching { params.javaClass.getField("verticalBias").setFloat(params, value) }
            }
        }

        companion object {
            private val VERTICAL_FIELDS = listOf(
                "topToTop",
                "topToBottom",
                "bottomToTop",
                "bottomToBottom",
                "baselineToBaseline",
                "baselineToTop",
                "baselineToBottom",
            )

            fun capture(params: ViewGroup.LayoutParams): ActionConstraintSnapshot =
                ActionConstraintSnapshot(
                    verticalFields = VERTICAL_FIELDS.mapNotNull { name ->
                        runCatching {
                            name to params.javaClass.getField(name).getInt(params)
                        }.getOrNull()
                    }.toMap(),
                    topMargin = (params as? ViewGroup.MarginLayoutParams)?.topMargin,
                    bottomMargin = (params as? ViewGroup.MarginLayoutParams)?.bottomMargin,
                    verticalBias = runCatching {
                        params.javaClass.getField("verticalBias").getFloat(params)
                    }.getOrNull(),
                )
        }
    }

    private class MediaCardLyricRoot(
        context: Context,
        clipLyricsToBounds: Boolean,
        private val wrapLyrics: Boolean,
    ) : LinearLayout(context) {
        private val groupViews = List(3) { MediaCardLyricGroupView(context, wrapLyrics) }

        init {
            if (wrapLyrics && id == View.NO_ID) {
                // The notification progress bar is constrained below this root;
                // it needs a real ConstraintLayout anchor id.
                id = View.generateViewId()
            }
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = clipLyricsToBounds
            clipToPadding = clipLyricsToBounds
            groupViews.forEachIndexed { index, view ->
                addView(
                    view,
                    LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (index > 0) {
                            topMargin = dp(
                                if (wrapLyrics) {
                                    NOTIFICATION_LYRIC_GROUP_GAP_DP
                                } else {
                                    LINE_GAP_DP
                                },
                            )
                        }
                    },
                )
            }
        }

        fun bind(
            content: MediaCardLyricContent,
            source: TextView?,
            config: MediaCardLyricConfig,
        ) {
            val sourceColor = source?.currentTextColor ?: Color.WHITE
            groupViews.forEachIndexed { index, view ->
                val group = content.groups.getOrNull(index)
                if (group == null || group.rows.isEmpty()) {
                    view.reserveMainLine(config, source)
                } else {
                    view.bind(
                        group = group,
                        config = config,
                        source = source,
                        sourceColor = sourceColor,
                        groupAlpha = when (group.blurDistance) {
                            0 -> 0xFF
                            1 -> 0xDD
                            else -> 0xAA
                        },
                    )
                }
            }
            requestLayout()
        }

        fun applyTransitionTextColor(color: Int) {
            groupViews.forEach { it.applyTransitionTextColor(color) }
        }

        fun applyFullAodSecondLineScale(
            fraction: Float,
            targetTextSizeSp: Float?,
            targetTopOffsetPx: Int?,
            sourceColor: Int,
            targetColor: Int,
            targetAlpha: Int?,
        ) {
            val secondGroup = groupViews.getOrNull(1) ?: return
            secondGroup.applyMainRowScale(
                fraction = fraction,
                targetTextSizeSp = targetTextSizeSp,
                sourceColor = sourceColor,
                targetColor = targetColor,
                targetAlpha = targetAlpha,
            )
            val sourceTop = secondGroup.top
            val targetTop = if (targetTopOffsetPx != null) {
                targetTopOffsetPx
            } else {
                sourceTop
            }
            val value = fraction.coerceIn(0f, 1f)
            val interpolatedTop = (
                sourceTop + (targetTop - sourceTop) * value
                ).roundToInt()
            secondGroup.translationY = interpolatedTop.toFloat() - sourceTop
        }

        fun resetFullAodTransitionScale() {
            groupViews.forEach { group ->
                group.translationY = 0f
                group.resetTransitionScale()
            }
        }

        fun measureWrappedContentHeight(availableWidth: Int): Int? {
            if (!wrapLyrics || availableWidth <= 0) return null
            val contentWidth = (
                availableWidth - paddingLeft - paddingRight
            ).coerceAtLeast(1)
            val childHeightSpec = View.MeasureSpec.makeMeasureSpec(
                0,
                View.MeasureSpec.UNSPECIFIED,
            )
            var totalHeight = paddingTop + paddingBottom
            groupViews.filter { it.visibility != View.GONE }.forEach { view ->
                val params = view.layoutParams as? ViewGroup.MarginLayoutParams
                val childWidth = (
                    contentWidth -
                        (params?.leftMargin ?: 0) -
                        (params?.rightMargin ?: 0)
                ).coerceAtLeast(1)
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(childWidth, View.MeasureSpec.EXACTLY),
                    childHeightSpec,
                )
                totalHeight += (params?.topMargin ?: 0) +
                    view.measuredHeight +
                    (params?.bottomMargin ?: 0)
            }
            return maxOf(suggestedMinimumHeight, totalHeight)
        }

        private fun dp(value: Float): Int =
            (value * resources.displayMetrics.density).roundToInt()
    }

    private class MediaCardLyricGroupView(
        context: Context,
        private val wrapLyrics: Boolean,
    ) : LinearLayout(context) {
        private val rowViews = mutableListOf<TextView>()
        private val rowAlphas = mutableListOf<Int>()
        private val rowRoles = mutableListOf<MediaCardLyricTextRole?>()

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        fun bind(
            group: MediaCardLyricGroupContent,
            config: MediaCardLyricConfig,
            source: TextView?,
            sourceColor: Int,
            groupAlpha: Int,
        ) {
            visibility = View.VISIBLE
            ensureRows(group.rows.size)
            rowViews.forEachIndexed { index, view ->
                val row = group.rows.getOrNull(index)
                val params = view.layoutParams as? LayoutParams
                params?.let {
                    val targetTopMargin = if (index > 0) {
                        dp(
                            if (wrapLyrics) {
                                NOTIFICATION_LYRIC_ROW_GAP_DP
                            } else {
                                MEDIA_CARD_GROUP_ROW_GAP_DP
                            },
                        )
                    } else {
                        0
                    }
                    if (it.topMargin != targetTopMargin) {
                        it.topMargin = targetTopMargin
                        view.layoutParams = it
                    }
                }
                if (row == null) {
                    view.visibility = View.GONE
                    view.text = ""
                    rowAlphas[index] = 0xFF
                    rowRoles[index] = null
                    return@forEachIndexed
                }
                rowRoles[index] = row.role
                view.visibility = View.VISIBLE
                view.text = row.text
                view.gravity = when (row.alignment) {
                    MediaCardLyricAlignment.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
                    MediaCardLyricAlignment.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
                    MediaCardLyricAlignment.CENTER -> Gravity.CENTER
                }
                view.setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    when (row.role) {
                        MediaCardLyricTextRole.MAIN,
                        MediaCardLyricTextRole.PREVIEW -> config.mainTextSize.toFloat()
                        MediaCardLyricTextRole.BACKING -> config.backingTextSize.toFloat()
                        MediaCardLyricTextRole.TRANSLATION -> config.translationTextSize.toFloat()
                    },
                )
                view.setTextColor(
                    Color.argb(
                        groupAlpha,
                        Color.red(sourceColor),
                        Color.green(sourceColor),
                        Color.blue(sourceColor),
                    )
                )
                rowAlphas[index] = groupAlpha
                source?.typeface?.let { view.typeface = it }
            }
            val radius = AppleLyricsBlurPolicy.blurRadiusPx(
                mode = config.blurMode,
                rowDistance = group.blurDistance,
                minRadius = config.blurMinRadius,
                maxRadius = config.blurMaxRadius,
                density = resources.displayMetrics.density,
            )
            AppleLyricsBlurRenderer.apply(this, config.blurMode, radius)
            requestLayout()
        }

        fun reserveMainLine(config: MediaCardLyricConfig, source: TextView?) {
            // Keep the three original lyric slots in measurement even when the
            // next or next-next lyric is unavailable. INVISIBLE preserves the
            // accepted progress-bar baseline without drawing placeholder text.
            visibility = View.INVISIBLE
            ensureRows(1)
            rowViews.forEachIndexed { index, view ->
                view.text = ""
                view.visibility = if (index == 0) View.VISIBLE else View.GONE
                rowRoles[index] = if (index == 0) MediaCardLyricTextRole.MAIN else null
                if (index == 0) {
                    rowAlphas[index] = 0xFF
                    view.gravity = Gravity.CENTER
                    view.setTextSize(
                        TypedValue.COMPLEX_UNIT_SP,
                        config.mainTextSize.toFloat(),
                    )
                    source?.typeface?.let { view.typeface = it }
                }
            }
            AppleLyricsBlurRenderer.clear(this)
            requestLayout()
        }

        fun applyTransitionTextColor(color: Int) {
            rowViews.forEachIndexed { index, view ->
                if (view.visibility != View.VISIBLE) return@forEachIndexed
                val alpha = rowAlphas.getOrElse(index) { 0xFF }
                view.setTextColor(
                    Color.argb(
                        alpha,
                        Color.red(color),
                        Color.green(color),
                        Color.blue(color),
                    )
                )
            }
        }

        fun applyMainRowScale(
            fraction: Float,
            targetTextSizeSp: Float?,
            sourceColor: Int,
            targetColor: Int,
            targetAlpha: Int?,
        ) {
            resetTransitionScale()
            val index = rowRoles.indexOfFirst { role ->
                role == MediaCardLyricTextRole.MAIN || role == MediaCardLyricTextRole.PREVIEW
            }
            val view = rowViews.getOrNull(index)?.takeIf { it.visibility == View.VISIBLE } ?: return
            targetTextSizeSp?.takeIf { it > 0f }?.let { targetSize ->
                val targetPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    targetSize,
                    resources.displayMetrics,
                )
                val value = fraction.coerceIn(0f, 1f)
                val targetScale = if (view.textSize > 0f && targetPx > 0f) {
                    targetPx / view.textSize
                } else {
                    1f
                }
                val scale = 1f + (targetScale - 1f) * value
                view.scaleX = scale
                view.scaleY = scale
            }
            targetAlpha?.let { alpha ->
                val value = fraction.coerceIn(0f, 1f)
                val sourceAlpha = rowAlphas.getOrElse(index) { 0xFF }
                val interpolatedAlpha = (
                    sourceAlpha.coerceIn(0, 0xFF) +
                        (alpha.coerceIn(0, 0xFF) - sourceAlpha.coerceIn(0, 0xFF)) * value
                    ).roundToInt()
                val sourceRgb = sourceColor
                val targetRgb = targetColor
                val red = (Color.red(sourceRgb) +
                    (Color.red(targetRgb) - Color.red(sourceRgb)) * value).roundToInt()
                val green = (Color.green(sourceRgb) +
                    (Color.green(targetRgb) - Color.green(sourceRgb)) * value).roundToInt()
                val blue = (Color.blue(sourceRgb) +
                    (Color.blue(targetRgb) - Color.blue(sourceRgb)) * value).roundToInt()
                view.setTextColor(Color.argb(interpolatedAlpha, red, green, blue))
            }
            view.pivotX = when (view.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
                Gravity.LEFT -> 0f
                Gravity.RIGHT -> view.width.toFloat()
                else -> view.width / 2f
            }
            view.pivotY = 0f
        }

        fun resetTransitionScale() {
            rowViews.forEach { view ->
                if (view.scaleX != 1f) view.scaleX = 1f
                if (view.scaleY != 1f) view.scaleY = 1f
            }
        }

        private fun ensureRows(count: Int) {
            while (rowViews.size < count) {
                val view = createRowView()
                rowViews += view
                rowAlphas += 0xFF
                rowRoles += null
                addView(
                    view,
                    LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (rowViews.size > 1) topMargin = dp(2f)
                    },
                )
            }
        }

        private fun createRowView(): TextView = TextView(context).apply {
            includeFontPadding = false
            setHorizontallyScrolling(false)
            setPadding(0, 0, 0, 0)
            setTextColor(Color.WHITE)
            if (wrapLyrics) {
                setSingleLine(false)
                maxLines = Int.MAX_VALUE
                ellipsize = null
            } else {
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
        }

        private fun dp(value: Float): Int =
            (value * resources.displayMetrics.density).roundToInt()
    }

    private class BackgroundConstraints private constructor(
        private val view: View,
        private val originalTopMargin: Int,
        private val originalBottomMargin: Int,
        private val verticalBiasField: Field?,
        private val originalVerticalBias: Float?,
    ) {
        private var pinned = false

        fun pinToParentTop(expectedParent: View): Boolean {
            if (pinned) return true
            if (view.parent !== expectedParent) return false
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
            verticalBiasField?.setFloat(params, 0f)
            params.topMargin = 0
            params.bottomMargin = 0
            view.layoutParams = params
            pinned = true
            return true
        }

        fun isPinned(): Boolean = pinned

        fun restore() {
            if (!pinned) return
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            originalVerticalBias?.let { value ->
                verticalBiasField?.setFloat(params, value)
            }
            params.topMargin = originalTopMargin
            params.bottomMargin = originalBottomMargin
            view.layoutParams = params
            pinned = false
        }

        companion object {
            fun create(view: View): BackgroundConstraints? = runCatching {
                val params = view.layoutParams as? ViewGroup.MarginLayoutParams
                    ?: return@runCatching null
                val biasField = runCatching {
                    params.javaClass.getField("verticalBias").apply { isAccessible = true }
                }.getOrNull()
                BackgroundConstraints(
                    view = view,
                    originalTopMargin = params.topMargin,
                    originalBottomMargin = params.bottomMargin,
                    verticalBiasField = biasField,
                    originalVerticalBias = biasField?.getFloat(params),
                )
            }.getOrNull()
        }
    }

    private class MediaHeaderHeightController private constructor(
        private val view: View,
        private val lockScreenHeightField: Field,
        private val setAnimateHeightMethod: Method,
        private val setActualHeightMethod: Method,
        private val originalHeight: Int,
        private val originalMinimumHeight: Int,
    ) {
        fun applyAnimatedHeight(height: Int) {
            if (height <= 0) return
            runCatching {
                lockScreenHeightField.setInt(view, height)
                setActualHeightMethod.invoke(view, height, false)
                view.requestLayout()
                (view.parent as? View)?.requestLayout()
            }
        }

        fun applyFinalHeight(height: Int) {
            if (height <= 0) return
            runCatching {
                lockScreenHeightField.setInt(view, height)
                setActualHeightMethod.invoke(view, height, false)
                view.minimumHeight = height
                view.requestLayout()
                (view.parent as? View)?.requestLayout()
            }
        }

        fun restoreHeight() {
            runCatching {
                lockScreenHeightField.setInt(view, originalHeight)
                setAnimateHeightMethod.invoke(view, 0)
                setActualHeightMethod.invoke(view, originalHeight, false)
                view.minimumHeight = originalMinimumHeight
                view.requestLayout()
                (view.parent as? View)?.requestLayout()
            }
        }

        companion object {
            fun create(
                view: View?,
                baseline: MediaCardAodViewSizeBaseline? = null,
            ): MediaHeaderHeightController? {
                if (view?.javaClass?.name != MEDIA_HEADER_VIEW_CLASS) return null
                return runCatching {
                    val clazz = view.javaClass
                    val heightField = clazz.getDeclaredField("mediaLockScreenHeight")
                        .apply { isAccessible = true }
                    val animateHeight = clazz.getDeclaredMethod(
                        "setAnimateHeight",
                        Int::class.javaPrimitiveType,
                    ).apply { isAccessible = true }
                    val actualHeight = clazz.getMethod(
                        "setActualHeight",
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                    ).apply { isAccessible = true }
                    MediaHeaderHeightController(
                        view,
                        heightField,
                        animateHeight,
                        actualHeight,
                        baseline?.takeIf { it.view === view }?.baseHeight
                            ?: heightField.getInt(view),
                        baseline?.takeIf { it.view === view }?.originalMinimumHeight
                            ?: view.minimumHeight,
                    )
                }.getOrNull()
            }
        }
    }
}
