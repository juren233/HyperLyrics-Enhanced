package com.juren233.hyperlyricsenhanced.root.island.renderer

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.view.RichLyricLineView
import com.juren233.hyperlyricsenhanced.lyric.view.SpaceGateRichLyricLineView
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.SystemUiScreenStateMonitor
import com.juren233.hyperlyricsenhanced.root.island.IslandAlbumCoverStyleHooker
import com.juren233.hyperlyricsenhanced.root.island.IslandContentUpdateCoordinator
import com.juren233.hyperlyricsenhanced.root.island.IslandHostFacade
import com.juren233.hyperlyricsenhanced.root.island.IslandHostRetirementPolicy
import com.juren233.hyperlyricsenhanced.root.island.IslandPureMusicGate
import com.juren233.hyperlyricsenhanced.root.island.IslandLyricTextInjector
import com.juren233.hyperlyricsenhanced.root.island.IslandMediaReinstater
import com.juren233.hyperlyricsenhanced.root.island.IslandMusicWaveColorHooker
import com.juren233.hyperlyricsenhanced.root.island.IslandProbeUtils
import com.juren233.hyperlyricsenhanced.root.island.IslandProgressGlowController
import com.juren233.hyperlyricsenhanced.root.island.IslandReattachAssistant
import com.juren233.hyperlyricsenhanced.root.island.IslandReflect
import com.juren233.hyperlyricsenhanced.root.island.IslandSlotContentAssembler
import com.juren233.hyperlyricsenhanced.root.island.IslandSlotRuntimeConfig
import com.juren233.hyperlyricsenhanced.root.island.IslandViewRegistry
import com.juren233.hyperlyricsenhanced.root.island.IslandViewRecoveryPolicy
import com.juren233.hyperlyricsenhanced.root.island.IslandViewHelper
import com.juren233.hyperlyricsenhanced.root.island.NextSongPreviewPolicy
import com.juren233.hyperlyricsenhanced.root.utils.DisplayDiagnosticLogger
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.WeakHashMap

object BaseIslandRenderer : IslandRenderer {

    @Volatile private var retiredForReload = false

    internal fun beginHotReload() {
        retiredForReload = true
        mainHandler.removeCallbacksAndMessages(null)
        IslandContentUpdateCoordinator.release()
        IslandReflect.clear()
    }

    private const val REFRESH_DEBOUNCE_MS = 32L
    private val SCREEN_ON_REFRESH_DELAYS_MS = longArrayOf(0L, 120L, 400L, 900L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { performRefreshActiveIsland() }
    // Main-thread preference refreshes coalesce, but must survive a subsequent
    // ordinary content refresh replacing the debounce runnable.
    private var dynamicWidthRefreshPending = false
    // Distinguishes a full content refresh from a width-only recalculation. Width-only
    // refreshes must not reset content caches: re-applying the same line on both slots
    // resets their progress/marquee state, which reads as the two sides "refreshing".
    private var fullRefreshPending = false
    private val pauseTransitionGuard = IslandPauseTransitionGuard()
    private val pauseRestoreRunnable = Runnable { commitDeferredNativeRestore() }
    private val nextSongPreviewActive = WeakHashMap<ViewGroup, NextSongPreviewState>()
    private val nextSongPreviewFailures = WeakHashMap<ViewGroup, String>()
    private var lyricTimingSong: Song? = null
    private var lyricTimingLastLineStart = -1L
    private var lyricTimingLastSyllableEnd: Long? = null

    // 宿主仍附着但注入锚点丢失时重注入必败；连续失败计数用于区分暂态窗口
    // （fake/real 过渡、原生重排）与结构性失效，达到阈值才注销并触发重挂。
    private val injectionRecoveryFailures = WeakHashMap<ViewGroup, Int>()

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

    // 息屏期间跳过了逐字推进，亮屏后首个位置更新要先对齐而不是补动画。
    @Volatile
    private var pendingScreenOnSeek = false

    /**
     * Source lifecycle events are the authority for lyric rendering state.
     * Hook paths must not re-query MediaSession here: during a lyric refresh the source can
     * already be stopped while the player session still reports STATE_PLAYING.
     */
    fun shouldRenderInjectedIsland(): Boolean {
        val prefs = HookEntry.instance?.prefs ?: return false
        if (!prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_HYPER_ISLAND)) {
            return false
        }
        val behavior = prefs.getInt(
            RootConstants.KEY_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE,
            RootConstants.DEFAULT_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE
        )
        if (!(playbackActive || behavior != 0 || pauseTransitionGuard.nativeRestorePending)) return false
        return !IslandPureMusicGate.isActive(prefs)
    }

    /**
     * 纯音乐且选了“系统短岛”：不注入歌词，原生岛照常显示；
     * 边缘光效与律动取色仍要继续处理，所以与暂停回退原生区分开。
     */
    fun isPureMusicNativeActive(): Boolean {
        val prefs = HookEntry.instance?.prefs ?: return false
        if (!prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_HYPER_ISLAND)) {
            return false
        }
        val behavior = prefs.getInt(
            RootConstants.KEY_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE,
            RootConstants.DEFAULT_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE
        )
        if (!(playbackActive || behavior != 0 || pauseTransitionGuard.nativeRestorePending)) return false
        return IslandPureMusicGate.isActive(prefs)
    }

    private fun refreshPureMusicNative(prefs: android.content.SharedPreferences) {
        val lyricPkg = LyriconDataBridge.currentLyricPackageName?.takeIf { it.isNotEmpty() } ?: return
        IslandViewRegistry.snapshotAttached(lyricPkg).forEach { (cv, _) ->
            cv.post {
                if (retiredForReload || IslandContentUpdateCoordinator.deferContent(cv)) return@post
                // 只在还留有注入内容时清理：clearInjectedViews 会连带清掉进度光效，重复清理会让光效闪烁。
                if (IslandLyricTextInjector.hasInjectedLyricView(cv)) IslandHostFacade.clearAndRefresh(cv)
                val mediaInfo = MediaMetadataHelper.getMediaInfo(cv.context, lyricPkg, HookLogger)
                IslandMusicWaveColorHooker.onMediaArtworkUpdated(mediaInfo)
                IslandHostFacade.updateHostGlow(cv, mediaInfo.albumArt, prefs)
                IslandHostFacade.updateProgressGlow(cv, lyricPkg, mediaInfo, prefs)
            }
        }
    }

    fun currentPlaybackActive(): Boolean = playbackActive

    override fun refreshActiveIsland() {
        if (retiredForReload) return
        fullRefreshPending = true
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS)
    }

    fun onScreenInteractive() {
        if (retiredForReload) return
        pendingScreenOnSeek = true
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
        DisplayDiagnosticLogger.logLazy(
            channel = "ISLAND",
            result = "pending",
            reason = "screen_on_refresh_scheduled",
            dedupeKey = "ISLAND/screen_on",
        ) { "attempts=${SCREEN_ON_REFRESH_DELAYS_MS.size}" }
    }

    fun onScreenNonInteractive() {
        screenRefreshGeneration++
        pendingScreenOnSeek = false
        DisplayDiagnosticLogger.clear("ISLAND/screen_on")
    }

    fun refreshDynamicWidth() {
        if (retiredForReload) return
        dynamicWidthRefreshPending = true
        scheduleRefresh()
    }

    private fun performRefreshActiveIsland() {
        val refreshWidth = dynamicWidthRefreshPending
        // Only a dynamic-limit width recalculation is pending: content is untouched, so the
        // content signature/preview caches must stay intact. Clearing them forces both slots
        // to re-apply their current line, which is the visible "两侧内容不断刷新更新" churn.
        val fullRefresh = fullRefreshPending || !refreshWidth
        if (BuildConfig.DEBUG) {
            HookLogger.dLazy("IslandDynamicLimit") { "刷新类型: full=$fullRefresh width=$refreshWidth" }
        }
        dynamicWidthRefreshPending = false
        fullRefreshPending = false
        val prefs = HookEntry.instance?.prefs ?: run {
            DisplayDiagnosticLogger.log("ISLAND", "skipped", "preferences_unavailable")
            return
        }
        if (!prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_HYPER_ISLAND)) {
            clearAllViews()
            DisplayDiagnosticLogger.log("ISLAND", "hidden", "feature_disabled")
            return
        }
        if (!shouldRenderInjectedIsland()) {
            if (isPureMusicNativeActive()) {
                refreshPureMusicNative(prefs)
                DisplayDiagnosticLogger.log("ISLAND", "hidden", "pure_music_system_compact")
                return
            }
            clearActiveViewsForPause()
            DisplayDiagnosticLogger.log("ISLAND", "hidden", "pause_policy")
            return
        }

        val lyricPkg = LyriconDataBridge.currentLyricPackageName?.takeIf { it.isNotEmpty() } ?: run {
            DisplayDiagnosticLogger.log("ISLAND", "skipped", "package_missing")
            return
        }

        if (fullRefresh) {
            IslandSlotContentAssembler.invalidate()
            synchronized(nextSongPreviewActive) { nextSongPreviewActive.clear() }
            synchronized(nextSongPreviewFailures) { nextSongPreviewFailures.clear() }
        }
        val activeViews = IslandViewRegistry.snapshotAttached(lyricPkg)
        if (activeViews.isEmpty() && IslandReattachAssistant.tryReattach(lyricPkg)) {
            // 重挂后 register() 已安排补发刷新，本次直接返回避免竞态双写。
            return
        }
        if (activeViews.isEmpty()) {
            DisplayDiagnosticLogger.log("ISLAND", "skipped", "no_attached_view")
            // 双播冲突断供窗口：重挂无目标时用最后一次媒体数据驱动原生重建媒体岛。
            IslandMediaReinstater.reinstateIfEligible(lyricPkg, reason = "no_attached_view")
            return
        }
        val config = IslandSlotRuntimeConfig.from(prefs)
        activeViews.forEach { (cv, _) ->
            cv.post {
                if (retiredForReload || IslandContentUpdateCoordinator.deferContent(cv, refreshWidth)) return@post
                // Existing content is refreshed below. Reconfiguring it here forces
                // applySlotContent() to report a change on every screen-on retry, which
                // incorrectly turns four content refreshes into four host width relayouts.
                val injectionChanged = IslandLyricTextInjector.injectSlots(
                    cv,
                    reconfigureExisting = false,
                )
                if (injectionChanged && !refreshWidth) {
                    IslandHostFacade.triggerSystemRelayout(cv)
                } else {
                    IslandHostFacade.applyHostSettings(cv, prefs)
                }
                val contentChanged = updateContentForView(cv, lyricPkg, prefs, config)
                // Also force measurement when turning dynamic width OFF: the
                // normal width hook intentionally only invalidates while ON.
                // Apply current content/hug options before measuring either way.
                if (refreshWidth) {
                    IslandViewHelper.forceLayoutIslandAreas(cv)
                }
                if (refreshWidth || (config.dynamicWidthEnabled && contentChanged)) {
                    IslandHostFacade.triggerSystemRelayout(cv)
                }
                val injected = IslandLyricTextInjector.hasInjectedLyricView(cv)
                val hostDiagnostic = if (BuildConfig.DEBUG) runCatching {
                    val shown = cv.isShown
                    val attached = cv.isAttachedToWindow
                    val visibility = cv.visibility
                    val width = cv.width
                    val height = cv.height
                    ("hostShown=$shown, hostAttached=$attached, hostVisibility=$visibility, " +
                        "hostWidth=$width, hostHeight=$height") to
                        "$injected|$shown|$attached|$visibility|${width > 0}|${height > 0}"
                }.getOrDefault("hostState=unavailable" to "unavailable") else "" to ""
                DisplayDiagnosticLogger.logLazy(
                    channel = "ISLAND",
                    result = if (injected) "present" else "skipped",
                    reason = if (injected) "injected_view_present" else "injection_unavailable",
                    dedupeKey = "ISLAND/refresh",
                    infoState = hostDiagnostic.second,
                ) {
                    "targetViews=${activeViews.size}, injectionChanged=$injectionChanged, " +
                        "playbackActive=$playbackActive, ${hostDiagnostic.first}"
                }
            }
        }

        HookLogger.dLazy("BaseIslandRenderer") { "已刷新活动媒体岛: 数量=${activeViews.size}" }
        DisplayDiagnosticLogger.logLazy(
            channel = "ISLAND",
            result = "pending",
            reason = "refresh_scheduled",
            dedupeKey = "ISLAND/refresh_pending",
        ) { "targetViews=${activeViews.size}, playbackActive=$playbackActive" }
    }

    override fun updateLyricLine() {
        if (retiredForReload) return
        if ((HookEntry.instance?.prefs?.getBoolean(RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_HYPER_ISLAND)) != true) {
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
        if (activeViews.isEmpty() && IslandReattachAssistant.tryReattach(lyricPkg)) {
            // 重挂后 register() 已安排补发刷新，本次直接返回避免竞态双写。
            return
        }
        if (activeViews.isEmpty()) {
            DisplayDiagnosticLogger.log("ISLAND", "skipped", "no_attached_view")
            // 双播冲突断供窗口：重挂无目标时用最后一次媒体数据驱动原生重建媒体岛。
            IslandMediaReinstater.reinstateIfEligible(lyricPkg, reason = "no_attached_view")
            return
        }
        activeViews.forEach { (cv, _) ->
                cv.post {
                    if (retiredForReload || IslandContentUpdateCoordinator.deferContent(cv)) return@post
                    val recoveryAction = IslandViewRecoveryPolicy.decide(
                        hasRegisteredHost = true,
                        hasInjectedView = IslandLyricTextInjector.hasInjectedLyricView(cv),
                    )
                    var injectionRecovered = false
                    if (recoveryAction == IslandViewRecoveryPolicy.Action.REINJECT_REGISTERED_HOST) {
                        val injectionChanged = IslandLyricTextInjector.injectSlots(
                            cv,
                            reconfigureExisting = false,
                            suppressAnimation = true,
                        )
                        injectionRecovered = IslandLyricTextInjector.hasInjectedLyricView(cv)
                        if (!injectionRecovered) {
                            val consecutiveFailures = (injectionRecoveryFailures[cv] ?: 0) + 1
                            if (BuildConfig.DEBUG) {
                                HookLogger.d(
                                    "IslandRecoveryDiag",
                                    "注入恢复失败: host=${cv.javaClass.simpleName}@${System.identityHashCode(cv).toString(16)}, " +
                                        "锚点=${IslandLyricTextInjector.describeAnchorState(cv)}, " +
                                        "连续失败=$consecutiveFailures/${IslandHostRetirementPolicy.RETIRE_THRESHOLD}",
                                )
                            }
                            if (IslandHostRetirementPolicy.shouldRetire(consecutiveFailures)) {
                                injectionRecoveryFailures.remove(cv)
                                IslandViewRegistry.unregister(cv)
                                val reattached = IslandReattachAssistant.tryReattach(lyricPkg)
                                HookLogger.i(
                                    "IslandRecovery",
                                    "连续注入失败已达阈值，已注销失效宿主并尝试重挂: package=$lyricPkg, " +
                                        "阈值=${IslandHostRetirementPolicy.RETIRE_THRESHOLD}, 重挂=$reattached, " +
                                        "剩余注册=${IslandViewRegistry.snapshotAttached(lyricPkg).size}",
                                )
                            } else {
                                injectionRecoveryFailures[cv] = consecutiveFailures
                            }
                            DisplayDiagnosticLogger.logLazy(
                                channel = "ISLAND",
                                result = "skipped",
                                reason = "injected_view_recovery_failed",
                                dedupeKey = "ISLAND/line",
                            ) {
                                "targetViews=${activeViews.size}, injectionChanged=$injectionChanged, " +
                                    "consecutiveFailures=$consecutiveFailures"
                            }
                            return@post
                        }
                        DisplayDiagnosticLogger.logLazy(
                            channel = "ISLAND",
                            result = "shown",
                            reason = "injected_view_recovered",
                            dedupeKey = "ISLAND/line",
                        ) { "targetViews=${activeViews.size}, injectionChanged=$injectionChanged" }
                    }
                    val contentChanged = updateLyricContentForView(cv, prefs, config)
                    if (injectionRecovered) {
                        IslandHostFacade.triggerSystemRelayout(cv)
                    } else if (config.dynamicWidthEnabled && contentChanged) {
                        IslandHostFacade.triggerLyricContentRelayout(cv)
                    }
                    // 任意健康路径（重注入成功或视图本就存在）都清零暂态失败计数。
                    injectionRecoveryFailures.remove(cv)
                    DisplayDiagnosticLogger.logLazy(
                        channel = "ISLAND",
                        result = "shown",
                        reason = "lyric_line_updated",
                        dedupeKey = "ISLAND/line",
                    ) { "targetViews=${activeViews.size}, playbackActive=$playbackActive" }
                }
            }
    }

    override fun updatePosition(position: Long) {
        if (retiredForReload) return
        if (pendingScreenOnSeek) {
            pendingScreenOnSeek = false
            updatePositionForActiveViews(position, isSeek = true)
            return
        }
        updatePositionForActiveViews(position, isSeek = false)
    }

    override fun seekTo(position: Long) {
        if (retiredForReload) return
        pendingScreenOnSeek = false
        updatePositionForActiveViews(position, isSeek = true)
    }

    private fun updatePositionForActiveViews(position: Long, isSeek: Boolean) {
        // 息屏时超级岛不可见，位置、光效和下一首预览都不必处理；息屏歌词走独立路径。
        if (!SystemUiScreenStateMonitor.isInteractive()) return
        val prefs = HookEntry.instance?.prefs ?: return
        if (!prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_HYPER_ISLAND)) return
        if (!shouldRenderInjectedIsland()) {
            if (isPureMusicNativeActive()) {
                val pkg = LyriconDataBridge.currentLyricPackageName ?: return
                IslandViewRegistry.snapshotAttached(pkg).forEach { (cv, _) ->
                    cv.post { if (!retiredForReload) IslandHostFacade.updateProgressGlow(cv, pkg, prefs) }
                }
            }
            return
        }
        val lyricPkg = LyriconDataBridge.currentLyricPackageName ?: return

        IslandViewRegistry.snapshotAttachedInjectedViews(lyricPkg)
            .forEach { (cv, indexedViews) ->
                cv.post {
                    if (retiredForReload) return@post
                    if (IslandContentUpdateCoordinator.deferContent(cv, isSeek = isSeek)) {
                        // 形变期间只放行逐字进度，新句不再停在开头等宿主稳定；seek 与其余内容仍整批延后。
                        if (!isSeek && IslandContentUpdateCoordinator.isRealContent(cv)) {
                            advanceWordProgress(cv, indexedViews, position)
                        }
                        return@post
                    }
                    val config = IslandSlotRuntimeConfig.from(prefs)
                    val interludeMinimal = IslandSlotContentAssembler.isInterludeMinimalDisplayActive(config)
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
                    if (interludeMinimal) {
                        IslandProgressGlowController.clear(cv)
                    } else {
                        IslandHostFacade.updateProgressGlow(cv, lyricPkg, prefs)
                    }
                    val previewChanged = updateEndOfSongPreview(
                        cv,
                        lyricPkg,
                        prefs,
                        config,
                        position
                    )
                    if (config.dynamicWidthEnabled && previewChanged) {
                        IslandHostFacade.triggerLyricContentRelayout(cv)
                    }
                }
            }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        if (retiredForReload) return
        val prefs = HookEntry.instance?.prefs ?: return
        if (!prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_HYPER_ISLAND)) {
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
                    if (IslandContentUpdateCoordinator.deferContent(cv)) return@post
                    if (!retiredForReload) IslandHostFacade.clearAndRefresh(cv)
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

    private fun advanceWordProgress(cv: ViewGroup, indexedViews: List<View>, position: Long) {
        val views = indexedViews.ifEmpty {
            listOf(
                cv.findViewWithTag<View>(IslandProbeUtils.LEFT_TEST_VIEW_TAG),
                cv.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_VIEW_TAG),
            )
        }
        views.forEach { view ->
            when (view) {
                is RichLyricLineView -> view.advanceWordProgress(position)
                is SpaceGateRichLyricLineView -> view.advanceWordProgress(position)
            }
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

    internal fun releaseForReload() {
        mainHandler.removeCallbacksAndMessages(null)
        IslandContentUpdateCoordinator.release()
        screenRefreshGeneration++
        pauseTransitionGuard.reset()
        playbackActive = false
        clearedByPause = true
        IslandViewRegistry.allRootsForReload().forEach { root ->
            com.juren233.hyperlyricsenhanced.root.island.IslandTextHookerSupport
                .clearInjectedIsland(root, suppressRelayout = true)
        }
        IslandViewRegistry.releaseForReload()
        nextSongPreviewActive.clear()
        IslandSlotContentAssembler.clearAllNextSongPreviewContent()
        injectionRecoveryFailures.clear()
    }

    override fun clearAllViews() {
        if (retiredForReload) return
        IslandContentUpdateCoordinator.release()
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.removeCallbacks(pauseRestoreRunnable)
        dynamicWidthRefreshPending = false
        fullRefreshPending = false
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

    /** Called synchronously inside the settled batch; it deliberately reads the latest model. */
    internal fun refreshAfterIslandSettled(cv: ViewGroup, isSeek: Boolean) {
        if (retiredForReload || !cv.isAttachedToWindow) return
        val prefs = HookEntry.instance?.prefs ?: return
        if (!shouldRenderInjectedIsland()) {
            IslandHostFacade.clearAndRefresh(cv)
            return
        }
        val media = com.juren233.hyperlyricsenhanced.root.island.IslandTextHookerSupport
            .extractMediaInfoFromContentOrReal(cv) ?: return
        val packageName = LyriconDataBridge.currentLyricPackageName ?: return
        if (media.packageName != packageName) return
        val config = IslandSlotRuntimeConfig.from(prefs)
        val injected = IslandLyricTextInjector.injectSlots(cv, reconfigureExisting = false)
        val position = LyriconDataBridge.currentPosition
        // Only a real seek cancels the old transition, before the latest content starts its own.
        if (isSeek) {
            updateViewPosition(cv.findViewWithTag(IslandProbeUtils.LEFT_TEST_VIEW_TAG), position, isSeek = true)
            updateViewPosition(cv.findViewWithTag(IslandProbeUtils.RIGHT_TEST_VIEW_TAG), position, isSeek = true)
        }
        val changed = updateContentForView(cv, packageName, prefs, config)
        updateViewPosition(cv.findViewWithTag(IslandProbeUtils.LEFT_TEST_VIEW_TAG), position, isSeek = false)
        updateViewPosition(cv.findViewWithTag(IslandProbeUtils.RIGHT_TEST_VIEW_TAG), position, isSeek = false)
        if (IslandContentUpdateCoordinator.isRealContent(cv)) {
            IslandLyricTextInjector.resumeInjectedContentMotion(cv, playbackActive)
        } else {
            IslandLyricTextInjector.freezeInjectedLyricProgress(cv, position)
        }
        if (injected || (config.dynamicWidthEnabled && changed)) {
            IslandHostFacade.triggerLyricContentRelayout(cv)
        }
    }

    private fun updateContentForView(
        cv: ViewGroup,
        packageName: String,
        prefs: android.content.SharedPreferences,
        config: IslandSlotRuntimeConfig
    ): Boolean {
        val mediaInfo = MediaMetadataHelper.getMediaInfo(cv.context, packageName, HookLogger)
        // 律动取色与歌词/光效同源：内容刷新即喂入最新媒体信息，切歌后颜色实时跟随
        IslandMusicWaveColorHooker.onMediaArtworkUpdated(mediaInfo)
        IslandHostFacade.updateHostGlow(cv, mediaInfo.albumArt, prefs)
        if (IslandSlotContentAssembler.isInterludeMinimalDisplayActive(config)) {
            IslandProgressGlowController.clear(cv)
        } else {
            IslandHostFacade.updateProgressGlow(cv, packageName, mediaInfo, prefs)
        }
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
        if (IslandSlotContentAssembler.isInterludeMinimalDisplayActive(config)) {
            // 换句路径也要处理非歌词槽，否则单侧模式下残留的歌曲信息不会被隐藏。
            val mediaInfo = MediaMetadataHelper.getMediaInfo(cv.context, packageName, HookLogger)
            val leftChanged = updateSlot(cv, IslandProbeUtils.LEFT_TEST_VIEW_TAG, config.leftMode, prefs, config, mediaInfo)
            val rightChanged = updateSlot(cv, IslandProbeUtils.RIGHT_TEST_VIEW_TAG, config.rightMode, prefs, config, mediaInfo)
            return leftChanged || rightChanged
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
        if (isSlotReservedByNextSongPreview(cv, tag)) {
            if (BuildConfig.DEBUG) {
                HookLogger.i("BaseIslandRenderer", "[PreviewEndDiag] updateSlot $tag skipped: reserved")
            }
            return false
        }
        val view = cv.findViewWithTag<View>(tag) ?: run {
            if (BuildConfig.DEBUG) {
                HookLogger.i("BaseIslandRenderer", "[PreviewEndDiag] updateSlot $tag skipped: view not found")
            }
            return false
        }
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

    fun isSlotReservedByNextSongPreview(cv: ViewGroup, tag: String): Boolean {
        val state = synchronized(nextSongPreviewActive) {
            nextSongPreviewActive[cv]
        } ?: return false
        return NextSongPreviewPolicy.reservesSlot(
            previewStyle = state.style,
            targetIsLeft = state.targetIsLeft,
            slotIsLeft = tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
        )
    }

    /** 预览结束或无法显示时，先解除槽位内容归属，常规歌词才能写回；返回是否真的释放了标记。 */
    private fun clearNextSongPreviewContentMarkers(cv: ViewGroup): Boolean {
        if (!IslandSlotContentAssembler.hasAnyNextSongPreviewContent()) return false
        return IslandSlotContentAssembler.clearNextSongPreviewContent(
            listOf(
                cv.findViewWithTag(IslandProbeUtils.LEFT_TEST_VIEW_TAG),
                cv.findViewWithTag(IslandProbeUtils.RIGHT_TEST_VIEW_TAG),
            )
        )
    }

    private fun restoreSlotsAfterPreviewRelease(
        cv: ViewGroup,
        prefs: android.content.SharedPreferences,
        config: IslandSlotRuntimeConfig,
        mediaInfo: MediaMetadataHelper.MediaInfo
    ): Boolean {
        val leftRestored = updateSlot(cv, IslandProbeUtils.LEFT_TEST_VIEW_TAG, config.leftMode, prefs, config, mediaInfo)
        val rightRestored = updateSlot(cv, IslandProbeUtils.RIGHT_TEST_VIEW_TAG, config.rightMode, prefs, config, mediaInfo)
        return leftRestored || rightRestored
    }

    private fun refreshLyricTimingCache(song: Song?) {
        if (song === lyricTimingSong) return
        lyricTimingSong = song
        lyricTimingLastLineStart = song?.lyrics?.maxOfOrNull { it.begin } ?: -1L
        lyricTimingLastSyllableEnd = song?.lyrics
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
    }

    private fun updateEndOfSongPreview(
        cv: ViewGroup,
        packageName: String,
        prefs: android.content.SharedPreferences,
        config: IslandSlotRuntimeConfig,
        position: Long
    ): Boolean {
        if (IslandSlotContentAssembler.isInterludeMinimalDisplayActive(config)) return false
        var previewState = synchronized(nextSongPreviewActive) {
            nextSongPreviewActive[cv]
        }
        if (!config.nextSongPreviewEnabled && previewState == null) {
            synchronized(nextSongPreviewFailures) { nextSongPreviewFailures.remove(cv) }
            if (!clearNextSongPreviewContentMarkers(cv)) return false
            // 标记真的被释放时必须立刻写回歌词，不能等下一次进度回调。
            val mediaInfo = MediaMetadataHelper.getMediaInfo(cv.context, packageName, HookLogger)
            return restoreSlotsAfterPreviewRelease(cv, prefs, config, mediaInfo)
        }

        val song = LyriconDataBridge.currentSong
        refreshLyricTimingCache(song)
        // getMediaInfo 按包名缓存，元数据未变化时只有一次缓存读取。
        val mediaInfo = MediaMetadataHelper.getMediaInfo(cv.context, packageName, HookLogger)
        val duration = NextSongPreviewPolicy.resolveTrackDuration(
            mediaDurationMs = mediaInfo.duration,
            lyricDurationMs = song?.duration ?: -1L
        )
        val shouldShow = config.nextSongPreviewEnabled && NextSongPreviewPolicy.shouldShow(
            positionMs = position,
            durationMs = duration,
            lastLyricStartMs = lyricTimingLastLineStart,
            lastSyllableEndMs = lyricTimingLastSyllableEnd,
            previewDurationMs = config.nextSongDurationMs,
            force = config.shouldForceNextSongPreview
        )

        if (!shouldShow) {
            synchronized(nextSongPreviewFailures) { nextSongPreviewFailures.remove(cv) }
            val hadPreviewState = synchronized(nextSongPreviewActive) {
                nextSongPreviewActive.remove(cv) != null
            }
            val releasedSlots = clearNextSongPreviewContentMarkers(cv)
            // 整岛刷新会先清掉预览状态但保留槽位标记；这里只要标记被释放就必须恢复歌词，
            // 否则切歌/前奏期间“下一首”会一直留在岛上，直到下一句歌词到来。
            if (!hadPreviewState && !releasedSlots) return false
            if (BuildConfig.DEBUG && hadPreviewState) {
                val leftView = cv.findViewWithTag<View>(IslandProbeUtils.LEFT_TEST_VIEW_TAG)
                val rightView = cv.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_VIEW_TAG)
                HookLogger.i(
                    "BaseIslandRenderer",
                    "[PreviewEndDiag] cv=${System.identityHashCode(cv).toString(16)}, " +
                        "leftView=${leftView?.let { System.identityHashCode(it).toString(16) }}, " +
                        "rightView=${rightView?.let { System.identityHashCode(it).toString(16) }}, " +
                        "leftMode=${config.leftMode}, rightMode=${config.rightMode}, " +
                        "position=$position, duration=$duration, attached=${cv.isAttachedToWindow}"
                )
            }
            val restored = restoreSlotsAfterPreviewRelease(cv, prefs, config, mediaInfo)
            if (BuildConfig.DEBUG && hadPreviewState) {
                HookLogger.i(
                    "BaseIslandRenderer",
                    "[PreviewEndDiag] restored=$restored"
                )
            }
            if (hadPreviewState) HookLogger.i("BaseIslandRenderer", "已结束下首歌曲信息预览")
            return restored
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
            // 样式/目标侧变更会换槽：旧槽的预览归属必须先释放，否则常规歌词写不回去。
            clearNextSongPreviewContentMarkers(cv)
            synchronized(nextSongPreviewActive) { nextSongPreviewActive.remove(cv) }
            previewState = null
        } else if (
            config.nextSongPreviewStyle == RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL
        ) {
            return true
        }

        if (previewState == null) {
            // 歌词侧歌名只在歌词来自同一个播放器时才可信，否则会把别的播放器的歌当成当前曲。
            val lyricSong = song?.takeIf { LyriconDataBridge.currentLyricPackageName == packageName }
            val nextLookup = MediaMetadataHelper.getNextMediaLookup(
                context = cv.context,
                packageName = packageName,
                current = mediaInfo,
                lyricTitle = lyricSong?.name,
                lyricArtist = lyricSong?.artist,
            )
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
                return if (clearNextSongPreviewContentMarkers(cv)) {
                    restoreSlotsAfterPreviewRelease(cv, prefs, config, mediaInfo)
                } else {
                    false
                }
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
            // 预览必须保持到切歌：每次刷新都重申预览内容并重新认领槽位，
            // 视图被重建后也能自愈（内容未变时签名检查会直接跳过）。
            val target = cv.findViewWithTag<View>(targetTag) ?: run {
                synchronized(nextSongPreviewActive) { nextSongPreviewActive.remove(cv) }
                clearNextSongPreviewContentMarkers(cv)
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
