/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheUsage
import com.juren233.hyperlyricsenhanced.common.IslandMusicAppCatalog
import com.juren233.hyperlyricsenhanced.common.media.MediaSessionMetadataAdapter
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSink
import com.juren233.hyperlyricsenhanced.lyric.source.SourceSelectionAwareSink
import com.juren233.hyperlyricsenhanced.lyric.source.TimelineContent
import com.juren233.hyperlyricsenhanced.lyric.view.line.PositionUpdateDemand
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.root.LyricsAvailability
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.SystemUiEnhancementGate
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.DiagnosticDecisionGate
import com.juren233.hyperlyricsenhanced.root.utils.LyricRuntimeDiagnostics
import com.juren233.hyperlyricsenhanced.root.utils.RuntimeResourceCleanup
import com.juren233.hyperlyricsenhanced.root.utils.RuntimeCallbackGate
import com.juren233.hyperlyricsenhanced.root.utils.RuntimeResourceCleanupException
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity
import com.juren233.hyperlyricsenhanced.common.MediaSessionOnlineFallbackPreferences.Settings
import com.juren233.hyperlyricsenhanced.root.source.MediaSessionOnlineFallbackCoordinator
import com.juren233.hyperlyricsenhanced.root.source.MediaSessionOnlineFallbackPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * SystemUI 中唯一的歌词时间轴拥有者。
 *
 * 歌词源只能通过 [onTimelineContent] 提交一份完整、不可变的歌词内容；曲目身份校验、
 * 内容替换、播放状态、位置外推、逐行滚动与渲染清理由本类统一完成。来源回调里的
 * 位置与 seek 不再直接驱动渲染，而是作为源时钟锚点被本类接受（歌词同步时钟权威
 * 是歌词源；MediaSession 锚点作兜底与外推），全流程仍单点写入渲染层，不存在两套
 * 时钟并行写状态。
 */
class LocalTimelineDriver(
    private val anchor: SystemMediaPlaybackAnchor,
    private val renderSink: LyricSink,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    /** 确认某播放器正由歌词源提供内容（或为目录内音乐 App）时回调，用于自定义AOD焦点通知。 */
    private val onLyricPlayerConfirmed: (String) -> Unit = {},
) : LyricSink, SourceSelectionAwareSink, SystemMediaPlaybackAnchor.Listener {

    @Volatile
    private var activeSourceId: String? = null

    @Volatile
    private var appliedTrackKey: String? = null

    @Volatile
    private var appliedPackageName: String? = null

    @Volatile
    private var anchorPlaying: Boolean = false

    @Volatile
    private var hintPlaying: Boolean = false

    @Volatile
    private var hintPackage: String? = null

    private var anchorInactiveSinceMs: Long? = null
    private var sourceProgressWhileAnchorInactiveAtMs: Long = 0L

    /** 最近一次下发给渲染层的合成播放态；null=尚未下发过。 */
    private var renderedPlaying: Boolean? = null

    private var pendingContent: TimelineContent? = null
    private var displayedContent: TimelineContent? = null
    private var fallbackOwner: Any? = null
    private var primaryContentListener: (() -> Unit)? = null

    /** 每次运行只授权一个补充协调器，close 后排队回调也不能再发布。 */
    internal fun openOnlineFallback(): MediaSessionOnlineFallbackCoordinator.Timeline {
        val owner = Any()
        fallbackOwner = owner
        return object : MediaSessionOnlineFallbackCoordinator.Timeline {
            private var closed = false
            private var settings = Settings()
            private var sessionUsable = false
            private fun current() = !closed && !stopped && activeInstance === this@LocalTimelineDriver &&
                fallbackOwner === owner

            override fun hasPrimary(track: TrackIdentity): Boolean = current() &&
                TimelineContentPolicy.hasPrimary(activeSourceId, track, displayedContent, appliedTrackKey)

            override fun hasWordTimedContent(track: TrackIdentity): Boolean = current() &&
                TimelineContentPolicy.showsWordTimed(activeSourceId, track, displayedContent, appliedTrackKey)

            override fun hasFallback(track: TrackIdentity): Boolean {
                val shown = displayedContent ?: return false
                return current() && shown.sourceId == MediaSessionOnlineFallbackPolicy.SOURCE_ID &&
                    shown.track?.let { MediaSessionOnlineFallbackPolicy.sameLookupTrack(it, track) } == true
            }

            override fun authorize(settings: Settings, sessionUsable: Boolean) {
                if (!current()) return
                this.settings = settings
                this.sessionUsable = sessionUsable
                if (!MediaSessionOnlineFallbackPolicy.eligible(settings, anchor.currentTrack, sessionUsable)) {
                    clearFallback()
                } else {
                    val shown = displayedContent
                    val track = anchor.currentTrack
                    if (shown?.sourceId == MediaSessionOnlineFallbackPolicy.SOURCE_ID &&
                        track != null && shown.track != null &&
                        !MediaSessionOnlineFallbackPolicy.sameLookupTrack(shown.track, track)
                    ) prepareTrackTransition(track)
                }
            }

            override fun submit(content: TimelineContent) = runOnMain {
                if (current() && TimelineContentPolicy.admitsOnlineFallback(
                        activeSourceId, anchor.currentTrack, content, displayedContent, settings, sessionUsable,
                        appliedTrackKey,
                    )
                ) applyContent(content)
            }

            override fun submitAtNextLine(content: TimelineContent) = runOnMain {
                if (!current() || !TimelineContentPolicy.admitsFallbackSwitch(
                        activeSourceId, anchor.currentTrack, content, displayedContent, settings, sessionUsable,
                        appliedTrackKey,
                    )
                ) return@runOnMain
                val position = displayPosition()
                when (val plan = FallbackSwitchPolicy.plan(displayedContent?.song?.lyrics.orEmpty(), position)) {
                    FallbackSwitchPolicy.Plan.ApplyNow -> applyContent(content)
                    is FallbackSwitchPolicy.Plan.Defer -> {
                        // 当前句继续用已显示的来源，到下一句的起点再切过去，避免当前句割裂跳变。
                        pendingFallbackSwitch = PendingFallbackSwitch(content, plan.targetPosition)
                        diagnostic(
                            "补充歌词来源下一句切换: target=${plan.targetPosition}, position=$position, " +
                                "lines=${content.song?.lyrics?.size ?: 0}"
                        )
                    }
                }
            }

            override fun listen(onPrimaryChanged: () -> Unit) {
                if (current()) primaryContentListener = onPrimaryChanged
            }

            override fun close() {
                closed = true
                runOnMain {
                    if (fallbackOwner !== owner) return@runOnMain
                    fallbackOwner = null
                    primaryContentListener = null
                    clearFallback()
                }
            }
        }
    }

    private fun clearFallback() {
        if (displayedContent?.sourceId == MediaSessionOnlineFallbackPolicy.SOURCE_ID) {
            clearTimeline(showTrackFallback = true)
        }
    }
    private val appliedContentCache = LinkedHashMap<String, TimelineContent>()
    private var streamingTrackKey: String? = null
    private val streamingLines = linkedMapOf<Long, RichLyricLine>()
    private var positionJob: Job? = null
    private val positionScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val callbackGate = RuntimeCallbackGate()
    private val callbackGeneration = callbackGate.begin()
    private val stopped get() = !callbackGate.isCurrent(callbackGeneration)
    private val callbackToken = Any()
    private var stopCompleted = false
    private var cleanupFailure: RuntimeResourceCleanupException? = null

    init {
        registerActiveInstance(this)
    }

    fun start() {
        check(!stopped) { "A stopped timeline driver cannot be restarted" }
        anchor.start(this)
    }

    fun stop() {
        // Invalidate queued/in-flight callbacks immediately, even for an off-main caller.
        callbackGate.invalidate()
        fun release() {
            if (stopCompleted) {
                cleanupFailure?.let { throw it }
                return
            }
            stopCompleted = true
            val cleanup = RuntimeResourceCleanup()
            cleanup.attempt("timeline queued callbacks") { mainHandler.removeCallbacksAndMessages(callbackToken) }
            cleanup.attempt("timeline position loop") { stopPositionLoop() }
            cleanup.attempt("anchor listener") { anchor.removeListener(this) }
            activeSourceId = null
            appliedTrackKey = null
            appliedPackageName = null
            pendingContent = null
            pendingSameTrackReplace = null
            displayedContent = null
            fallbackOwner = null
            primaryContentListener = null
            appliedContentCache.clear()
            clearStreamingContent()
            resetSourceClock()
            displayClock.reset()
            anchorPlaying = false
            hintPlaying = false
            hintPackage = null
            anchorInactiveSinceMs = null
            sourceProgressWhileAnchorInactiveAtMs = 0L
            renderedPlaying = null
            // A delayed stop from the previous attempt must not clear its replacement's sink.
            if (clearActiveInstance(this)) cleanup.attempt("timeline render sink") { renderSink.onStop() }
            cleanupFailure = cleanup.failureOrNull()
            cleanupFailure?.let { throw it }
        }
        if (Looper.myLooper() == mainHandler.looper) {
            release()
        } else {
            check(mainHandler.post {
                runCatching { release() }.onFailure { HookLogger.e(TAG, "时间轴清理失败", it) }
            }) { "Timeline cleanup could not be scheduled" }
        }
    }

    /** 所有歌词渲染面都关闭时停止时间轴，不保留上一首内容。 */
    fun stopDriving() {
        runOnMain { clearTimeline(showTrackFallback = false) }
    }

    override fun onSourceSelected(sourceId: String) {
        runOnMain {
            if (activeSourceId == sourceId) return@runOnMain
            activeSourceId = sourceId
            pendingContent = null
            clearStreamingContent()
            resetSourceClock()
            clearTimeline(showTrackFallback = true)
            diagnostic("活动来源=$sourceId")
            primaryContentListener?.invoke()
        }
    }

    override fun onSourceStopped(sourceId: String) {
        runOnMain {
            if (activeSourceId != sourceId) return@runOnMain
            activeSourceId = null
            pendingContent = null
            clearStreamingContent()
            resetSourceClock()
            clearTimeline(showTrackFallback = false)
            primaryContentListener?.invoke()
        }
    }

    override fun onTimelineContent(content: TimelineContent) {
        runOnMain { handleTimelineContent(content) }
    }

    /** 兼容尚未改造完的完整 Song 回调；仍会转成统一内容，不允许直接写桥。 */
    override fun onSongChanged(song: Any?) {
        val sourceId = activeSourceId ?: return
        onTimelineContent(
            TimelineContent(
                sourceId = sourceId,
                track = anchor.currentTrack,
                song = song as? Song,
            )
        )
    }

    override fun onOnlineTranslationMatched(song: Any?) {
        val sourceId = activeSourceId ?: return
        onTimelineContent(
            TimelineContent(
                sourceId = sourceId,
                track = anchor.currentTrack,
                song = song as? Song,
                onlineTranslationMatched = true,
            )
        )
    }

    override fun onOnlineTranslationUnavailable(song: Any?) {
        runOnMain { renderSink.onOnlineTranslationUnavailable(song) }
    }

    override fun onStop() {
        runOnMain {
            if (displayedContent?.sourceId != MediaSessionOnlineFallbackPolicy.SOURCE_ID) {
                clearTimeline(showTrackFallback = true)
            }
            primaryContentListener?.invoke()
        }
    }

    // 逐行兼容来源只提交内容；显示、播放时钟与滚动仍归本 driver。
    override fun onLyricLine(line: Any?) {
        val richLine = line as? RichLyricLine ?: return
        runOnMain { applyStreamingLine(richLine) }
    }

    override fun onPlainText(text: String?) {
        val value = text?.takeIf { it.isNotBlank() } ?: return
        runOnMain {
            val position = displayPosition()?.coerceAtLeast(0L) ?: 0L
            clearStreamingContent()
            applyStreamingLine(
                RichLyricLine(
                    begin = position,
                    end = Long.MAX_VALUE,
                    duration = Long.MAX_VALUE - position,
                    text = value,
                )
            )
        }
    }
    /**
     * 歌词源位置：作为时间轴锚点接受，而不是拒绝。歌词同步的时钟权威是
     * 歌词源（app 自报的歌词时钟），它与公开 MediaSession 位置可能存在固定
     * 相差（真机实测 salt 音乐恒定 ~400ms；MIUI 息屏会话回调丢失时偏差更大），
     * 只信 MediaSession 会让岛歌词与实际演唱错位。仍经 driver 单点写入渲染层，
     * 不恢复"两套时钟并行写状态"；播放状态与滚动仍归锚点。
     */
    override fun onPositionChanged(position: Long) {
        runOnMain { acceptSourceClock(position, "position") }
    }

    override fun onSeekTo(position: Long) {
        runOnMain { acceptSourceClock(position, "seek") }
    }

    /** 源时钟锚点与偏后连续段（纯逻辑，见 [SourceClockTracker]）。 */
    private val sourceClock = SourceClockTracker(SOURCE_CLOCK_TTL_MS)

    /** 播放中的显示位置平滑：纠偏调速而不是跳，只有真正的 seek/切换才跳。 */
    private val displayClock = DisplayPositionSmoother()
    private var displayCorrectingLogged = false

    private var sourceClockAcceptedSinceDiag = 0
    private var sourceClockDuplicatesSinceDiag = 0
    private var sourceClockBacktracksSinceDiag = 0
    private var lastSourceClockDiagAtMs = 0L

    private fun acceptSourceClock(position: Long, event: String) {
        if (displayedContent?.sourceId == MediaSessionOnlineFallbackPolicy.SOURCE_ID) return
        if (appliedTrackKey == null) return
        val anchored = position.coerceAtLeast(0L)
        val now = SystemClock.elapsedRealtime()
        val result = sourceClock.accept(
            position = anchored,
            explicitSeek = event == "seek",
            nowMs = now,
            playing = anchor.anchor()?.isPlaying == true,
        )
        val action = result.action
        val previous = result.previous
        val projected = result.projected
        when (action) {
            SourceClockUpdatePolicy.Action.IGNORE_DUPLICATE -> {
                sourceClockDuplicatesSinceDiag++
                logSourceClockSummaryIfNeeded(now, action, anchored, projected)
                return
            }
            SourceClockUpdatePolicy.Action.IGNORE_BACKTRACK -> {
                sourceClockBacktracksSinceDiag++
                logSourceClockSummaryIfNeeded(now, action, anchored, projected)
                return
            }
            SourceClockUpdatePolicy.Action.ANCHOR,
            SourceClockUpdatePolicy.Action.SEEK -> {
                if (result.endedBacktrackStreakMs != null && BuildConfig.DEBUG) {
                    diagnostic(
                        "源时钟重新锚定: action=$action, incoming=$anchored, projected=$projected, " +
                            "backtrackStreakMs=${result.endedBacktrackStreakMs}"
                    )
                }
                if (
                    anchorInactiveSinceMs != null && event == "position" &&
                    previous != null && anchored > previous &&
                    anchored - previous <= MAX_CONTINUOUS_SOURCE_STEP_MS
                ) {
                    sourceProgressWhileAnchorInactiveAtMs = now
                }
                sourceClockAcceptedSinceDiag++
                logSourceClockSummaryIfNeeded(now, action, anchored, projected)
            }
        }

        if (renderedPlaying != true) {
            // 暂停态没有位置循环，也没有可见的推进，直接对齐。
            val shown = displayClock.snap(anchored, now)
            if (action == SourceClockUpdatePolicy.Action.SEEK) {
                renderSink.onSeekTo(shown)
            } else {
                renderSink.onPositionChanged(shown)
            }
        } else if (event == "seek") {
            // 来源明确上报的 seek：立即跳。
            commitPendingSameTrackReplace(force = true, reason = "seek")
            renderSink.onSeekTo(displayClock.snap(anchored, now))
        } else if (action == SourceClockUpdatePolicy.Action.SEEK) {
            // 位置不连续但不是明确 seek：可能是真 seek，也可能只是源时钟纠偏。
            // 交给平滑器判断，够大才跳，否则由位置循环调速消化。
            val truth = timelinePosition() ?: anchored
            val step = displayClock.advance(truth, now)
            if (step.jumped) {
                diagnostic("显示位置跳转(源不连续): to=${step.position}, incoming=$anchored")
                renderSink.onSeekTo(step.position)
            }
        }
        // 播放中由 33ms 单一位置循环驱动渲染；来源回调只校准锚点，避免
        // 高频来源与本地循环同时写 UI。
    }

    private fun logSourceClockSummaryIfNeeded(
        now: Long,
        action: SourceClockUpdatePolicy.Action,
        incoming: Long,
        projected: Long?,
    ) {
        if (!BuildConfig.DEBUG) return
        val urgent = action == SourceClockUpdatePolicy.Action.SEEK ||
            action == SourceClockUpdatePolicy.Action.IGNORE_BACKTRACK
        if (!urgent && now - lastSourceClockDiagAtMs < SOURCE_CLOCK_DIAG_INTERVAL_MS) return
        HookLogger.d(
            TAG,
            "[TIMELINE] 源时钟采样: action=$action, incoming=$incoming, projected=$projected, " +
                "accepted=$sourceClockAcceptedSinceDiag, duplicates=$sourceClockDuplicatesSinceDiag, " +
                "backtracks=$sourceClockBacktracksSinceDiag"
        )
        sourceClockAcceptedSinceDiag = 0
        sourceClockDuplicatesSinceDiag = 0
        sourceClockBacktracksSinceDiag = 0
        lastSourceClockDiagAtMs = now
    }

    private fun resetSourceClock() {
        sourceClock.reset()
        sourceProgressWhileAnchorInactiveAtMs = 0L
        sourceClockAcceptedSinceDiag = 0
        sourceClockDuplicatesSinceDiag = 0
        sourceClockBacktracksSinceDiag = 0
        lastSourceClockDiagAtMs = 0L
    }

    /**
     * 当前渲染位置：源时钟新鲜（TTL 内）时按其线性外推（歌词时钟即墙钟，
     * 速度恒 1），播放态复用合成播放语义；过期或缺席回退 MediaSession 锚点外推。
     */
    private fun timelinePosition(): Long? {
        val now = SystemClock.elapsedRealtime()
        val sourcePosition = projectedSourcePosition(now)
        val systemPosition = anchor.estimatedPosition(now)
        if (SourceClockUpdatePolicy.preferSystemAfterSeek(
                packageName = anchor.currentTrack?.packageName,
                sourcePosition = sourcePosition,
                sourceSampleAtMs = sourceClock.sampleAtMs,
                systemPosition = systemPosition,
                systemStateUpdatedAtMs = anchor.anchor()?.stateUpdatedAtMs ?: 0L,
            )
        ) {
            return systemPosition
        }
        return sourcePosition ?: systemPosition
    }

    /** 当前显示给用户的位置（平滑后）；尚未建立时取真实位置。 */
    private fun displayPosition(): Long? = displayClock.displayed ?: timelinePosition()

    /**
     * 离散事件后下发位置。[keepDisplay]（同曲内容替换、同曲标题回退）且正在播放时沿用
     * 已显示的位置，不打断平滑；其余情况对齐到真实位置。
     */
    private fun dispatchTimelinePosition(keepDisplay: Boolean = false) {
        val shown = displayClock.displayed
        if (keepDisplay && shown != null && renderedPlaying == true) {
            renderSink.onPositionChanged(shown)
            return
        }
        val truth = timelinePosition() ?: return
        renderSink.onPositionChanged(displayClock.snap(truth, SystemClock.elapsedRealtime()))
    }

    private fun projectedSourcePosition(now: Long): Long? =
        // Session PAUSED/BUFFERING cannot be turned into an advancing clock by a stale
        // source activity hint. Fresh source samples still move the anchor explicitly.
        sourceClock.projected(now, playing = anchor.anchor()?.isPlaying == true)

    override fun onMetadata(title: String?, artist: String?, album: String?, publisher: String?) = Unit
    override fun currentPlaybackState(): Boolean = !stopped && (renderedPlaying ?: anchorPlaying)

    override fun onTrackChanged(track: TrackIdentity?) {
        runOnMain {
            // 来源提示是包级播放意图，不是单曲快照。同 app 切歌时提示常早于
            // MediaSession 元数据到达；若在这里无条件清空，来源之后又没有状态翻转，
            // 新曲缓冲窗口就只剩下 MediaSession 的假暂停。跨 app/会话清空仍立即作废。
            if (!PlaybackSmoothingPolicy.shouldRetainHint(hintPackage, track?.packageName)) {
                hintPlaying = false
                hintPackage = null
            }
            val effectiveBeforeTransition = renderedPlaying ?: PlaybackSmoothingPolicy.effectivePlaying(
                anchorPlaying = anchorPlaying,
                hintPlaying = hintPlaying,
                hintPackage = hintPackage,
                anchorPackage = track?.packageName,
            )
            val preserveHost = PlaybackSmoothingPolicy.shouldPreserveHostAcrossTrackChange(
                currentPackage = appliedPackageName,
                nextPackage = track?.packageName,
                effectivePlaying = effectiveBeforeTransition,
            )
            if (!preserveHost) renderedPlaying = null
            clearStreamingContent()
            // 曲目身份已变：旧曲的源时钟锚点必须作废，外推不得跨曲延续。
            resetSourceClock()
            displayClock.reset()
            if (preserveHost && track != null) {
                prepareTrackTransition(track)
            } else {
                clearTimeline(showTrackFallback = track != null)
            }
            val pending = pendingContent
            pendingContent = null
            if (track != null) {
                val pendingApplies = pending != null &&
                    TimelineContentPolicy.decide(activeSourceId, track, pending) ==
                    TimelineContentPolicy.Decision.APPLY
                if (pendingApplies) {
                    applyContent(pending)
                    return@runOnMain
                }
                // 锚点让位后切回的曲目：暂存内容不匹配（或没有）时回放缓存内容。
                // 缓存键是锚点规范化键，应用时已经过一次身份校验；这里不再重复
                // 标题级匹配，否则原名刷新后的回放会被组合键标题差异误拒。
                // 车载歌词污染确认前，锚点键随歌词行翻转过；确认后键不再变化，
                // 之前应用过的同曲内容按污染感知的身份匹配找回，而不是等来源重发。
                val cached = appliedContentCache[track.normalizedKey()]
                    ?: if (track.polluted) {
                        appliedContentCache.values.lastOrNull {
                            TimelineContentPolicy.decide(activeSourceId, track, it) ==
                                TimelineContentPolicy.Decision.APPLY
                        }
                    } else {
                        null
                    }
                if (cached != null && cached.sourceId == activeSourceId) {
                    applyContent(cached)
                    diagnostic("锚点切回，回放缓存内容: ${track.normalizedKey()}")
                } else if (pending != null) {
                    diagnostic("锚点更新后丢弃仍不匹配的暂存内容: ${pending.track?.normalizedKey()}")
                }
            }
        }
    }

    override fun onTrackMetadataRefreshed(track: TrackIdentity) {
        runOnMain {
            if (!TimelineContentPolicy.shouldForwardMetadataRefresh(appliedTrackKey, track)) {
                diagnostic("未接管曲目，跳过展示信息刷新: pkg=${track.packageName}")
                return@runOnMain
            }
            val appliedContent = displayedContent
            if (TimelineContentPolicy.isSessionTextUntrusted(track, appliedContent?.song)) {
                // 会话标题/歌手被车载歌词改写：展示已应用歌词内容里的名称，没有则清空歌词行；
                // 污染确认前暂存的内容此时才能按污染感知的身份匹配，补一次应用。
                pendingContent?.let { pending ->
                    if (TimelineContentPolicy.decide(activeSourceId, track, pending) ==
                        TimelineContentPolicy.Decision.APPLY
                    ) {
                        applyContent(pending)
                        return@runOnMain
                    }
                }
                val shown = TimelineContentPolicy.displayMetadata(track, appliedContent?.song)
                renderSink.onMetadata(shown.title, shown.artist, track.album, track.packageName)
                return@runOnMain
            }
            if (!XiaomiMusicMetadataRefreshPolicy.shouldForward(
                    packageName = track.packageName,
                    sameAppliedTrack = appliedTrackKey == track.normalizedKey(),
                    activeSong = appliedContent?.song,
                    title = track.title,
                    artist = track.artist,
                )
            ) {
                // 小米音乐车载歌词逐行改写 MediaSession 标题；完整歌词已在屏时
                // 继续传给 RootLyricSink 会失效并重绑同一句，逐字进度因此闪回。
                return@runOnMain
            }
            // 同一曲展示信息刷新（如原名恢复）：只更新标题元数据，时间轴与歌词保持不动。
            renderSink.onMetadata(track.title, track.artist, track.album, track.packageName)
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        runOnMain {
            if (isPlaying) {
                if (!anchorPlaying) {
                    // 暂停期间收到的源样本是冻结值；恢复播放从现在起外推，暂停时长不能算进去。
                    sourceClock.retimeOnResume(SystemClock.elapsedRealtime())
                }
                anchorInactiveSinceMs = null
                sourceProgressWhileAnchorInactiveAtMs = 0L
            } else if (anchorPlaying || anchorInactiveSinceMs == null) {
                anchorInactiveSinceMs = SystemClock.elapsedRealtime()
                sourceProgressWhileAnchorInactiveAtMs = 0L
                // Keep the last source value for duplicate rejection, but stop projecting it.
                sourceClock.stopProjecting()
            }
            anchorPlaying = isPlaying
            if (appliedTrackKey == null) return@runOnMain
            refreshRenderPlaybackState()
        }
    }

    /**
     * 来源侧（app 进程内）播放态提示。部分 app 缓冲期向 MediaSession 上报暂停态，
     * 渲染层只信锚点会把缓冲当暂停触发岛缩回；同包来源侧仍报播放时维持播放态。
     */
    override fun onSourcePlaybackHint(playing: Boolean, packageName: String?) {
        runOnMain {
            if (hintPlaying == playing && hintPackage == packageName) return@runOnMain
            hintPlaying = playing
            hintPackage = packageName
            if (BuildConfig.DEBUG) {
                HookLogger.d(TAG, "[TIMELINE] 来源播放态提示: playing=$playing, pkg=$packageName")
            }
            if (appliedTrackKey != null) refreshRenderPlaybackState()
        }
    }

    private fun refreshRenderPlaybackState() {
        val now = SystemClock.elapsedRealtime()
        val effective = PlaybackSmoothingPolicy.effectivePlaying(
            anchorPlaying = anchorPlaying,
            hintPlaying = hintPlaying && sourceHintAllowed(now),
            hintPackage = hintPackage,
            anchorPackage = anchor.currentTrack?.packageName,
        )
        val cachedPlaying = renderedPlaying
        val sinkPlaying = renderSink.currentPlaybackState()
        if (
            PlaybackSmoothingPolicy.shouldDispatchPlaybackState(
                effectivePlaying = effective,
                renderedPlaying = cachedPlaying,
                sinkPlaying = sinkPlaying,
            )
        ) {
            renderedPlaying = effective
            renderSink.onPlaybackStateChanged(effective)
            diagnostic(
                "同步渲染播放态: effective=$effective, cached=$cachedPlaying, " +
                    "sink=$sinkPlaying"
            )
        }
        if (effective) {
            startPositionLoop()
        } else {
            stopPositionLoop()
            commitPendingSameTrackReplace(force = true, reason = "paused")
            dispatchTimelinePosition()
        }
    }

    private fun sourceHintAllowed(nowMs: Long): Boolean =
        displayedContent?.sourceId != MediaSessionOnlineFallbackPolicy.SOURCE_ID &&
        PlaybackSmoothingPolicy.allowsSourceHint(
            inactiveAnchorAgeMs = anchorInactiveSinceMs?.let { nowMs - it },
            sourceProgressAgeMs = sourceProgressWhileAnchorInactiveAtMs
                .takeIf { it > 0L }
                ?.let { nowMs - it },
        )

    private val contentDecisionGate = DiagnosticDecisionGate(maxKeys = 8)

    private fun recordContentDecision(content: TimelineContent, decision: String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val songIdentity = System.identityHashCode(content.song)
            val anchorIdentity = anchor.currentTrack?.normalizedKey()?.hashCode()
            val signature = "$decision|${content.sourceId}|$songIdentity|$anchorIdentity"
            if (contentDecisionGate.shouldLog("content/$decision", signature, SystemClock.elapsedRealtime())) {
                LyricRuntimeDiagnostics.record("timeline_content_decision") {
                    "decision=$decision activeSource=$activeSourceId source=${content.sourceId} " +
                        "songObj=$songIdentity anchorPresent=${anchor.currentTrack != null} " +
                        "lyricLines=${content.song?.lyrics.orEmpty().size} streaming=${content.streaming}"
                }
            }
        }
    }

    private fun handleTimelineContent(content: TimelineContent) {
        // 歌词源自带的歌名歌手交给净化器当独立锚点；采用与否由净化器按会话歌手串交叉验证，
        // 锚点身份键不受影响，展示信息在下一次轮询采样时刷新。
        MediaSessionMetadataAdapter.offerLyricsSong(
            content.track?.packageName,
            content.song?.name,
            content.song?.artist,
        )
        val decision = TimelineContentPolicy.decide(activeSourceId, anchor.currentTrack, content)
        if (decision == TimelineContentPolicy.Decision.APPLY &&
            TimelineContentPolicy.keepsWordTimedFallback(activeSourceId, anchor.currentTrack, displayedContent, content)
        ) {
            recordContentDecision(content, "KEEP_WORD_TIMED_FALLBACK")
            return
        }
        recordContentDecision(content, decision.name)
        when (decision) {
            TimelineContentPolicy.Decision.APPLY -> applyContent(content)
            TimelineContentPolicy.Decision.HOLD_FOR_TRACK -> {
                pendingContent = content
                diagnostic("等待媒体身份: source=${content.sourceId}, title=${content.track?.title}")
            }
            TimelineContentPolicy.Decision.DROP_WRONG_TRACK -> {
                // 来源可能比 MediaSession 更早切歌，暂存一次；锚点更新后只会在严格匹配时应用。
                pendingContent = content
                diagnostic(
                    "暂存身份不符内容: source=${content.sourceId}, " +
                        "content=${content.track?.normalizedKey()}, anchor=${anchor.currentTrack?.normalizedKey()}"
                )
            }
            TimelineContentPolicy.Decision.DROP_INACTIVE_SOURCE -> diagnostic(
                "拒绝非活动来源: active=$activeSourceId, incoming=${content.sourceId}"
            )
        }
    }

    private class PendingFallbackSwitch(val content: TimelineContent, val targetPosition: Long)

    /** 更高优先级的补充歌词已到，等位置走到下一句起点再应用；换曲、清空、应用其他内容时作废。 */
    private var pendingFallbackSwitch: PendingFallbackSwitch? = null

    private fun commitPendingFallbackSwitch(position: Long?) {
        val pending = pendingFallbackSwitch ?: return
        when (
            FallbackSwitchPolicy.due(
                position = position,
                targetPosition = pending.targetPosition,
                displayedIsFallback = displayedContent?.sourceId == MediaSessionOnlineFallbackPolicy.SOURCE_ID,
            )
        ) {
            FallbackSwitchPolicy.Due.WAIT -> Unit
            FallbackSwitchPolicy.Due.DROP -> pendingFallbackSwitch = null
            FallbackSwitchPolicy.Due.APPLY -> {
                pendingFallbackSwitch = null
                applyContent(pending.content)
            }
        }
    }

    /**
     * 同曲内容替换（翻译到达、补充/逐字来源到达、来源重发）在播放中晚到时先挂起，
     * 等当前句自然换句的那一帧再应用：句中重新绑定会让岛/息屏的当前句进度与跑马灯从头来一次。
     * 换曲、清空时作废；暂停、seek、等待过久时立即应用。
     */
    private class PendingSameTrackReplace(
        val content: TimelineContent,
        val trackKey: String,
        val queuedAtMs: Long,
    )

    private var pendingSameTrackReplace: PendingSameTrackReplace? = null

    private fun commitPendingSameTrackReplace(force: Boolean, reason: String) {
        val pending = pendingSameTrackReplace ?: return
        if (pending.trackKey != appliedTrackKey) {
            pendingSameTrackReplace = null
            return
        }
        val waitedMs = SystemClock.elapsedRealtime() - pending.queuedAtMs
        if (!force && waitedMs < SameTrackReplacePolicy.MAX_DEFER_MS) return
        pendingSameTrackReplace = null
        diagnostic(
            "同曲内容替换已应用: reason=${if (force) reason else "timeout"}, waitedMs=$waitedMs, " +
                "lines=${pending.content.song?.lyrics?.size ?: 0}"
        )
        applyContent(pending.content, allowDefer = false)
    }

    private fun applyContent(content: TimelineContent, allowDefer: Boolean = true) {
        pendingFallbackSwitch = null
        if (!SystemUiEnhancementGate.isLyricRuntimeEnabled()) {
            recordContentDecision(content, "FEATURE_DISABLED")
            return
        }

        // 媒体会话是 SystemUI 侧的曲目身份权威；来源身份只用于入站匹配。
        val track = anchor.currentTrack ?: content.track
        if (track == null) {
            pendingContent = content
            return
        }
        TimelineContentPolicy.sourceDeclaredPlayer(content, track)?.let(onLyricPlayerConfirmed)
        val song = content.song
        if (song?.lyrics.isNullOrEmpty()) {
            applyTitleFallback(track, content.fallbackTitle)
            // 必须在回退处理之后上报：回退会清空桥状态并重置汇总判定。
            if (song?.metadata?.getBoolean(LyricMetadataKeys.LYRICS_UNAVAILABLE) == true) {
                LyricsAvailability.onFirstPartyReported()
            }
            return
        }
        val incomingTrackKey = track.normalizedKey()
        if (
            allowDefer && SameTrackReplacePolicy.shouldDefer(
                streaming = content.streaming,
                sameTrack = appliedTrackKey == incomingTrackKey,
                playing = renderedPlaying == true,
                displayedHasLyrics = !displayedContent?.song?.lyrics.isNullOrEmpty(),
                lineShowing = !LyriconDataBridge.isTextMode && LyriconDataBridge.currentLyricLine != null,
            )
        ) {
            val queuedAt = pendingSameTrackReplace?.takeIf { it.trackKey == incomingTrackKey }?.queuedAtMs
                ?: SystemClock.elapsedRealtime()
            pendingSameTrackReplace = PendingSameTrackReplace(content, incomingTrackKey, queuedAt)
            diagnostic(
                "同曲内容替换等换句再应用: source=${content.sourceId}, " +
                    "lines=${song.lyrics?.size ?: 0}, position=${displayClock.displayed}"
            )
            return
        }
        pendingSameTrackReplace = null
        if (!content.streaming) clearStreamingContent()
        displayedContent = content
        OnlineLyricCacheUsage.onShown(song)

        LyriconDataBridge.updateLyricPackage(track.packageName)
        val sameTrack = appliedTrackKey == track.normalizedKey()
        val replaced = sameTrack && LyriconDataBridge.replaceSameSongContent(song)
        if (!replaced) LyriconDataBridge.updateSong(song)

        val trackKey = track.normalizedKey()
        appliedTrackKey = trackKey
        appliedPackageName = track.packageName
        if (content.sourceId == activeSourceId) rememberAppliedContent(trackKey, content)
        pendingContent = null
        if (content.streaming) {
            // 流式兼容内容不触发整首歌词的在线/AI 翻译编排。
        } else if (content.onlineTranslationMatched) {
            renderSink.onOnlineTranslationMatched(song)
        } else {
            renderSink.onSongChanged(song)
        }
        val shown = TimelineContentPolicy.displayMetadata(track, song)
        renderSink.onMetadata(shown.title, shown.artist, track.album, track.packageName)
        if (sameTrack) {
            // 同一首的内容替换（翻译到达、流式逐行、补充来源切换）：源时钟仍然有效，
            // 重置会让位置临时回退到会话外推，两者的固定偏差就成了一次抽动。
            dispatchTimelinePosition(keepDisplay = true)
        } else {
            resetSourceClock()
            displayClock.reset()
            dispatchTimelinePosition()
        }
        refreshRenderPlaybackState()
        if (content.sourceId == activeSourceId) primaryContentListener?.invoke()
        HookLogger.i(
            TAG,
            "[TIMELINE] 已应用完整内容: source=${content.sourceId}, title=${track.title}, " +
                "lines=${song.lyrics?.size ?: 0}, revision=${content.revision}, replaced=$replaced"
        )
    }

    private fun applyTitleFallback(track: TrackIdentity, fallbackTitle: String) {
        val trackKey = track.normalizedKey()
        val fallbackAction = PlaybackSmoothingPolicy.emptyLyricsFallbackAction(
            appliedTrackKey = appliedTrackKey,
            nextTrackKey = trackKey,
            currentPackage = appliedPackageName,
            nextPackage = track.packageName,
            renderedPlaying = renderedPlaying,
        )
        when (fallbackAction) {
            PlaybackSmoothingPolicy.EmptyLyricsFallbackAction.KEEP_CURRENT_CONTENT -> Unit

            PlaybackSmoothingPolicy.EmptyLyricsFallbackAction.PRESERVE_HOST -> {
                stopPositionLoop()
                // The track is still playing even when its lyric payload is empty.
                // Keep its clock alive so the Island progress does not freeze at
                // the last position dispatched before this fallback.
                appliedTrackKey = trackKey
                appliedPackageName = track.packageName
                renderSink.onTrackTransition(
                    title = track.displayTitle,
                    artist = track.displayArtist,
                    album = track.album,
                    publisher = track.packageName,
                )
            }

            PlaybackSmoothingPolicy.EmptyLyricsFallbackAction.FULL_RESET -> {
                stopPositionLoop()
                appliedTrackKey = null
                appliedPackageName = null
                renderedPlaying = null
                renderSink.onStop()
            }
        }
        publishTitleFallback(track, fallbackTitle)
        diagnostic(
            "空歌词内容回退: action=$fallbackAction, pkg=${track.packageName}, " +
                "title=${track.title}, renderedPlaying=$renderedPlaying"
        )
    }

    private fun clearTimeline(showTrackFallback: Boolean) {
        pendingFallbackSwitch = null
        pendingSameTrackReplace = null
        displayedContent = null
        stopPositionLoop()
        appliedTrackKey = null
        appliedPackageName = null
        renderedPlaying = null
        resetSourceClock()
        displayClock.reset()
        renderSink.onStop()
        if (showTrackFallback && SystemUiEnhancementGate.isLyricRuntimeEnabled()) {
            // 标题回退只服务模块作用域内的音乐 App（含汽水的系统媒体路径）。
            // 非作用域 App（视频/投屏等）拿到锚点时不写入任何元数据，
            // 岛交给系统原生显示（2026-09-24 issue #39）。
            val fallbackTrack = anchor.currentTrack
                ?.takeIf { IslandMusicAppCatalog.isSupported(it.packageName) }
            if (fallbackTrack == null && BuildConfig.DEBUG) {
                anchor.currentTrack?.let { track ->
                    diagnostic("非作用域锚点，跳过标题回退: pkg=${track.packageName}")
                }
            }
            fallbackTrack?.let { track ->
                onLyricPlayerConfirmed(track.packageName)
                publishTitleFallback(track, track.displayTitle)
            }
        }
    }

    private fun publishTitleFallback(track: TrackIdentity, fallbackTitle: String) {
        // Metadata-only presentation owns a track too. Leaving this null after onStop()
        // prevents later playback callbacks from ever resuming the title placeholder.
        val sameTrack = appliedTrackKey == track.normalizedKey()
        appliedTrackKey = track.normalizedKey()
        appliedPackageName = track.packageName
        LyriconDataBridge.updateLyricPackage(track.packageName)
        LyriconDataBridge.currentSongName = fallbackTitle.ifBlank { track.displayTitle }
        renderSink.onMetadata(track.displayTitle, track.displayArtist, track.album, track.packageName)
        dispatchTimelinePosition(keepDisplay = sameTrack)
        refreshRenderPlaybackState()
        diagnostic("标题回退已绑定: pkg=${track.packageName}, key=$appliedTrackKey, playing=$renderedPlaying")
    }

    private fun prepareTrackTransition(track: TrackIdentity) {
        pendingFallbackSwitch = null
        pendingSameTrackReplace = null
        displayedContent = null
        stopPositionLoop()
        // A same-player track change must keep its position clock even while
        // waiting for the new song's lyrics to arrive.
        appliedTrackKey = track.normalizedKey()
        appliedPackageName = track.packageName
        renderSink.onTrackTransition(
            title = track.displayTitle,
            artist = track.displayArtist,
            album = track.album,
            publisher = track.packageName,
        )
        displayClock.reset()
        dispatchTimelinePosition()
        if (renderedPlaying == true) startPositionLoop()
        diagnostic("同包切歌保留岛宿主: pkg=${track.packageName}, title=${track.title}")
    }

    private fun applyStreamingLine(line: RichLyricLine) {
        val sourceId = activeSourceId ?: return
        val track = anchor.currentTrack ?: return
        val trackKey = track.normalizedKey()
        if (streamingTrackKey != trackKey) {
            clearStreamingContent()
            streamingTrackKey = trackKey
        }
        streamingLines[line.begin] = line
        while (streamingLines.size > MAX_STREAMING_LINES) {
            streamingLines.remove(streamingLines.keys.first())
        }
        handleTimelineContent(
            TimelineContent(
                sourceId = sourceId,
                track = track,
                song = Song(
                    id = trackKey,
                    name = track.displayTitle,
                    artist = track.displayArtist,
                    duration = track.durationMs,
                    lyrics = streamingLines.values.sortedBy { it.begin },
                ),
                streaming = true,
            )
        )
    }

    private fun clearStreamingContent() {
        streamingTrackKey = null
        streamingLines.clear()
    }

    /** 记住最近应用的曲目内容，锚点让位后切回同一曲目时立即恢复，不必等来源重发。 */
    private fun rememberAppliedContent(trackKey: String, content: TimelineContent) {
        appliedContentCache[trackKey] = content
        while (appliedContentCache.size > MAX_CACHED_TRACKS) {
            appliedContentCache.remove(appliedContentCache.keys.first())
        }
    }

    private fun startPositionLoop() {
        if (stopped || activeInstance !== this || positionJob?.isActive == true || appliedTrackKey == null) return
        // 暂停/停循环期间不计入推进；显示位置从上次下发处接着走。
        displayClock.rebase(SystemClock.elapsedRealtime())
        displayCorrectingLogged = false
        positionJob = positionScope.launch {
            while (
                isActive && !stopped && activeInstance === this@LocalTimelineDriver && PlaybackSmoothingPolicy.shouldDrivePositionLoop(
                    appliedTrackKey = appliedTrackKey,
                    renderedPlaying = renderedPlaying,
                )
            ) {
                if (!anchorPlaying && !sourceHintAllowed(SystemClock.elapsedRealtime())) {
                    refreshRenderPlaybackState()
                    if (!isActive || renderedPlaying != true) break
                }
                val step = timelinePosition()?.let { advanceDisplay(it) }
                val position = step?.position
                commitPendingFallbackSwitch(position)
                val lineBeforeChange = LyriconDataBridge.currentLyricLine
                step?.let {
                    if (it.jumped) renderSink.onSeekTo(it.position) else renderSink.onPositionChanged(it.position)
                }
                val lineChanged = LyriconDataBridge.currentLyricLine !== lineBeforeChange
                if (position != null && lineChanged) {
                    // 换句时刻：稳定窗内恢复 33ms 快档，喂换句动画与跑马灯闩锁重试。
                    lastLineChangeAtMs = SystemClock.uptimeMillis()
                }
                commitPendingSameTrackReplace(
                    force = lineChanged || step?.jumped == true ||
                        LyriconDataBridge.currentLyricLine == null,
                    reason = if (lineChanged) "line_changed" else "no_line",
                )
                delay(nextPositionIntervalMs(position))
            }
        }
    }

    private fun advanceDisplay(truth: Long): DisplayPositionSmoother.Step {
        val before = displayClock.displayed
        val step = displayClock.advance(truth, SystemClock.elapsedRealtime())
        if (BuildConfig.DEBUG) {
            if (step.jumped) {
                diagnostic("显示位置跳转: from=$before, to=${step.position}, truth=$truth")
                displayCorrectingLogged = false
            } else if (displayClock.isCorrecting != displayCorrectingLogged) {
                displayCorrectingLogged = displayClock.isCorrecting
                diagnostic(
                    if (displayCorrectingLogged) {
                        "开始平滑纠偏: shown=${step.position}, truth=$truth, " +
                            "rate=${"%.2f".format(java.util.Locale.US, displayClock.rate)}"
                    } else {
                        "平滑纠偏完成: shown=${step.position}, truth=$truth"
                    }
                )
            }
        }
        return step
    }

    private fun stopPositionLoop() {
        positionJob?.cancel()
        positionJob = null
    }

    /** 最近一次派发引起显示行切换的单调时刻；0 表示尚未发生过。主线程限定。 */
    private var lastLineChangeAtMs = 0L

    /**
     * 位置环自适应节拍：逐字行与换句后稳定窗内维持 33ms 平滑填充；普通行句中
     * 放宽到 250ms 并睡到下一个显示行切换点之前（边界到期唤醒），句间主线程唤醒
     * 从 30 次/秒降到约 4-5 次/秒且切句精度不损失。边界与逐字判定读派发后刚更新
     * 的桥状态（渲染 sink 同步写桥）。
     */
    private fun nextPositionIntervalMs(position: Long?): Long {
        if (position == null) return TimelineCadencePolicy.MID_LINE_INTERVAL_MS
        // 调速纠偏期间显示时钟不是 1 倍速，按边界距离睡会睡过头；回到快档逐帧推进。
        if (displayClock.isCorrecting) return TimelineCadencePolicy.MIN_INTERVAL_MS
        val msSinceLineChange = lastLineChangeAtMs.takeIf { it > 0L }
            ?.let { SystemClock.uptimeMillis() - it }
        return TimelineCadencePolicy.nextIntervalMs(
            positionMs = position,
            currentLineWordSync = LyriconDataBridge.currentLyricLine?.words?.isNotEmpty() == true,
            nextBoundaryMs = LyriconDataBridge.nextDisplayChangeMs(position),
            nextLineWordSync = LyriconDataBridge.currentNextLyricLine?.words?.isNotEmpty() == true,
            msSinceLineChange = msSinceLineChange,
            // Raw source words omit generated progress and timed secondary content.
            activeConsumerWordSync = PositionUpdateDemand.active.requiresFrequentUpdates(),
        )
    }

    private fun diagnostic(message: String) {
        if (BuildConfig.DEBUG) HookLogger.d(TAG, "[TIMELINE] $message")
    }

    private fun runOnMain(action: () -> Unit) {
        if (stopped || activeInstance !== this) return
        val guarded = callbackGate.guard(callbackGeneration) { if (activeInstance === this) action() }
        if (Looper.myLooper() == mainHandler.looper) guarded.run()
        else mainHandler.postAtTime(guarded, callbackToken, SystemClock.uptimeMillis())
    }

    companion object {
        private const val TAG = "LocalTimelineDriver"
        private const val MAX_STREAMING_LINES = 256
        private const val MAX_CACHED_TRACKS = 8

        /**
         * 源时钟新鲜窗口：源位置推送停止（暂停/后台停止推送）超过此时长后，
         * 渲染位置回退 MediaSession 锚点外推。须覆盖正常推送间隔（真机 2-5s）
         * 加丢包余量，同时远小于一首歌时长，防止跨曲外推。
         */
        private const val SOURCE_CLOCK_TTL_MS = 15_000L
        private const val SOURCE_CLOCK_DIAG_INTERVAL_MS = 2_000L
        private const val MAX_CONTINUOUS_SOURCE_STEP_MS = 10_000L

        val appliedTrackKeyForDiag: String?
            get() = activeInstance?.appliedTrackKey

        @Volatile
        private var activeInstance: LocalTimelineDriver? = null

        @Synchronized
        internal fun registerActiveInstance(instance: LocalTimelineDriver?) {
            activeInstance = instance
        }

        @Synchronized
        private fun clearActiveInstance(instance: LocalTimelineDriver): Boolean {
            if (activeInstance !== instance) return false
            activeInstance = null
            return true
        }
    }
}
