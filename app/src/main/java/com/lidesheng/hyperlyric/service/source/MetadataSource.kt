package com.lidesheng.hyperlyric.service.source

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.lidesheng.hyperlyric.common.image.AlbumImageHelper
import com.lidesheng.hyperlyric.lyric.DynamicLyricData
import com.lidesheng.hyperlyric.utils.LogManager
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
    private val componentName: ComponentName
) {
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeSessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val currentControllers = mutableListOf<MediaController>()
    private var bitmapRetryJob: Job? = null
    private var bitmapRetryCount = 0
    private val maxBitmapRetries = 5
    private val bitmapRetryDelayMs = 500L
    private var currentSongIdentifier = ""
    private var lastEmittedDynamicTitle = ""

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

            updateCurrentController(manager.getActiveSessions(componentName))
            LogManager.d(TAG, "媒体会话监听注册成功")
        } catch (e: Exception) {
            LogManager.e(TAG, "媒体会话监听注册失败", e)
        }
    }

    fun disconnect() {
        unregisterAllControllers()
        cancelBitmapRetry()
        activeSessionsListener?.let { listener ->
            mediaSessionManager?.removeOnActiveSessionsChangedListener(listener)
        }
        activeSessionsListener = null
        mediaSessionManager = null
    }

    fun clearState() {
        currentSongIdentifier = ""
        lastEmittedDynamicTitle = ""
        cancelBitmapRetry()
        DynamicLyricData.updateLoadingAlbumArt(false)
        DynamicLyricData.updateFetchingLyrics(false)
        DynamicLyricData.updateAnchor(0L, false)
        DynamicLyricData.updateRightTitles(" ", " ", " ", " ", 0L, false, "")
    }

    private fun refreshActiveSessions() {
        mediaSessionManager?.let { manager ->
            updateCurrentController(manager.getActiveSessions(componentName))
        }
    }

    private fun updateCurrentController(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) {
            LogManager.d(TAG, "控制器列表为空，正在清除歌词状态")
            unregisterAllControllers()
            clearState()
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

        val newIdentifier = "$currentPackageName-$artist-$album-$duration"
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
    }
}
