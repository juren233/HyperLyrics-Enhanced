/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity
import io.github.proify.lyricon.provider.PlaybackStateActivityPolicy
import java.util.concurrent.ConcurrentHashMap

/**
 * SystemUI 进程的播放锚点权威（实施计划 5.2）。
 *
 * - 位置真值只来自公开 [MediaController.PlaybackState]（lastPositionUpdateTime 外推复用
 *   [MediaMetadataHelper.estimatePlaybackPosition]）；不把 MediaData.isPlaying 当位置真值。
 * - 媒体身份规范化与应用进程 MetadataSource 完全一致（title 取首个非空行 + trim，
 *   组合键规则同 MediaSongIdentity），保证快照 track 与锚点 track 可严格比对。
 * - 会话选择：优先 STATE_PLAYING；同 token 不抖动切换；轮询兜底覆盖 MIUI 回调丢失。
 * - session 切换或身份变化 → [Listener.onTrackChanged]，消费方据此清除旧快照资格。
 */
class SystemMediaPlaybackAnchor(
    context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {

    data class Anchor(
        val positionMs: Long,
        val elapsedRealtimeMs: Long,
        val speed: Float,
        val isPlaying: Boolean,
        val stateUpdatedAtMs: Long,
    )

    interface Listener {
        /** 媒体身份变化（含会话清空为 null）。 */
        fun onTrackChanged(track: TrackIdentity?)

        /** 播放/暂停状态翻转。 */
        fun onPlaybackStateChanged(isPlaying: Boolean)

        /**
         * 同一曲目的展示信息刷新（normalizedKey 不变，title/artist 等变化）。
         * 典型场景：Apple Music 原名恢复后重新发布元数据。消费方只更新展示文本，
         * 不得清空已应用的时间轴。
         */
        fun onTrackMetadataRefreshed(track: TrackIdentity) {}
    }

    @Volatile
    var currentTrack: TrackIdentity? = null
        private set

    @Volatile
    private var currentAnchor: Anchor? = null

    @Volatile
    private var playbackActive: Boolean = false

    @Volatile
    private var timelineAdvancing: Boolean = false

    /** 当前是否处于播放态（供渲染面调度参考）。 */
    val playing: Boolean
        get() = timelineAdvancing

    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val trackedControllers = ConcurrentHashMap<MediaController, MediaController.Callback>()
    @Volatile
    private var activeController: MediaController? = null

    /**
     * 每个会话最近一次转入播放的时刻（识别"新近起播"的让位信号）。
     * 锚点只在主线程刷新会话，轮询与回调都会更新这两个表。
     */
    private val playStartedAtMs = ConcurrentHashMap<MediaSession.Token, Long>()

    /** 每个会话上次观测到的状态；轮询也能识别起播翻转（MIUI 回调丢失的兜底）。 */
    private val lastObservedState = ConcurrentHashMap<MediaSession.Token, Int>()

    private val listeners =
        java.util.concurrent.CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private val pollRunnable: Runnable = Runnable {
        refreshControllers()
        mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        mainHandler.post { refreshControllers(controllers) }
    }

    fun start(listener: Listener) {
        addListener(listener)
        runCatching {
            manager.addOnActiveSessionsChangedListener(sessionListener, null)
        }.onFailure {
            HookLogger.w(TAG, "会话监听注册失败: ${it.javaClass.simpleName}")
        }
        refreshControllers()
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    fun stop() {
        mainHandler.removeCallbacks(pollRunnable)
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionListener) }
        trackedControllers.keys.forEach { controller ->
            runCatching { controller.unregisterCallback(trackedControllers[controller] ?: return@forEach) }
        }
        trackedControllers.clear()
        activeController = null
        currentAnchor = null
        currentTrack = null
        playbackActive = false
        timelineAdvancing = false
        playStartedAtMs.clear()
        lastObservedState.clear()
        listeners.clear()
    }

    /** 当前锚点（回调/轮询最近一次采样）。 */
    fun anchor(): Anchor? = currentAnchor

    /** 按单调时钟外推当前位置；无锚点返回 null。 */
    fun estimatedPosition(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long? {
        val anchor = currentAnchor ?: return null
        if (!anchor.isPlaying) return anchor.positionMs
        val elapsed = (nowElapsedMs - anchor.elapsedRealtimeMs).coerceAtLeast(0L)
        return (anchor.positionMs + elapsed * anchor.speed).toLong()
    }

    private fun refreshControllers(explicit: List<MediaController>? = null) {
        val controllers = explicit ?: runCatching { manager.getActiveSessions(null) }.getOrNull() ?: return
        synchronized(this) {
            // 清理消失的会话。
            val current = controllers.toSet()
            trackedControllers.keys.filter { it !in current }.forEach { dead ->
                trackedControllers.remove(dead)?.let { cb -> runCatching { dead.unregisterCallback(cb) } }
            }
            // 注册新会话回调。
            for (controller in controllers) {
                if (!trackedControllers.containsKey(controller)) {
                    val callback = object : MediaController.Callback() {
                        override fun onPlaybackStateChanged(state: PlaybackState?) {
                            if (controller.sessionToken == activeController?.sessionToken) {
                                mainHandler.post { sampleController(controller) }
                            } else if (state?.state == PlaybackState.STATE_PLAYING) {
                                // 其他会话起播：立即重新评估，换 app 播放即时切换。
                                mainHandler.post { refreshControllers() }
                            }
                        }

                        override fun onMetadataChanged(metadata: MediaMetadata?) {
                            if (controller.sessionToken == activeController?.sessionToken) {
                                mainHandler.post { sampleController(controller) }
                            }
                        }
                    }
                    runCatching { controller.registerCallback(callback) }
                    trackedControllers[controller] = callback
                }
            }

            // 起播跟踪：转入播放（或首次观测到就在播放）记为现在；一直播着的会话
            // 保持原始起播时刻，随时间推移自然过期为"非新近"。
            val now = SystemClock.elapsedRealtime()
            val aliveTokens = HashSet<MediaSession.Token>()
            for (controller in controllers) {
                val token = controller.sessionToken
                aliveTokens.add(token)
                val state = runCatching { controller.playbackState?.state }.getOrNull()
                if (state != null) {
                    if (state == PlaybackState.STATE_PLAYING &&
                        lastObservedState[token] != PlaybackState.STATE_PLAYING
                    ) {
                        playStartedAtMs[token] = now
                    }
                    lastObservedState[token] = state
                }
            }
            playStartedAtMs.keys.retainAll(aliveTokens)
            lastObservedState.keys.retainAll(aliveTokens)

            // 会话选择：持有且播放中 → 保持；持有但非播放（缓冲/暂停）→ 仅当其他
            // 会话新近起播才让位；无持有会话 → 优先播放中，再退回首会话。
            // 详见 MediaSessionSelectionPolicy。
            val decision = MediaSessionSelectionPolicy.select(
                sessions = controllers,
                keyOf = { it.sessionToken },
                heldKey = activeController?.sessionToken,
                isPlaying = { it.playbackState?.state == PlaybackState.STATE_PLAYING },
                playStartedAtMs = { controller -> playStartedAtMs[controller.sessionToken] },
                nowMs = now,
                playStartWindowMs = PLAY_START_WINDOW_MS,
            )
            val preferred = decision.selected
            if (preferred?.sessionToken != activeController?.sessionToken) {
                activeController = preferred
                if (BuildConfig.DEBUG) {
                    HookLogger.d(TAG, "活动会话切换: pkg=${preferred?.packageName}")
                }
                sampleController(preferred)
            } else {
                // 保持同一会话也要低频补采样（MIUI 息屏后可能不派发回调）。
                sampleController(preferred)
            }
        }
    }

    private fun sampleController(controller: MediaController?) {
        controller ?: run {
            // 会话全空：清除身份与锚点。
            if (currentTrack != null) {
                currentTrack = null
                currentAnchor = null
                listeners.forEach { it.onTrackChanged(null) }
            }
            timelineAdvancing = false
            publishPlaybackActivity(false)
            return
        }
        val metadata = runCatching { controller.metadata }.getOrNull()
        val state = runCatching { controller.playbackState }.getOrNull()

        val track = metadata?.let { buildTrackIdentity(controller.packageName ?: "", it) }
        if (track?.normalizedKey() != currentTrack?.normalizedKey()) {
            currentTrack = track
            // 新会话/新曲目未采到自己的位置前，旧锚点绝不得被新内容沿用。
            currentAnchor = null
            HookLogger.i(TAG, "媒体身份变化: pkg=${track?.packageName}, title=${track?.title}")
            listeners.forEach { it.onTrackChanged(track) }
        } else if (track != null && track != currentTrack) {
            // 同一曲（键不变）展示信息变化：如 Apple Music 原名恢复后重发元数据。
            // 键相同时 currentTrack 此前从未刷新，展示层会一直拿到旧标题。
            currentTrack = track
            if (BuildConfig.DEBUG) {
                HookLogger.d(
                    TAG,
                    "媒体展示信息刷新: pkg=${track.packageName}, title=${track.title}",
                )
            }
            listeners.forEach { it.onTrackMetadataRefreshed(track) }
        }

        val stateCode = state?.state
        val advancing = stateCode == PlaybackState.STATE_PLAYING
        timelineAdvancing = advancing
        val position = MediaMetadataHelper.estimatePlaybackPosition(state)
        if (position >= 0L) {
            currentAnchor = Anchor(
                positionMs = position,
                elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                speed = state?.playbackSpeed ?: 1.0f,
                isPlaying = advancing,
                stateUpdatedAtMs = state?.lastPositionUpdateTime ?: 0L,
            )
        }
        // 播放面活动与时间轴推进必须分开：BUFFERING 保留歌词面，但
        // Anchor.isPlaying=false 使位置冻结。位置暂时不可用时也不得吞掉播放态。
        publishPlaybackActivity(PlaybackStateActivityPolicy.keepsSessionActive(stateCode))
    }

    private fun publishPlaybackActivity(active: Boolean) {
        if (active == playbackActive) return
        playbackActive = active
        listeners.forEach { it.onPlaybackStateChanged(active) }
    }

    companion object {
        private const val TAG = "SystemMediaAnchor"

        /** 轮询兜底：两次回调之间补采样（MIUI 息屏回调丢失的教训）。 */
        private const val POLL_INTERVAL_MS = 2_000L

        /**
         * "新近起播"窗口：其他会话转入播放后此时长内才构成让位信号。窗口只需覆盖
         * 轮询间隔(2s)与回调丢失的余量；长期在放的会话（投屏/后台视频）起播时刻
         * 远超窗口，持有会话缓冲期上报暂停时不会被它们挤走。
         */
        private const val PLAY_START_WINDOW_MS = 8_000L

        /**
         * 与应用进程 MetadataSource.syncToGlobalData 完全一致的规范化：
         * title 取首个非空行并 trim（缺省 "Playing~"），artist/album 原样。
         * 两键不一致会导致快照身份匹配失败，禁止单侧调整。
         */
        fun buildTrackIdentity(packageName: String, metadata: MediaMetadata): TrackIdentity {
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?.lines()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                ?: "Playing~"
            return TrackIdentity(
                packageName = packageName,
                mediaId = runCatching {
                    metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                }.getOrNull(),
                title = title,
                artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
                durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            )
        }
    }
}
