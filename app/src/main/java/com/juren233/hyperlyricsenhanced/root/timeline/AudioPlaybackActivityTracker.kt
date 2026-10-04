/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.root.timeline

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.RuntimeResourceCleanup
import com.juren233.hyperlyricsenhanced.root.utils.RuntimeResourceCleanupException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SystemUI 侧「真实发声」跟踪器，判定源与 AOSP MediaSessionService 的媒体按键仲裁器同款：
 * AudioService 播放器事件流（AudioPlaybackConfiguration）。MediaSession 的 PlaybackState
 * 是 App 自由自报，暂停可能落在会话摘除/冻结的观测盲区（2026-09-29 真机：双播交接后锚点
 * 卡死在已暂停的持有者上）；本跟踪器只认「该 uid 名下存在 started 的 USAGE_MEDIA 播放器」。
 *
 * - 只做逐 uid 簿记（当前发声集合 + 最近一次确认发声的单调时刻），目录过滤与让位策略
 *   在 [AudibleSessionSelectionPolicy] 层完成，两层职责不混。
 * - `getClientUid()`/`getPlayerState()` 均为 SystemApi（AOSP main 实证未弃用），公共
 *   android.jar 不含。SystemUI 进程内反射可用；任一解析失败即永久标记不可用，调用方
 *   回退纯会话规则，行为不劣于升级前。
 * - PLAYER_STATE_STARTED 按 AOSP 源码取字面值 2（UNKNOWN=-1 RELEASED=0 IDLE=1
 *   STARTED=2 PAUSED=3 STOPPED=4），禁止臆改。
 */
internal class AudioPlaybackActivityTracker(
    private val mainHandler: Handler,
) {

    interface Listener {
        /** 当前发声 uid 集合发生变化（新起播/全部停止）。 */
        fun onAudibleUidsChanged()
    }

    private companion object {
        private const val TAG = "SystemMediaAnchor"

        /** AOSP AudioPlaybackConfiguration.PLAYER_STATE_STARTED。 */
        private const val PLAYER_STATE_STARTED = 2

        /** 无发声 uid 的簿记保留时长；超过即清理，防止陈旧条目累积。 */
        private const val RETENTION_MS = 10 * 60_000L
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    private var audioManager: AudioManager? = null
    @Volatile
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var cleanupFailure: RuntimeResourceCleanupException? = null

    /** uid -> 最近一次确认发声的时刻（elapsedRealtime 基准）。 */
    @Volatile
    private var lastAudibleAtMs: Map<Int, Long> = emptyMap()

    /** uid -> 最近一次「转入发声」的时刻；持续在播保留原始时刻，仅无声→有声跳变时刷新。 */
    @Volatile
    private var audioStartedAtMs: Map<Int, Long> = emptyMap()

    /** 最近一次快照中仍有 started 媒体播放器的 uid 集合。 */
    @Volatile
    private var audibleUids: Set<Int> = emptySet()

    /** getClientUid 反射是否解析成功；false 时一切查询视为不可用。 */
    @Volatile
    var available: Boolean = false
        private set

    private class ConfigApis(
        val getClientUid: java.lang.reflect.Method,
        val getPlayerState: java.lang.reflect.Method,
    )

    private val configApis: ConfigApis? by lazy {
        val cls = AudioPlaybackConfiguration::class.java
        val clientUid = runCatching { cls.getMethod("getClientUid") }.getOrNull()
        val playerState = runCatching { cls.getMethod("getPlayerState") }.getOrNull()
        if (clientUid == null || playerState == null) {
            HookLogger.w(TAG, "AudioPlaybackConfiguration 私有 API 不可用，发声判定回退纯会话规则")
            null
        } else {
            ConfigApis(clientUid, playerState)
        }
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun start(context: Context) {
        cleanupFailure?.let { throw it }
        if (audioManager != null) return
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val changed = refreshFrom(manager.getActivePlaybackConfigurations())
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                if (playbackCallback !== this) return
                if (refreshFrom(configs)) {
                    listeners.forEach { it.onAudibleUidsChanged() }
                }
            }
        }
        audioManager = manager
        playbackCallback = callback
        manager.registerAudioPlaybackCallback(callback, mainHandler)
        if (BuildConfig.DEBUG) {
            HookLogger.d(TAG, "发声跟踪已启动: available=$available, audibleUids=$audibleUids")
        }
        if (changed) {
            listeners.forEach { it.onAudibleUidsChanged() }
        }
    }

    fun stop() {
        val previousCallback = playbackCallback
        val previousManager = audioManager
        playbackCallback = null
        audioManager = null
        available = false
        lastAudibleAtMs = emptyMap()
        audioStartedAtMs = emptyMap()
        audibleUids = emptySet()
        val cleanup = RuntimeResourceCleanup()
        cleanup.attempt("previous audio playback cleanup") { cleanupFailure?.let { throw it } }
        cleanup.attempt("audio playback callback") {
            if (previousManager != null && previousCallback != null) {
                previousManager.unregisterAudioPlaybackCallback(previousCallback)
            }
        }
        cleanupFailure = cleanup.failureOrNull()
        cleanupFailure?.let { throw it }
    }

    /** 该 uid 当前是否有 USAGE_MEDIA 播放器处于 started。 */
    fun isAudible(uid: Int): Boolean = uid in audibleUids

    /** 该 uid 最近一次确认发声的时刻；从未观测到返回 null。 */
    fun lastAudibleAt(uid: Int): Long? = lastAudibleAtMs[uid]

    /** 该 uid 最近一次转入发声的时刻（用户起播信号）；从未观测到返回 null。 */
    fun startedAt(uid: Int): Long? = audioStartedAtMs[uid]

    /** 更新簿记；返回发声 uid 集合是否发生变化（变化才值得重选会话）。 */
    private fun refreshFrom(configs: List<AudioPlaybackConfiguration>): Boolean {
        val apis = configApis ?: return false
        val now = SystemClock.elapsedRealtime()
        val audible = mutableSetOf<Int>()
        for (config in configs) {
            if (config.audioAttributes?.usage != AudioAttributes.USAGE_MEDIA) continue
            val state = runCatching { apis.getPlayerState.invoke(config) as? Int }.getOrNull() ?: continue
            if (state != PLAYER_STATE_STARTED) continue
            val uid = runCatching { apis.getClientUid.invoke(config) as? Int }.getOrNull() ?: continue
            audible += uid
        }
        available = true

        val previous = audibleUids
        val merged = lastAudibleAtMs.toMutableMap()
        for (uid in audible) {
            merged[uid] = now
        }
        val pruned = merged.entries
            .filter { it.key in audible || now - it.value < RETENTION_MS }
            .associate { it.key to it.value }
        lastAudibleAtMs = pruned
        val started = audioStartedAtMs.toMutableMap()
        for (uid in audible) {
            if (uid !in previous) started[uid] = now
        }
        started.keys.retainAll(pruned.keys)
        audioStartedAtMs = started
        audibleUids = audible
        if (BuildConfig.DEBUG && audible != previous) {
            HookLogger.d(TAG, "发声 uid 集合变化: $previous -> $audible")
        }
        return audible != previous
    }
}
