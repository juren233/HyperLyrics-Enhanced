package com.juren233.hyperlyricsenhanced.service.source

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.image.AlbumImageHelper
import com.juren233.hyperlyricsenhanced.lyric.DynamicLyricData
import com.juren233.hyperlyricsenhanced.root.source.ActiveMediaSessionSnapshot
import com.juren233.hyperlyricsenhanced.utils.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class MetadataSource(
    private val context: Context,
    private val scope: CoroutineScope,
    private val componentName: ComponentName,
    private val onMediaSessionAccessLost: () -> Unit = {},
) {
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeSessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val currentControllers = mutableListOf<MediaController>()
    private var bitmapRetryJob: Job? = null
    private var mediaSessionPollJob: Job? = null
    private var emptySessionsSinceElapsedRealtime: Long? = null
    private var emptyStatePublished = false
    private var consecutiveMediaSessionAccessFailures = 0
    private var lastMediaSessionRecoveryElapsedRealtime = 0L
    private var bitmapRetryCount = 0
    private val maxBitmapRetries = 5
    private val bitmapRetryDelayMs = 500L
    private var currentSongIdentifier = ""
    private var lastEmittedDynamicTitle = ""
    private var lastPublishedActiveMediaPackages: Set<String>? = null
    private var lastActiveMediaPackagesPublishedAtMs = 0L

    val lyricUpdateFlow =
        MutableSharedFlow<SyncData>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val newSongFlow =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val playingController = currentControllers.find {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: currentControllers.firstOrNull()
            syncToGlobalData(playingController)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val playingController = currentControllers.find {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: currentControllers.firstOrNull()
            syncToGlobalData(playingController)
        }

        override fun onSessionDestroyed() {
            try {
                refreshActiveSessions()
            } catch (e: Exception) {
                LogManager.w(TAG, "会话销毁处理失败", e)
            }
        }
    }

    fun connect() {
        if (mediaSessionManager != null) return
        try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                updateCurrentController(controllers)
            }
            manager.addOnActiveSessionsChangedListener(listener, componentName)
            mediaSessionManager = manager
            activeSessionsListener = listener

            mediaSessionPollJob = scope.launch {
                while (true) {
                    delay(mediaSessionPollIntervalMs.milliseconds)
                    refreshTrackedMediaSession()
                }
            }
            refreshTrackedMediaSession()
            LogManager.d(TAG, "媒体会话监听注册成功")
        } catch (e: Exception) {
            LogManager.e(TAG, "媒体会话监听注册失败", e)
            requestMediaSessionRecovery()
        }
    }

    fun disconnect() {
        unregisterAllControllers()
        cancelBitmapRetry()
        mediaSessionPollJob?.cancel()
        mediaSessionPollJob = null
        activeSessionsListener?.let { listener ->
            mediaSessionManager?.removeOnActiveSessionsChangedListener(listener)
        }
        activeSessionsListener = null
        mediaSessionManager = null
        emptySessionsSinceElapsedRealtime = null
        emptyStatePublished = false
        consecutiveMediaSessionAccessFailures = 0
    }

    fun clearState() {
        currentSongIdentifier = ""
        lastEmittedDynamicTitle = ""
        cancelBitmapRetry()
        DynamicLyricData.updateLoadingAlbumArt(false)
        DynamicLyricData.updateFetchingLyrics(false)
        DynamicLyricData.updateAnchor(0L, false)
        DynamicLyricData.updateTrackInfo("", "", "")
        DynamicLyricData.updateRightTitles(" ", " ", " ", " ", 0L, false, "")
    }

    private fun refreshActiveSessions() {
        refreshTrackedMediaSession()
    }

    private fun refreshTrackedMediaSession() {
        val manager = mediaSessionManager ?: return
        try {
            updateCurrentController(manager.getActiveSessions(componentName))
            consecutiveMediaSessionAccessFailures = 0
        } catch (e: Exception) {
            consecutiveMediaSessionAccessFailures++
            if (
                consecutiveMediaSessionAccessFailures == 1 ||
                consecutiveMediaSessionAccessFailures % 10 == 0
            ) {
                LogManager.w(
                    TAG,
                    "轮询媒体会话失败: 连续${consecutiveMediaSessionAccessFailures}次",
                    e,
                )
            }
            if (consecutiveMediaSessionAccessFailures >= mediaSessionRecoveryFailureThreshold) {
                requestMediaSessionRecovery()
            }
        }
    }

    fun refreshNow() {
        refreshTrackedMediaSession()
    }

    /**
     * 将“当前存在 MediaSession 的包集合”发布给 SystemUI 侧（AOD-LYRICS-004）。
     * 集合变化立即发布；未变化时按 [ACTIVE_MEDIA_PACKAGES_REFRESH_MS] 周期重发，
     * 让消费方通过快照时间戳确认监控存活。访问失败时不发布，快照过期后消费方 fail-open。
     */
    private fun publishActiveMediaSessionPackages(controllers: List<MediaController>?) {
        val packages = controllers.orEmpty().mapNotNull { it.packageName }.toSet()
        val nowWallClock = System.currentTimeMillis()
        if (
            packages == lastPublishedActiveMediaPackages &&
            nowWallClock - lastActiveMediaPackagesPublishedAtMs < ACTIVE_MEDIA_PACKAGES_REFRESH_MS
        ) {
            return
        }
        lastPublishedActiveMediaPackages = packages
        lastActiveMediaPackagesPublishedAtMs = nowWallClock
        runCatching {
            PrefsBridge.putString(
                RootConstants.KEY_ACTIVE_MEDIA_SESSION_PACKAGES,
                ActiveMediaSessionSnapshot.encode(nowWallClock, packages),
            )
        }.onFailure { error ->
            LogManager.w(TAG, "发布活动媒体会话快照失败", error)
        }
    }

    private fun updateCurrentController(controllers: List<MediaController>?) {
        publishActiveMediaSessionPackages(controllers)
        if (controllers.isNullOrEmpty()) {
            val now = SystemClock.elapsedRealtime()
            val emptySince = emptySessionsSinceElapsedRealtime ?: now.also {
                emptySessionsSinceElapsedRealtime = it
                LogManager.d(TAG, "控制器列表暂时为空，保留当前歌曲状态")
            }
            if (
                emptyStatePublished ||
                now - emptySince < emptySessionGracePeriodMs
            ) {
                return
            }

            LogManager.d(TAG, "控制器持续为空，正在清除歌词状态")
            unregisterAllControllers()
            clearState()
            emptyStatePublished = true
            lyricUpdateFlow.tryEmit(
                SyncData(
                    identityTitle = "",
                    identityArtist = "",
                    identityAlbum = "",
                    dynamicTitle = "",
                    duration = 0L,
                    position = 0L,
                    isPlaying = false,
                    currentPackageName = "",
                    isNewSong = true,
                    albumBitmap = null,
                    notificationAlbumBitmap = null,
                    notificationAlbumBitmapCircular = null,
                    identifier = ""
                )
            )
            return
        }

        emptySessionsSinceElapsedRealtime = null
        emptyStatePublished = false
        val playingController = controllers.find {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        LogManager.d(TAG, "控制器更新: 数量=${controllers.size}, 播放中=${playingController?.packageName}")

        if (playingController != null) {
            val alreadyTracking =
                currentControllers.singleOrNull()?.sessionToken == playingController.sessionToken
            if (!alreadyTracking) {
                unregisterAllControllers()
                currentControllers.add(playingController)
                playingController.registerCallback(mediaCallback)
                syncToGlobalData(playingController)
            } else if (hasTrackedMediaStateChanged(playingController)) {
                // MIUI/部分播放器在息屏后可能不派发 MediaController 回调。
                // 用当前会话对象补一次同步，确保切歌仍能刷新 AOD 焦点通知。
                syncToGlobalData(playingController)
            }
        } else {
            val currentTokens = currentControllers.map { it.sessionToken }.toSet()
            val newTokens = controllers.map { it.sessionToken }.toSet()
            if (currentTokens != newTokens) {
                unregisterAllControllers()
                for (controller in controllers) {
                    currentControllers.add(controller)
                    controller.registerCallback(mediaCallback)
                }
                syncToGlobalData(controllers.first())
            }
        }
    }

    private fun requestMediaSessionRecovery() {
        val now = SystemClock.elapsedRealtime()
        if (
            now - lastMediaSessionRecoveryElapsedRealtime <
            mediaSessionRecoveryCooldownMs
        ) {
            return
        }
        lastMediaSessionRecoveryElapsedRealtime = now
        LogManager.w(TAG, "媒体会话访问持续失败，触发通知监听服务自动重绑")
        onMediaSessionAccessLost()
    }

    private fun hasTrackedMediaStateChanged(controller: MediaController): Boolean {
        val metadata = controller.metadata ?: return false
        val playbackState = controller.playbackState
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.lines()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: "Playing~"
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val identifier = MediaSongIdentity.build(
            packageName = controller.packageName ?: "",
            title = title,
            artist = artist,
            album = album,
            duration = duration,
        )
        val playing = playbackState?.state == PlaybackState.STATE_PLAYING
        return identifier != currentSongIdentifier ||
            playing != DynamicLyricData.currentState.isPlaying
    }

    private fun unregisterAllControllers() {
        for (controller in currentControllers) {
            try {
                controller.unregisterCallback(mediaCallback)
            } catch (e: Exception) {
                LogManager.w(TAG, "注销媒体回调失败", e)
            }
        }
        currentControllers.clear()
    }

    private fun syncToGlobalData(controller: MediaController?) {
        controller ?: run {
            LogManager.d(TAG, "syncToGlobalData 跳过: controller 为 null")
            return
        }

        val metadata = controller.metadata ?: run {
            LogManager.d(TAG, "syncToGlobalData 跳过: metadata 为 null, pkg=${controller.packageName}")
            return
        }
        val playbackState = controller.playbackState ?: run {
            LogManager.d(TAG, "syncToGlobalData 跳过: playbackState 为 null, pkg=${controller.packageName}")
            return
        }
        val currentPackageName = controller.packageName ?: ""

        val rawTitle = (metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.lines()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: "Playing~")
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val position = playbackState.position
        val isPlaying = playbackState.state == PlaybackState.STATE_PLAYING

        val lyricInfoRaw = try {
            metadata.description.extras?.getString("lyricInfo")
                ?: metadata.description.extras?.getString("lyricinfo")
                ?: metadata.getString("lyricInfo")
                ?: metadata.getString("lyricinfo")
        } catch (_: Exception) {
            null
        }
        val lyricRaw = try { metadata.getString("android.media.metadata.LYRIC") } catch (_: Exception) { null }

        val newIdentifier = MediaSongIdentity.build(
            packageName = currentPackageName,
            title = rawTitle,
            artist = artist,
            album = album,
            duration = duration,
        )
        val isNewSong = (newIdentifier != currentSongIdentifier) || DynamicLyricData.currentState.albumBitmap == null
        LogManager.d(TAG, "同步元数据: pkg=$currentPackageName, 标题=$rawTitle, 艺术家=$artist, 专辑=$album, 时长=${duration}ms, 新歌=$isNewSong")

        if (isNewSong) {
            currentSongIdentifier = newIdentifier
            DynamicLyricData.updateBitmaps(null, null)

            cancelBitmapRetry()
            newSongFlow.tryEmit(Unit)
        }

        val albumBitmap = if (isNewSong) {
            val raw = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            AlbumImageHelper.safeCopyBitmap(raw)
        } else {
            DynamicLyricData.currentState.albumBitmap
        }

        val notificationAlbumBitmap = if (isNewSong) {
            albumBitmap?.let { AlbumImageHelper.processAlbumBitmap(it) }
        } else {
            DynamicLyricData.currentState.notificationAlbumBitmap
        }

        val notificationAlbumBitmapCircular = if (isNewSong) {
            albumBitmap?.let { AlbumImageHelper.processAlbumBitmapCircular(it) }
        } else {
            DynamicLyricData.currentState.notificationAlbumBitmapCircular
        }

        val identityTitle = rawTitle
        val identityArtist = artist

        if (isNewSong && albumBitmap == null) {
            LogManager.d(TAG, "封面为空，正在启动重试")
            lastEmittedDynamicTitle = rawTitle
            scheduleBitmapRetry(controller)
        } else if (isNewSong) {
            cancelBitmapRetry()
            lastEmittedDynamicTitle = rawTitle
        }

        lyricUpdateFlow.tryEmit(
            SyncData(
                identityTitle, identityArtist, album, rawTitle,
                duration, position, isPlaying,
                currentPackageName, isNewSong, albumBitmap, notificationAlbumBitmap,
                notificationAlbumBitmapCircular, newIdentifier,
                lyricInfoRaw, lyricRaw
            )
        )
    }

    private fun scheduleBitmapRetry(controller: MediaController) {
        cancelBitmapRetry()
        bitmapRetryCount = 0
        DynamicLyricData.updateLoadingAlbumArt(true)
        bitmapRetryJob = scope.launch {
            while (bitmapRetryCount < maxBitmapRetries) {
                delay(bitmapRetryDelayMs.milliseconds)
                bitmapRetryCount++
                LogManager.d(TAG, "封面重试: 第${bitmapRetryCount}次/${maxBitmapRetries}次")
                val metadata = controller.metadata ?: continue
                val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                if (bitmap != null) {
                    if (metadata.getString(MediaMetadata.METADATA_KEY_TITLE) != lastEmittedDynamicTitle) {
                        LogManager.d(TAG, "封面重试中止: 标题已变更")
                        break
                    }
                    LogManager.d(TAG, "封面重试成功: 第${bitmapRetryCount}次")
                    syncToGlobalData(controller)
                    break
                }
            }
            if (bitmapRetryCount >= maxBitmapRetries) {
                LogManager.w(TAG, "封面重试超时: 已达最大次数 $maxBitmapRetries")
            }
            DynamicLyricData.updateLoadingAlbumArt(false)
        }
    }

    private fun cancelBitmapRetry() {
        bitmapRetryJob?.cancel()
        bitmapRetryJob = null
    }

    companion object {
        private const val TAG = "MetadataSource"
        private const val mediaSessionPollIntervalMs = 1000L
        private const val emptySessionGracePeriodMs = 5000L
        private const val mediaSessionRecoveryFailureThreshold = 2
        private const val mediaSessionRecoveryCooldownMs = 15000L
        private const val ACTIVE_MEDIA_PACKAGES_REFRESH_MS = 60_000L
    }
}

internal object MediaSongIdentity {
    private const val SEPARATOR = '\u001F'

    fun build(
        packageName: String,
        title: String,
        artist: String,
        album: String,
        duration: Long,
    ): String = listOf(
        packageName.trim(),
        title.trim(),
        artist.trim(),
        album.trim(),
        duration.toString(),
    ).joinToString(SEPARATOR.toString())
}
