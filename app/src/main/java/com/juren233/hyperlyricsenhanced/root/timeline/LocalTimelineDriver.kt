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
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSink
import com.juren233.hyperlyricsenhanced.lyric.source.SourceSelectionAwareSink
import com.juren233.hyperlyricsenhanced.lyric.source.TimelineContent
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.SystemUiEnhancementGate
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity
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

    /** 最近一次下发给渲染层的合成播放态；null=尚未下发过。 */
    private var renderedPlaying: Boolean? = null

    private var pendingContent: TimelineContent? = null
    private val appliedContentCache = LinkedHashMap<String, TimelineContent>()
    private var streamingTrackKey: String? = null
    private val streamingLines = linkedMapOf<Long, RichLyricLine>()
    private var positionJob: Job? = null
    private val positionScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    init {
        registerActiveInstance(this)
    }

    fun start() {
        anchor.start(this)
    }

    fun stop() {
        runOnMain {
            stopPositionLoop()
            activeSourceId = null
            appliedTrackKey = null
            appliedPackageName = null
            pendingContent = null
            appliedContentCache.clear()
            clearStreamingContent()
            resetSourceClock()
            anchorPlaying = false
            hintPlaying = false
            hintPackage = null
            renderedPlaying = null
            renderSink.onStop()
            registerActiveInstance(null)
        }
        anchor.removeListener(this)
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
        runOnMain { clearTimeline(showTrackFallback = true) }
    }

    // 逐行兼容来源只提交内容；显示、播放时钟与滚动仍归本 driver。
    override fun onLyricLine(line: Any?) {
        val richLine = line as? RichLyricLine ?: return
        runOnMain { applyStreamingLine(richLine) }
    }

    override fun onPlainText(text: String?) {
        val value = text?.takeIf { it.isNotBlank() } ?: return
        runOnMain {
            val position = timelinePosition()?.coerceAtLeast(0L) ?: 0L
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

    /** 源时钟锚点：活动源最近一次上报的（位置, 单调时刻）。 */
    @Volatile
    private var sourceAnchorPosition: Long? = null

    @Volatile
    private var sourceAnchorElapsedMs: Long = 0L

    private var sourceClockAcceptedSinceDiag = 0
    private var sourceClockDuplicatesSinceDiag = 0
    private var sourceClockBacktracksSinceDiag = 0
    private var lastSourceClockDiagAtMs = 0L

    private fun acceptSourceClock(position: Long, event: String) {
        if (appliedTrackKey == null) return
        val anchored = position.coerceAtLeast(0L)
        val now = SystemClock.elapsedRealtime()
        val projected = projectedSourcePosition(now)
        val action = SourceClockUpdatePolicy.decide(
            explicitSeek = event == "seek",
            previousAnchorPosition = sourceAnchorPosition,
            projectedPosition = projected,
            incomingPosition = anchored,
        )
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
                sourceAnchorPosition = anchored
                sourceAnchorElapsedMs = now
                sourceClockAcceptedSinceDiag++
                logSourceClockSummaryIfNeeded(now, action, anchored, projected)
            }
        }

        if (action == SourceClockUpdatePolicy.Action.SEEK) {
            renderSink.onSeekTo(anchored)
        } else if (renderedPlaying != true) {
            // 播放中由 33ms 单一位置循环驱动渲染；来源回调只校准锚点，避免
            // 高频来源与本地循环同时写 UI。暂停态没有循环，仍立即刷新位置。
            renderSink.onPositionChanged(anchored)
        }
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
        sourceAnchorPosition = null
        sourceAnchorElapsedMs = 0L
        sourceClockAcceptedSinceDiag = 0
        sourceClockDuplicatesSinceDiag = 0
        sourceClockBacktracksSinceDiag = 0
        lastSourceClockDiagAtMs = 0L
    }

    /**
     * 当前渲染位置：源时钟新鲜（TTL 内）时按其线性外推（歌词时钟即墙钟，
     * 速度恒 1），播放态复用合成播放语义；过期或缺席回退 MediaSession 锚点外推。
     */
    private fun timelinePosition(): Long? =
        projectedSourcePosition(SystemClock.elapsedRealtime()) ?: anchor.estimatedPosition()

    private fun projectedSourcePosition(now: Long): Long? {
        val src = sourceAnchorPosition ?: return null
        val age = now - sourceAnchorElapsedMs
        if (age !in 0..SOURCE_CLOCK_TTL_MS) return null
        val effective = PlaybackSmoothingPolicy.effectivePlaying(
            anchorPlaying = anchorPlaying,
            hintPlaying = hintPlaying,
            hintPackage = hintPackage,
            anchorPackage = anchor.currentTrack?.packageName,
        )
        return if (effective) src + age else src
    }
    override fun onMetadata(title: String?, artist: String?, album: String?, publisher: String?) = Unit
    override fun currentPlaybackState(): Boolean = renderedPlaying ?: anchorPlaying

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
                val cached = appliedContentCache[track.normalizedKey()]
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
            // 同一曲展示信息刷新（如原名恢复）：只更新标题元数据，时间轴与歌词保持不动。
            renderSink.onMetadata(track.title, track.artist, track.album, track.packageName)
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        anchorPlaying = isPlaying
        runOnMain {
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
        val effective = PlaybackSmoothingPolicy.effectivePlaying(
            anchorPlaying = anchorPlaying,
            hintPlaying = hintPlaying,
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
            timelinePosition()?.let(renderSink::onPositionChanged)
        }
    }

    private fun handleTimelineContent(content: TimelineContent) {
        when (TimelineContentPolicy.decide(activeSourceId, anchor.currentTrack, content)) {
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

    private fun applyContent(content: TimelineContent) {
        if (!SystemUiEnhancementGate.isLyricRuntimeEnabled()) return

        // 媒体会话是 SystemUI 侧的曲目身份权威；来源身份只用于入站匹配。
        val track = anchor.currentTrack ?: content.track
        if (track == null) {
            pendingContent = content
            return
        }
        val song = content.song
        if (song?.lyrics.isNullOrEmpty()) {
            applyTitleFallback(track, content.fallbackTitle)
            return
        }
        if (!content.streaming) clearStreamingContent()

        LyriconDataBridge.updateLyricPackage(track.packageName)
        val sameTrack = appliedTrackKey == track.normalizedKey()
        val replaced = sameTrack && LyriconDataBridge.replaceSameSongContent(song)
        if (!replaced) LyriconDataBridge.updateSong(song)

        val trackKey = track.normalizedKey()
        appliedTrackKey = trackKey
        appliedPackageName = track.packageName
        rememberAppliedContent(trackKey, content)
        pendingContent = null
        if (content.streaming) {
            // 流式兼容内容不触发整首歌词的在线/AI 翻译编排。
        } else if (content.onlineTranslationMatched) {
            renderSink.onOnlineTranslationMatched(song)
        } else {
            renderSink.onSongChanged(song)
        }
        renderSink.onMetadata(track.title, track.artist, track.album, track.packageName)
        resetSourceClock()
        timelinePosition()?.let(renderSink::onPositionChanged)
        refreshRenderPlaybackState()
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
                appliedTrackKey = null
                appliedPackageName = track.packageName
                renderSink.onTrackTransition(
                    title = track.title,
                    artist = track.artist,
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
        LyriconDataBridge.updateLyricPackage(track.packageName)
        LyriconDataBridge.currentSongName = fallbackTitle.ifBlank { track.title }
        renderSink.onMetadata(track.title, track.artist, track.album, track.packageName)
        diagnostic(
            "空歌词内容回退: action=$fallbackAction, pkg=${track.packageName}, " +
                "title=${track.title}, renderedPlaying=$renderedPlaying"
        )
    }

    private fun clearTimeline(showTrackFallback: Boolean) {
        stopPositionLoop()
        appliedTrackKey = null
        appliedPackageName = null
        renderedPlaying = null
        resetSourceClock()
        renderSink.onStop()
        if (showTrackFallback) {
            anchor.currentTrack?.let { track ->
                LyriconDataBridge.updateLyricPackage(track.packageName)
                LyriconDataBridge.currentSongName = track.title
                renderSink.onMetadata(track.title, track.artist, track.album, track.packageName)
            }
        }
    }

    private fun prepareTrackTransition(track: TrackIdentity) {
        stopPositionLoop()
        appliedTrackKey = null
        appliedPackageName = track.packageName
        renderSink.onTrackTransition(
            title = track.title,
            artist = track.artist,
            album = track.album,
            publisher = track.packageName,
        )
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
                    name = track.title,
                    artist = track.artist,
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
        if (positionJob?.isActive == true || appliedTrackKey == null) return
        positionJob = positionScope.launch {
            while (
                isActive && PlaybackSmoothingPolicy.shouldDrivePositionLoop(
                    appliedTrackKey = appliedTrackKey,
                    renderedPlaying = renderedPlaying,
                )
            ) {
                timelinePosition()?.let(renderSink::onPositionChanged)
                delay(POSITION_INTERVAL_MS)
            }
        }
    }

    private fun stopPositionLoop() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun diagnostic(message: String) {
        if (BuildConfig.DEBUG) HookLogger.d(TAG, "[TIMELINE] $message")
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) action() else mainHandler.post(action)
    }

    companion object {
        private const val TAG = "LocalTimelineDriver"
        private const val POSITION_INTERVAL_MS = 33L
        private const val MAX_STREAMING_LINES = 256
        private const val MAX_CACHED_TRACKS = 8

        /**
         * 源时钟新鲜窗口：源位置推送停止（暂停/后台停止推送）超过此时长后，
         * 渲染位置回退 MediaSession 锚点外推。须覆盖正常推送间隔（真机 2-5s）
         * 加丢包余量，同时远小于一首歌时长，防止跨曲外推。
         */
        private const val SOURCE_CLOCK_TTL_MS = 15_000L
        private const val SOURCE_CLOCK_DIAG_INTERVAL_MS = 2_000L

        val appliedTrackKeyForDiag: String?
            get() = activeInstance?.appliedTrackKey

        @Volatile
        private var activeInstance: LocalTimelineDriver? = null

        internal fun registerActiveInstance(instance: LocalTimelineDriver?) {
            activeInstance = instance
        }
    }
}
