/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import android.app.Application
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.MediaCardDiagnosticLogger
import io.github.proify.lyricon.central.CentralRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun LyriconSource.registerLocalMediaSessionTracker() {
    val context = app ?: return
    localMediaSessionState.register(
        context = context,
        onSessions = ::onLocalActiveMediaSessionsChanged,
        onRegistered = {
            HookLogger.i(LyriconSource.TAG, "SystemUI 本地媒体会话跟踪已启动")
        },
        onFailure = { error ->
            HookLogger.w(
                LyriconSource.TAG,
                "SystemUI 本地媒体会话跟踪不可用，回退 app 快照: reason=${error.message}",
            )
        },
    )
}

internal fun LyriconSource.onLocalActiveMediaSessionsChanged(packages: Set<String>?) {
    activeMediaSessionGate.updateLocal(packages)
    diagnostic("stage=local_media_sessions, packages=${packages?.sorted()}")
    MediaCardDiagnosticLogger.log(
        stage = "media_session",
        event = "local_sessions_changed",
        details = "packages=${packages?.sorted()},activePlayer=${MediaCardDiagnosticLogger.sanitize(activeCentralPlayerPackageName)},blocked=${activeCentralPlayerPackageName?.let(activeMediaSessionGate::isBlocked)}",
    )
    evaluateActiveMediaSessionGate()
}

internal fun LyriconSource.unregisterLocalMediaSessionTracker() {
    localMediaSessionState.unregister()
    activeMediaSessionGate.updateLocal(null)
}

/**
 * 系统级“是否有音频正在播放”。快照为空但音频在放时，说明 app 侧
 * 通知监听器失明（`150212` 真机证伪）或存在无会话音频，门控必须放行。
 * 取不到 AudioManager 时按“在放”处理，保持 fail-open。
 */
internal fun LyriconSource.isAnyMusicActive(): Boolean {
    val context = app ?: return true
    return localMediaSessionState.isAnyMusicActive(context)
}

internal fun LyriconSource.startAppleMediaMonitor() {
    applePositionState.setObservedMediaKey(null)
    mainHandler.removeCallbacks(appleMediaMonitor)
    mainHandler.post(appleMediaMonitor)
}

internal fun LyriconSource.stopAppleMediaMonitor() {
    mainHandler.removeCallbacks(appleMediaMonitor)
    applePositionState.setObservedMediaKey(null)
}

internal fun LyriconSource.observeAppleMediaSession(force: Boolean = false) {
    val application: Application = app ?: return
    if (hasNonAppleCentralPlayer()) return
    val media = MediaMetadataHelper.getMediaInfo(application, LyriconSource.APPLE_MUSIC_PACKAGE, HookLogger)
    if (media.title.isBlank()) return
    updateAppleMediaPositionReference(media)
    if (!isOnlineTranslationEnabledFor(LyriconSource.APPLE_MUSIC_PACKAGE) &&
        !isFillMissingLyricsEnabled()
    ) {
        return
    }

    val mediaSong = LocalSong(
        name = media.title,
        artist = media.artist,
        duration = media.duration.coerceAtLeast(0L),
        lyrics = emptyList()
    )
    val mediaKey = songIdentity(mediaSong)
    if (!force && mediaKey == applePositionState.observedMediaKey()) return
    applePositionState.setObservedMediaKey(mediaKey)

    val nativeSong = currentAppleSong
    if (
        AppleSongUpdatePolicy.canStartFallbackFromMediaSession(
            currentSong = nativeSong,
            mediaSessionSong = mediaSong,
            currentHasNativeLyrics = currentAppleHasNativeLyrics
        )
    ) {
        val fallbackPending = appleFallbackRequest.snapshot().pending
        if (!fallbackPending && !fallbackSongActive) {
            diagnostic(
                "Apple Music Provider 已确认无歌词，媒体会话补充触发在线兜底: title=${media.title}, " +
                    "artist=${media.artist}, duration=${media.duration}"
            )
            scheduleFallback(nativeSong ?: return, 0L)
        }
        return
    }

    diagnostic(
        "Apple Music 媒体会话只用于观察，等待原生歌词通道确认: " +
            "title=${media.title}, artist=${media.artist}, duration=${media.duration}"
    )
}

internal fun LyriconSource.refreshAppleMediaPositionReference() {
    val application = app ?: return
    val media = MediaMetadataHelper.getMediaInfo(application, LyriconSource.APPLE_MUSIC_PACKAGE, HookLogger)
    if (media.title.isBlank()) return
    updateAppleMediaPositionReference(media)
}

internal fun LyriconSource.updateAppleMediaPositionReference(media: MediaMetadataHelper.MediaInfo) {
    val application = app ?: return
    val currentSong = currentAppleSong ?: return
    val previous = applePositionState.mediaReference()
    val matchesCurrentSong = AppleCentralPositionPolicy.matchesTrack(
        firstTitle = currentSong.name,
        firstArtist = currentSong.artist,
        firstDuration = currentSong.duration,
        secondTitle = media.title,
        secondArtist = media.artist,
        secondDuration = media.duration,
    )
    val continuesBoundMediaIdentity = previous?.songGeneration == applePositionState.songGeneration() &&
        AppleCentralPositionPolicy.matchesTrack(
            firstTitle = previous.title,
            firstArtist = previous.artist,
            firstDuration = previous.duration,
            secondTitle = media.title,
            secondArtist = media.artist,
            secondDuration = media.duration,
        )
    if (!matchesCurrentSong && !continuesBoundMediaIdentity) return

    val progress = MediaMetadataHelper.getPlaybackProgress(application, LyriconSource.APPLE_MUSIC_PACKAGE)
    if (progress.position < 0L) return
    applePositionState.setMediaReference(AppleCentralPositionPolicy.MediaReference(
        songGeneration = applePositionState.songGeneration(),
        title = media.title,
        artist = media.artist,
        duration = media.duration.takeIf { it > 0L } ?: progress.duration,
        position = progress.position,
        isPlaying = progress.isPlaying,
        playbackSpeed = progress.playbackSpeed,
        observedAtMs = SystemClock.elapsedRealtime(),
    ))
}

internal fun LyriconSource.resolveApplePosition(
    adjustedPosition: Long,
    explicitSeek: Boolean,
): AppleCentralPositionPolicy.Resolution = AppleCentralPositionPolicy.resolve(
    centralPosition = adjustedPosition,
    currentSongDuration = currentPublishedAppleSong?.duration
        ?.takeIf { it > 0L }
        ?: currentAppleSong?.duration
        ?: 0L,
    currentSongGeneration = applePositionState.songGeneration(),
    mediaReference = applePositionState.mediaReference(),
    directReference = applePositionState.directReference().takeIf {
        !hasActiveCentralPlayer() || isBuiltInAppleCentralProviderActive()
    },
    providerDelayMs = activeProviderDelayMs,
    nowMs = SystemClock.elapsedRealtime(),
    explicitSeek = explicitSeek,
)

internal fun LyriconSource.startMediaPositionPolling() {
    if (mediaPositionJob?.isActive == true) return
    val application = app ?: return
    applePositionState.setMediaPlaybackState(null)
    mediaPositionJob = mediaPositionScope.launch {
        while (isActive && fallbackSongActive) {
            val progress = MediaMetadataHelper.getPlaybackProgress(
                application,
                LyriconSource.APPLE_MUSIC_PACKAGE
            )
            if (progress.position >= 0L) {
                applePositionState.lastAdjustedPosition = progress.position
                sink?.onPositionChanged(progress.position)
            }
            if (applePositionState.mediaPlaybackState() != progress.isPlaying) {
                applePositionState.setMediaPlaybackState(progress.isPlaying)
                sink?.onPlaybackStateChanged(progress.isPlaying)
            }
            delay(33L)
        }
    }
}

internal fun LyriconSource.stopMediaPositionPolling() {
    mediaPositionJob?.cancel()
    mediaPositionJob = null
    applePositionState.setMediaPlaybackState(null)
}

/**
 * 接收模块 app 侧发布的“存在 MediaSession 的包集合”快照。
 * 来源：远端 prefs 变更、配置广播或冷启动读取（见 [HookEntry]）。
 */
internal fun LyriconSource.onActiveMediaSessionSnapshotChanged(raw: String?, reason: String) {
    activeMediaSessionGate.update(raw)
    MediaCardDiagnosticLogger.log(
        stage = "media_session",
        event = "snapshot_changed",
        reason = reason,
        details = "raw=${MediaCardDiagnosticLogger.sanitize(raw)},tracked=${activeMediaSessionGate.trackedPackages?.sorted()}",
    )
    diagnostic(
        "stage=active_media_session_snapshot, reason=$reason, " +
            "tracked=${activeMediaSessionGate.trackedPackages?.sorted()}",
    )
    evaluateActiveMediaSessionGate()
}

/**
 * 活动播放者被门控阻断（Provider 僵尸发布、宿主已无 MediaSession）时，
 * 主动触发一次 sink 清除，恢复超级岛与经典 AOD 原生显示。
 *
 * 解除阻断（会话重新出现，如划掉后台后重新打开播放、会话抖动恢复）时，
 * 被阻断窗口丢弃的歌曲回调不会自动补发：对同一播放者重放 Central 当前快照，
 * 让原歌曲歌词无需切歌即回填。播放者已切换则不重放，切换路径自身会全量补发。
 */
internal fun LyriconSource.evaluateActiveMediaSessionGate() {
    val player = activeCentralPlayerPackageName ?: return
    if (!activeMediaSessionGate.isBlocked(player)) {
        if (mediaSessionGateRecovery.shouldReplayAfterRecovery(player)) {
            HookLogger.i(
                LyriconSource.TAG,
                "活动播放者 MediaSession 已恢复，重放 Central 快照回填歌词: player=$player",
            )
            MediaCardDiagnosticLogger.log(
                stage = "media_session",
                event = "gate_recovered_snapshot_replay",
                details = "player=${MediaCardDiagnosticLogger.sanitize(player)}",
            )
            // 主线程排队：保证排在本轮阻断发出的 sink 清除之后执行，避免清除反超重放。
            mainHandler.post { CentralRuntime.activePlayers.syncAllListeners() }
        }
        return
    }
    if (!mediaSessionGateRecovery.shouldStop(player)) return
    HookLogger.i(LyriconSource.TAG, "活动播放者已无系统 MediaSession，清除歌词显示: player=$player")
    mainHandler.post { sink?.onStop() }
}

internal fun LyriconSource.isCentralPlayerBlockedByMediaSession(): Boolean {
    val blocked = activeMediaSessionGate.isBlocked(activeCentralPlayerPackageName)
    if (blocked) evaluateActiveMediaSessionGate()
    return blocked
}

internal fun LyriconSource.hasNonAppleCentralPlayer(): Boolean {
    val packageName = activeCentralPlayerPackageName
    return packageName != null && packageName != LyriconSource.APPLE_MUSIC_PACKAGE
}

internal fun LyriconSource.logPlayerVersionSnapshot(
    playerPackageName: String?,
    providerPackageName: String?,
    processName: String?,
    source: String,
) {
    if (playerPackageName.isNullOrBlank()) return
    val application = app ?: return
    runCatching {
        application.packageManager.getPackageInfo(playerPackageName, 0)
    }.onSuccess { packageInfo ->
        val versionName = packageInfo.versionName ?: "unknown"
        val versionCode = packageInfo.longVersionCode
        val key = "$playerPackageName|$versionName|$versionCode|$providerPackageName|$processName"
        if (!loggedPlayerVersionSnapshots.add(key)) return@onSuccess
        HookLogger.i(
            LyriconSource.TAG,
            "[PlayerVersionDiag] stage=active_player_snapshot, result=resolved, " +
                "source=$source, player=$playerPackageName, versionName=$versionName, " +
                "versionCode=$versionCode, provider=$providerPackageName, process=$processName",
        )
    }.onFailure { error ->
        val key = "$playerPackageName|unavailable|$providerPackageName|$processName"
        if (!loggedPlayerVersionSnapshots.add(key)) return@onFailure
        HookLogger.w(
            LyriconSource.TAG,
            "[PlayerVersionDiag] stage=active_player_snapshot, result=unavailable, " +
                "source=$source, player=$playerPackageName, provider=$providerPackageName, " +
                "process=$processName, error=${error.javaClass.simpleName}:${error.message}",
        )
    }
}

internal fun LyriconSource.logAppleTimingDiagnostic(
    path: String,
    rawPosition: Long,
    adjustedPosition: Long,
    resolution: AppleCentralPositionPolicy.Resolution? = null,
    force: Boolean = false,
) {
    if (!BuildConfig.DEBUG) return
    val now = SystemClock.elapsedRealtime()
    val state = listOf(
        path,
        currentAppleSong?.id,
        currentPublishedAppleSong?.id,
        activeCentralPlayerPackageName,
        activeProviderPackageName,
        activeProviderDelayMs,
        fallbackSongActive,
        resolution?.reason,
    ).joinToString("|")
    if (
        !force &&
        state == lastTimingDiagnosticState &&
        now - lastTimingDiagnosticAtMs < LyriconSource.TIMING_DIAGNOSTIC_INTERVAL_MS
    ) return
    HookLogger.i(
        LyriconSource.TAG,
        "[debug] Timing receive: path=$path, rawPosition=$rawPosition, " +
            "adjustedPosition=$adjustedPosition, " +
            "resolvedPosition=${resolution?.position ?: adjustedPosition}, " +
            "mediaPosition=${resolution?.mediaPosition}, " +
            "directPosition=${resolution?.directPosition}, decision=${resolution?.reason}, " +
            "positionDelta=${(adjustedPosition - lastTimingDiagnosticPosition).takeIf {
                lastTimingDiagnosticPosition >= 0L
            }}, delayMs=$activeProviderDelayMs, currentSongId=${currentAppleSong?.id}, " +
            "publishedSongId=${currentPublishedAppleSong?.id}, " +
            "centralPlayer=$activeCentralPlayerPackageName, " +
            "provider=$activeProviderPackageName, fallback=$fallbackSongActive"
    )
    lastTimingDiagnosticAtMs = now
    lastTimingDiagnosticPosition = adjustedPosition
    lastTimingDiagnosticState = state
}

internal fun LyriconSource.logCentralPositionDiagnostic(
    rawPosition: Long,
    forwardedPosition: Long?,
    decision: String,
) {
    if (!BuildConfig.DEBUG) return
    val now = SystemClock.elapsedRealtime()
    if (now - lastCentralPositionDiagnosticAtMs < LyriconSource.TIMING_DIAGNOSTIC_INTERVAL_MS) return
    lastCentralPositionDiagnosticAtMs = now
    HookLogger.i(
        LyriconSource.TAG,
        "[debug] [LyricPositionDiag] stage=subscriber_callback, decision=$decision, " +
            "rawPosition=$rawPosition, forwardedPosition=$forwardedPosition, " +
            "centralPlayer=$activeCentralPlayerPackageName, " +
            "provider=$activeProviderPackageName, delayMs=$activeProviderDelayMs, " +
            "appleCentral=$centralAppleProviderActive, fallback=$fallbackSongActive, " +
            "sinkAvailable=${sink != null}"
    )
}
