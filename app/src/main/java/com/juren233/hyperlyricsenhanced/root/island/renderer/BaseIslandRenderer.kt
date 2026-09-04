package com.juren233.hyperlyricsenhanced.root.island.renderer

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.lyric.view.RichLyricLineView
import com.juren233.hyperlyricsenhanced.lyric.view.SpaceGateRichLyricLineView
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.island.IslandAlbumCoverStyleHooker
import com.juren233.hyperlyricsenhanced.root.island.IslandHostFacade
import com.juren233.hyperlyricsenhanced.root.island.IslandLyricTextInjector
import com.juren233.hyperlyricsenhanced.root.island.IslandProbeUtils
import com.juren233.hyperlyricsenhanced.root.island.IslandProgressGlowController
import com.juren233.hyperlyricsenhanced.root.island.IslandSlotContentAssembler
import com.juren233.hyperlyricsenhanced.root.island.IslandSlotRuntimeConfig
import com.juren233.hyperlyricsenhanced.root.island.IslandViewRegistry
import com.juren233.hyperlyricsenhanced.root.island.NextSongPreviewPolicy
import com.juren233.hyperlyricsenhanced.root.utils.DisplayDiagnosticLogger
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.WeakHashMap

object BaseIslandRenderer : IslandRenderer {

    private const val REFRESH_DEBOUNCE_MS = 32L
    private val SCREEN_ON_REFRESH_DELAYS_MS = longArrayOf(0L, 120L, 400L, 900L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { performRefreshActiveIsland() }
    private val pauseTransitionGuard = IslandPauseTransitionGuard()
    private val pauseRestoreRunnable = Runnable { commitDeferredNativeRestore() }
    private val nextSongPreviewActive = WeakHashMap<ViewGroup, NextSongPreviewState>()
    private val nextSongPreviewFailures = WeakHashMap<ViewGroup, String>()

    private data class NextSongPreviewState(
        val style: Int,
        val targetIsLeft: Boolean?,
        val nextSong: MediaMetadataHelper.MediaInfo
    )

    @Volatile
    private var playbackActive = true

    @Volatile
    private var clearedByPause = false

    @Volatile
    private var screenRefreshGeneration = 0

    /**
     * Source lifecycle events are the authority for lyric rendering state.
     * Hook paths must not re-query MediaSession here: during a lyric refresh the source can
     * already be stopped while the player session still reports STATE_PLAYING.
     */
    fun shouldRenderInjectedIsland(): Boolean {
        val prefs = HookEntry.instance?.prefs ?: return false
        if (!prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND)) {
            return false
        }
        val behavior = prefs.getInt(
            RootConstants.KEY_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE,
            RootConstants.DEFAULT_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE
        )
        return playbackActive || behavior != 0 || pauseTransitionGuard.nativeRestorePending
    }

    fun currentPlaybackActive(): Boolean = playbackActive

    override fun refreshActiveIsland() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS)
    }

    fun onScreenInteractive() {
        val generation = ++screenRefreshGeneration
        SCREEN_ON_REFRESH_DELAYS_MS.forEach { delay ->
            mainHandler.postDelayed(
                {
                    if (generation != screenRefreshGeneration) return@postDelayed
                    val estimatedPosition = LyriconDataBridge.estimatedPosition()
                    if (estimatedPosition != null) {
                        if (LyriconDataBridge.updateEstimatedPosition(estimatedPosition)) {
                            updateLyricLine()
                        }
                        updatePosition(estimatedPosition)
                    }
                    refreshActiveIsland()
                },
                delay,
            )
        }
        DisplayDiagnosticLogger.log(
            channel = "ISLAND",
            result = "pending",
            reason = "screen_on_refresh_scheduled",
            extra = "attempts=${SCREEN_ON_REFRESH_DELAYS_MS.size}",
            dedupeKey = "ISLAND/screen_on",
        )
    }

    fun onScreenNonInteractive() {
        screenRefreshGeneration++
        DisplayDiagnosticLogger.clear("ISLAND/screen_on")
    }

    private fun performRefreshActiveIsland() {
        val prefs = HookEntry.instance?.prefs ?: run {
            DisplayDiagnosticLogger.log("ISLAND", "skipped", "preferences_unavailable")
            return
        }
        if (!prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND)) {
            clearAllViews()
            DisplayDiagnosticLogger.log("ISLAND", "hidden", "feature_disabled")
            return
        }
        if (!shouldRenderInjectedIsland()) {
            clearActiveViewsForPause()
            DisplayDiagnosticLogger.log("ISLAND", "hidden", "pause_policy")
            return
        }

        val lyricPkg = LyriconDataBridge.currentLyricPackageName?.takeIf { it.isNotEmpty() } ?: run {
            DisplayDiagnosticLogger.log("ISLAND", "skipped", "package_missing")
            return
        }

        IslandSlotContentAssembler.invalidate()
        synchronized(nextSongPreviewActive) { nextSongPreviewActive.clear() }
        synchronized(nextSongPreviewFailures) { nextSongPreviewFailures.clear() }

        val activeViews = IslandViewRegistry.snapshotAttached(lyricPkg)
        if (activeViews.isEmpty()) {
            DisplayDiagnosticLogger.log("ISLAND", "skipped", "no_attached_view")
            return
        }
        val config = IslandSlotRuntimeConfig.from(prefs)
        activeViews.forEach { (cv, _) ->
            cv.post {
                // Existing content is refreshed below. Reconfiguring it here forces
                // applySlotContent() to report a change on every screen-on retry, which
                // incorrectly turns four content refreshes into four host width relayouts.
                val injectionChanged = IslandLyricTextInjector.injectSlots(
                    cv,
                    reconfigureExisting = false,
                )
                if (injectionChanged) {
                    IslandHostFacade.triggerSystemRelayout(cv)
                } else {
                    IslandHostFacade.applyHostSettings(cv, prefs)
                }
                val contentChanged = updateContentForView(cv, lyricPkg, prefs, config)
                if (config.dynamicWidthEnabled && contentChanged) {
                    IslandHostFacade.triggerSystemRelayout(cv)
                }
                val injected = IslandLyricTextInjector.hasInjectedLyricText(cv)
                DisplayDiagnosticLogger.log(
                    channel = "ISLAND",
                    result = if (injected) "shown" else "skipped",
                    reason = if (injected) "injected_view_visible" else "injection_unavailable",
                    extra = "targetViews=${activeViews.size}, injectionChanged=$injectionChanged, " +
                        "playbackActive=$playbackActive",
                    dedupeKey = "ISLAND/refresh",
                )
            }
        }

        HookLogger.d("BaseIslandRenderer", "已刷新活动媒体岛: 数量=${activeViews.size}")
        DisplayDiagnosticLogger.log(
            channel = "ISLAND",
            result = "pending",
            reason = "refresh_scheduled",
            extra = "targetViews=${activeViews.size}, playbackActive=$playbackActive",
            dedupeKey = "ISLAND/refresh",
        )
    }

    override fun updateLyricLine() {
        if ((HookEntry.instance?.prefs?.getBoolean(RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND)) != true) {
            DisplayDiagnosticLogger.log("ISLAND", "skipped", "feature_disabled")
            return
        }
        if (!shouldRenderInjectedIsland()) {
            DisplayDiagnosticLogger.log("ISLAND", "skipped", "pause_policy")
            return
        }
        val lyricPkg = LyriconDataBridge.currentLyricPackageName
        if (lyricPkg.isNullOrEmpty()) {
            DisplayDiagnosticLogger.log("ISLAND", "skipped", "package_missing")
            return
        }

        val prefs = HookEntry.instance?.prefs ?: run {
            DisplayDiagnosticLogger.log("ISLAND", "skipped", "preferences_unavailable")
            return
        }
        val config = IslandSlotRuntimeConfig.from(prefs)

        val activeViews = IslandViewRegistry.snapshotAttached(lyricPkg)
        if (activeViews.isEmpty()) {
            DisplayDiagnosticLogger.log("ISLAND", "skipped", "no_attached_view")
            return
        }
        activeViews.forEach { (cv, _) ->
                cv.post {
                    if (!IslandLyricTextInjector.hasInjectedLyricText(cv)) {
                        DisplayDiagnosticLogger.log(
                            channel = "ISLAND",
                            result = "skipped",
                            reason = "injected_view_missing",
                            extra = "targetViews=${activeViews.size}",
                            dedupeKey = "ISLAND/line",
                        )
                        return@post
                    }
                    val contentChanged = updateLyricContentForView(cv, prefs, config)
                    if (config.dynamicWidthEnabled && contentChanged) {
                        IslandHostFacade.triggerSystemRelayout(cv)
                    }
                    DisplayDiagnosticLogger.log(
                        channel = "ISLAND",
                        result = "shown",
                        reason = "lyric_line_updated",
                        extra = "targetViews=${activeViews.size}, playbackActive=$playbackActive",
                        dedupeKey = "ISLAND/line",
                    )
                }
            }
    }

    override fun updatePosition(position: Long) {
        updatePositionForActiveViews(position, isSeek = false)
    }

    override fun seekTo(position: Long) {
        updatePositionForActiveViews(position, isSeek = true)
    }

    private fun updatePositionForActiveViews(position: Long, isSeek: Boolean) {
        val prefs = HookEntry.instance?.prefs ?: return
        if (!prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND)) return
        if (!shouldRenderInjectedIsland()) return
        val lyricPkg = LyriconDataBridge.currentLyricPackageName ?: return

        IslandViewRegistry.snapshotAttachedInjectedViews(lyricPkg)
            .forEach { (cv, indexedViews) ->
                cv.post {
                    if (indexedViews.isEmpty()) {
                        updateViewPosition(
                            cv.findViewWithTag(IslandProbeUtils.LEFT_TEST_VIEW_TAG),
                            position,
                            isSeek
                        )
                        updateViewPosition(
                            cv.findViewWithTag(IslandProbeUtils.RIGHT_TEST_VIEW_TAG),
                            position,
                            isSeek
                        )
                        IslandViewRegistry.refreshInjectedViews(cv)
                    } else {
                        indexedViews.forEach { view -> updateViewPosition(view, position, isSeek) }
                    }
                    IslandHostFacade.updateProgressGlow(cv, lyricPkg, prefs)
                    val config = IslandSlotRuntimeConfig.from(prefs)
                    val previewChanged = updateEndOfSongPreview(
                        cv,
                        lyricPkg,
                        prefs,
                        config,
                        position
                    )
                    if (config.dynamicWidthEnabled && previewChanged) {
                        IslandHostFacade.triggerSystemRelayout(cv)
                    }
                }
            }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        val prefs = HookEntry.instance?.prefs ?: return
        if (!prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND)) {
            clearAllViews()
            return
        }
        val behavior = prefs.getInt(
            RootConstants.KEY_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE,
            RootConstants.DEFAULT_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE
        )
        val transition = pauseTransitionGuard.onPlaybackStateChanged(isPlaying, behavior)
        playbackActive = isPlaying
        IslandAlbumCoverStyleHooker.onPlaybackStateChanged(isPlaying)
        IslandProgressGlowController.onPlaybackStateChanged(isPlaying)
        HookLogger.d("BaseIslandRenderer", "播放状态变化: 正在播放=$isPlaying")

        when (transition) {
            IslandPauseTransitionGuard.Transition.RESUME -> {
                mainHandler.removeCallbacks(pauseRestoreRunnable)
                DisplayDiagnosticLogger.log(
                    channel = "ISLAND",
                    result = "shown",
                    reason = "playback_resumed",
                    extra = "pauseBehavior=$behavior",
                )
                if (clearedByPause) {
                    clearedByPause = false
                    refreshActiveIsland()
                } else {
                    applyPlaybackStateToActiveViews(true)
                }
                HookLogger.d("BaseIslandRenderer", "播放已继续，等待进度或歌词事件")
            }

            IslandPauseTransitionGuard.Transition.DEFER_NATIVE_RESTORE -> {
                applyPlaybackStateToActiveViews(false)
                mainHandler.postDelayed(
                    pauseRestoreRunnable,
                    IslandPauseTransitionGuard.NATIVE_RESTORE_DELAY_MS,
                )
                DisplayDiagnosticLogger.log(
                    channel = "ISLAND",
                    result = "pending",
                    reason = "pause_policy_restore_native_deferred",
                    extra = "pauseBehavior=$behavior, " +
                        "delayMs=${IslandPauseTransitionGuard.NATIVE_RESTORE_DELAY_MS}",
                )
                HookLogger.d("BaseIslandRenderer", "播放短暂停顿，延迟恢复原生媒体岛")
            }

            IslandPauseTransitionGuard.Transition.NATIVE_RESTORE_ALREADY_PENDING -> Unit

            IslandPauseTransitionGuard.Transition.NATIVE_RESTORE_ALREADY_COMMITTED -> Unit

            IslandPauseTransitionGuard.Transition.KEEP_LYRICS -> {
                mainHandler.removeCallbacks(pauseRestoreRunnable)
                applyPlaybackStateToActiveViews(false)
                DisplayDiagnosticLogger.log(
                    channel = "ISLAND",
                    result = "shown",
                    reason = "pause_policy_keep_lyrics",
                    extra = "pauseBehavior=$behavior",
                )
                HookLogger.d("BaseIslandRenderer", "已暂停，保留当前歌词注入")
            }
        }
    }

    private fun commitDeferredNativeRestore() {
        if (!pauseTransitionGuard.consumeNativeRestore(playbackActive)) return
        clearActiveViewsForPause()
        DisplayDiagnosticLogger.log(
            channel = "ISLAND",
            result = "hidden",
            reason = "pause_policy_restore_native",
            extra = "pauseBehavior=0",
        )
        HookLogger.d("BaseIslandRenderer", "暂停超过切歌宽限期，恢复原生媒体岛")
    }

    private fun clearActiveViewsForPause() {
        val lyricPkg = LyriconDataBridge.currentLyricPackageName
        IslandViewRegistry.snapshotAttached()
            .filter { (_, pkgName) -> lyricPkg == null || pkgName == lyricPkg }
            .forEach { (cv, _) ->
                cv.post {
                    IslandHostFacade.clearAndRefresh(cv)
                }
            }
        clearedByPause = true
    }

    private fun applyPlaybackStateToActiveViews(isPlaying: Boolean) {
        val lyricPkg = LyriconDataBridge.currentLyricPackageName
        IslandViewRegistry.snapshotAttachedInjectedViews(lyricPkg)
            .forEach { (cv, indexedViews) ->
                cv.post {
                    if (indexedViews.isEmpty()) {
                        setPlaybackActiveRecursively(cv, isPlaying)
                        IslandViewRegistry.refreshInjectedViews(cv)
                    } else {
                        indexedViews.forEach { view ->
                            setPlaybackActive(view, isPlaying)
                        }
                    }
                }
            }
    }

    private fun setPlaybackActive(view: View, isPlaying: Boolean) {
        when (view) {
            is RichLyricLineView -> view.setPlaybackActive(isPlaying)
            is SpaceGateRichLyricLineView -> view.setPlaybackActive(isPlaying)
        }
    }

    private fun updateViewPosition(view: View?, position: Long, isSeek: Boolean) {
        when (view) {
            is RichLyricLineView -> if (isSeek) view.seekTo(position) else view.setPosition(position)
            is SpaceGateRichLyricLineView -> if (isSeek) view.seekTo(position) else view.setPosition(position)
        }
    }

    private fun setPlaybackActiveRecursively(view: View, isPlaying: Boolean) {
        when (view) {
            is RichLyricLineView,
            is SpaceGateRichLyricLineView -> setPlaybackActive(view, isPlaying)
            is ViewGroup -> {
                for (index in 0 until view.childCount) {
                    setPlaybackActiveRecursively(view.getChildAt(index), isPlaying)
                }
            }
        }
    }

    override fun clearAllViews() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.removeCallbacks(pauseRestoreRunnable)
        screenRefreshGeneration++
        pauseTransitionGuard.reset()
        playbackActive = false
        clearedByPause = true
        IslandViewRegistry.snapshotAttached()
            .forEach { (cv, _) ->
                cv.post {
                    IslandHostFacade.clearAndRefresh(cv)
                }
            }
    }

    private fun updateContentForView(
        cv: ViewGroup,
        packageName: String,
        prefs: android.content.SharedPreferences,
        config: IslandSlotRuntimeConfig
    ): Boolean {
        val mediaInfo = MediaMetadataHelper.getMediaInfo(cv.context, packageName, HookLogger)
        IslandHostFacade.updateHostGlow(cv, mediaInfo.albumArt, prefs)
        IslandHostFacade.updateProgressGlow(cv, packageName, mediaInfo, prefs)
        val leftChanged = updateSlot(cv, IslandProbeUtils.LEFT_TEST_VIEW_TAG, config.leftMode, prefs, config, mediaInfo)
        val rightChanged = updateSlot(cv, IslandProbeUtils.RIGHT_TEST_VIEW_TAG, config.rightMode, prefs, config, mediaInfo)
        val previewChanged = updateEndOfSongPreview(cv, packageName, prefs, config, LyriconDataBridge.currentPosition)
        return leftChanged || rightChanged || previewChanged
    }

    private fun updateLyricContentForView(
        cv: ViewGroup,
        prefs: android.content.SharedPreferences,
        config: IslandSlotRuntimeConfig
    ): Boolean {
        val packageName = LyriconDataBridge.currentLyricPackageName.orEmpty()
        if (updateEndOfSongPreview(cv, packageName, prefs, config, LyriconDataBridge.currentPosition)) {
            return true
        }
        if (config.adjacentBackgroundTranslation && config.supportsAdjacentBackgroundTranslation) {
            val mediaInfo = MediaMetadataHelper.getMediaInfo(cv.context, packageName, HookLogger)
            val leftChanged = updateSlot(cv, IslandProbeUtils.LEFT_TEST_VIEW_TAG, config.leftMode, prefs, config, mediaInfo)
            val rightChanged = updateSlot(cv, IslandProbeUtils.RIGHT_TEST_VIEW_TAG, config.rightMode, prefs, config, mediaInfo)
            return leftChanged || rightChanged
        }
        val leftChanged = updateLyricSlot(cv, IslandProbeUtils.LEFT_TEST_VIEW_TAG, config.leftMode, prefs, config)
        val rightChanged = updateLyricSlot(cv, IslandProbeUtils.RIGHT_TEST_VIEW_TAG, config.rightMode, prefs, config)
        return leftChanged || rightChanged
    }

    private fun updateLyricSlot(
        cv: ViewGroup,
        tag: String,
        mode: Int,
        prefs: android.content.SharedPreferences,
        config: IslandSlotRuntimeConfig
    ): Boolean {
        if (isSlotReservedByNextSongPreview(cv, tag)) return false
        if (mode != 7) return false
        val view = cv.findViewWithTag<View>(tag) ?: return false
        val line = IslandSlotContentAssembler.buildSlotLyricLine(
            view = view,
            prefs = prefs,
            config = config,
            isLeft = tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
        )
        return IslandSlotContentAssembler.applyLyricLineContent(
            view = view,
            prefs = prefs,
            config = config,
            lineOverride = line,
            playbackActive = playbackActive
        )
    }

    private fun updateSlot(
        cv: ViewGroup,
        tag: String,
        mode: Int,
        prefs: android.content.SharedPreferences,
        config: IslandSlotRuntimeConfig,
        mediaInfo: MediaMetadataHelper.MediaInfo
    ): Boolean {
        if (isSlotReservedByNextSongPreview(cv, tag)) return false
        val view = cv.findViewWithTag<View>(tag) ?: return false
        val isLeft = tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
        val adjacentTranslation = IslandSlotContentAssembler.buildAdjacentTranslationLine(
            prefs = prefs,
            config = config,
            isLeft = isLeft
        )
        val effectiveMode = if (adjacentTranslation != null) 7 else mode
        val lineOverride = adjacentTranslation ?: if (effectiveMode == 7) {
            IslandSlotContentAssembler.buildSlotLyricLine(
                view = view,
                prefs = prefs,
                config = config,
                isLeft = isLeft
            )
        } else {
            null
        }
        return IslandSlotContentAssembler.applySlotContent(
            view = view,
            prefs = prefs,
            config = config,
            mode = effectiveMode,
            lineOverride = lineOverride,
            playbackActive = playbackActive,
            mediaInfo = mediaInfo
        )
    }

    private fun isSlotReservedByNextSongPreview(cv: ViewGroup, tag: String): Boolean {
        val state = synchronized(nextSongPreviewActive) {
            nextSongPreviewActive[cv]
        } ?: return false
        return NextSongPreviewPolicy.reservesSlot(
            previewStyle = state.style,
            targetIsLeft = state.targetIsLeft,
            slotIsLeft = tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
        )
    }

    private fun updateEndOfSongPreview(
        cv: ViewGroup,
        packageName: String,
        prefs: android.content.SharedPreferences,
        config: IslandSlotRuntimeConfig,
        position: Long
    ): Boolean {
        val mediaInfo = MediaMetadataHelper.getMediaInfo(cv.context, packageName, HookLogger)
        val song = LyriconDataBridge.currentSong
        val duration = NextSongPreviewPolicy.resolveTrackDuration(
            mediaDurationMs = mediaInfo.duration,
            lyricDurationMs = song?.duration ?: -1L
        )
        val lastLyricStart = song?.lyrics?.maxOfOrNull { it.begin } ?: -1L
        val lastSyllableEnd = song?.lyrics
            .orEmpty()
            .asSequence()
            .flatMap { line ->
                sequenceOf(line.words.orEmpty(), line.secondaryWords.orEmpty()).flatten()
            }
            .mapNotNull { word ->
                when {
                    word.end > word.begin -> word.end
                    word.duration > 0L -> word.begin + word.duration
                    else -> null
                }
            }
            .maxOrNull()
        val shouldShow = config.nextSongPreviewEnabled && NextSongPreviewPolicy.shouldShow(
            positionMs = position,
            durationMs = duration,
            lastLyricStartMs = lastLyricStart,
            lastSyllableEndMs = lastSyllableEnd,
            previewDurationMs = config.nextSongDurationMs,
            force = config.shouldForceNextSongPreview
        )
        var previewState = synchronized(nextSongPreviewActive) {
            nextSongPreviewActive[cv]
        }

        if (!shouldShow) {
            synchronized(nextSongPreviewFailures) { nextSongPreviewFailures.remove(cv) }
            if (previewState == null) return false
            synchronized(nextSongPreviewActive) { nextSongPreviewActive.remove(cv) }
            updateSlot(cv, IslandProbeUtils.LEFT_TEST_VIEW_TAG, config.leftMode, prefs, config, mediaInfo)
            updateSlot(cv, IslandProbeUtils.RIGHT_TEST_VIEW_TAG, config.rightMode, prefs, config, mediaInfo)
            HookLogger.i("BaseIslandRenderer", "已结束下首歌曲信息预览")
            return false
        }

        val expectedTargetIsLeft = if (
            config.nextSongPreviewStyle == RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF
        ) {
            config.halfPreviewTargetIsLeft
        } else {
            null
        }
        val previewStateMatches = previewState?.let { state ->
            state.style == config.nextSongPreviewStyle &&
                state.targetIsLeft == expectedTargetIsLeft
        } == true
        if (!previewStateMatches) {
            synchronized(nextSongPreviewActive) { nextSongPreviewActive.remove(cv) }
            previewState = null
        } else if (
            config.nextSongPreviewStyle == RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL
        ) {
            return true
        }

        if (previewState == null) {
            val nextLookup = MediaMetadataHelper.getNextMediaLookup(cv.context, packageName, mediaInfo)
            val nextSong = nextLookup.mediaInfo
            if (nextSong.title.isBlank()) {
                val failureSignature = "${mediaInfo.title}|$duration|${nextLookup.reason}"
                val shouldLog = synchronized(nextSongPreviewFailures) {
                    if (nextSongPreviewFailures[cv] == failureSignature) {
                        false
                    } else {
                        nextSongPreviewFailures[cv] = failureSignature
                        true
                    }
                }
                if (shouldLog) {
                    HookLogger.w(
                        "BaseIslandRenderer",
                        "无法显示下首歌曲信息：reason=${nextLookup.reason}, " +
                            "当前歌曲=${mediaInfo.title}",
                    )
                }
                return false
            }
            synchronized(nextSongPreviewFailures) { nextSongPreviewFailures.remove(cv) }
            val createdState = NextSongPreviewState(
                style = config.nextSongPreviewStyle,
                targetIsLeft = expectedTargetIsLeft,
                nextSong = nextSong
            )
            previewState = createdState
            synchronized(nextSongPreviewActive) { nextSongPreviewActive[cv] = createdState }
            HookLogger.i(
                "BaseIslandRenderer",
                "开始下首歌曲信息预览: style=${config.nextSongPreviewStyle}, " +
                    "target=${expectedTargetIsLeft?.let { if (it) "left" else "right" } ?: "full"}, " +
                    "position=$position, duration=$duration, next=${nextSong.title}, " +
                    "source=${nextLookup.source}"
            )
        }
        val activePreviewState = previewState

        if (activePreviewState.style == RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF) {
            val targetIsLeft = activePreviewState.targetIsLeft ?: return false
            val targetTag = if (targetIsLeft) {
                IslandProbeUtils.LEFT_TEST_VIEW_TAG
            } else {
                IslandProbeUtils.RIGHT_TEST_VIEW_TAG
            }
            val otherTag = if (targetIsLeft) {
                IslandProbeUtils.RIGHT_TEST_VIEW_TAG
            } else {
                IslandProbeUtils.LEFT_TEST_VIEW_TAG
            }
            updateSlot(
                cv = cv,
                tag = otherTag,
                mode = config.modeForTag(otherTag),
                prefs = prefs,
                config = config,
                mediaInfo = mediaInfo
            )
            if (previewStateMatches) return true
            val target = cv.findViewWithTag<View>(targetTag) ?: run {
                synchronized(nextSongPreviewActive) { nextSongPreviewActive.remove(cv) }
                return false
            }
            IslandSlotContentAssembler.applyHalfNextSongPreviewContent(
                view = target,
                prefs = prefs,
                config = config,
                nextSong = activePreviewState.nextSong,
                label = "下一首",
                playbackActive = playbackActive
            )
            return true
        }

        val left = cv.findViewWithTag<View>(IslandProbeUtils.LEFT_TEST_VIEW_TAG)
        val right = cv.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_VIEW_TAG)
        if (left == null || right == null) return false
        IslandSlotContentAssembler.applyFullNextSongPreviewContent(
            left, prefs, config, isLeft = true, nextSong = activePreviewState.nextSong, label = "下一首",
            playbackActive = playbackActive
        )
        IslandSlotContentAssembler.applyFullNextSongPreviewContent(
            right, prefs, config, isLeft = false, nextSong = activePreviewState.nextSong, label = "下一首",
            playbackActive = playbackActive
        )
        return true
    }

}
