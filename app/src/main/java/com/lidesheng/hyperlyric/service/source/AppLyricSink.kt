package com.lidesheng.hyperlyric.service.source

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.toColorInt
import com.lidesheng.hyperlyric.BuildConfig
import com.lidesheng.hyperlyric.common.image.AlbumImageHelper
import com.lidesheng.hyperlyric.lyric.ConfigRepository
import com.lidesheng.hyperlyric.lyric.DynamicLyricData
import com.lidesheng.hyperlyric.lyric.LrcLine
import com.lidesheng.hyperlyric.service.NotificationPresenter
import com.lidesheng.hyperlyric.service.scheduler.LyricScheduler
import com.lidesheng.hyperlyric.service.scheduler.LyricSchedulerListener
import com.lidesheng.hyperlyric.utils.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.ServiceConstants
import com.lidesheng.hyperlyric.common.UIConstants

class AppLyricSink(
    private val context: Context,
    private val scope: CoroutineScope,
    private val notificationPresenter: NotificationPresenter
) : LyricSchedulerListener {
    private var currentSongIdentifier = ""
    private var cachedNotificationEnabled = false
    private var lastPermissionCheckTime = 0L
    private val permissionCheckInterval = 30_000L

    private var cachedLyricLines: List<LrcLine>? = null
    private var cachedLyricHash: Int = 0

    private val sourceManager = ServiceSourceManager(context)
    private val lyricScheduler = LyricScheduler(context, scope, this)

    private var collectJob: Job? = null
    private var fetchJob: Job? = null
    private var isCurrentlyPlaying: Boolean = false
    private var lastDispatchedLrc: String = ""
    private var currentSyncData: SyncData? = null

    fun startCollecting(lyricUpdateFlow: Flow<SyncData>, newSongFlow: Flow<Unit>) {
        collectJob = scope.launch(Dispatchers.Default) {
            lyricUpdateFlow.conflate().collectLatest { data -> processSyncData(data) }
        }
        scope.launch {
            newSongFlow.collect {
                fetchJob?.cancel()
                fetchJob = null
                lyricScheduler.stop()
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        fetchJob?.cancel()
        fetchJob = null
        lyricScheduler.stop()
    }

    fun clearState() {
        fetchJob?.cancel()
        fetchJob = null
        currentSongIdentifier = ""
        isCurrentlyPlaying = false
        cachedLyricLines = null
        cachedLyricHash = 0
        lastDispatchedLrc = ""
        lyricScheduler.stop()
        DynamicLyricData.updateLoadingAlbumArt(false)
        DynamicLyricData.updateFetchingLyrics(false)
        DynamicLyricData.updateAnchor(0L, false)
        DynamicLyricData.updateRightTitles(" ", " ", " ", " ", 0L, false, "")
        notificationPresenter.clearNotifications()
    }

    private suspend fun processSyncData(data: SyncData) {
        if (data.currentPackageName.isEmpty()) {
            clearState()
            return
        }

        val sp = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        val enableDynamicIsland = sp.getBoolean(RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_DYNAMIC_ISLAND)
        val pauseListening = !enableDynamicIsland
        val isWhitelisted = ConfigRepository.whitelistState.value.contains(data.currentPackageName)
        LogManager.d("AppLyricSink", "processSyncData: 超级岛开关=$enableDynamicIsland, 白名单通过=$isWhitelisted, pkg=${data.currentPackageName}")

        if (pauseListening || !isWhitelisted) {
            isCurrentlyPlaying = false
            DynamicLyricData.updateAnchor(data.position, false)
            DynamicLyricData.updateRightTitles(
                islandText = " ",
                notificationText = " ",
                newSongLyric = " ",
                newSongInfo = " ",
                newDuration = 0L,
                newIsPlaying = false,
                newPackageName = data.currentPackageName
            )
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastPermissionCheckTime > permissionCheckInterval) {
            cachedNotificationEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            lastPermissionCheckTime = now
        }
        if (!cachedNotificationEnabled) {
            LogManager.w("AppLyricSink", "通知权限未授予，跳过处理")
            return
        }

        DynamicLyricData.updateAnchor(data.position, data.isPlaying)

        val isSongChanged = data.isNewSong || currentSongIdentifier != data.identifier

        if (isSongChanged) {
            val progressColorEnabled = sp.getBoolean(ServiceConstants.KEY_NOTIFICATION_PROGRESS_COLOR, ServiceConstants.DEFAULT_NOTIFICATION_PROGRESS_COLOR)
            val highlightColorEnabled = sp.getBoolean(ServiceConstants.KEY_NOTIFICATION_HIGHLIGHT_COLOR, ServiceConstants.DEFAULT_NOTIFICATION_HIGHLIGHT_COLOR)
            val songInfoHighlightColorEnabled = sp.getBoolean(ServiceConstants.KEY_NOTIFICATION_SONG_INFO_HIGHLIGHT_COLOR, ServiceConstants.DEFAULT_NOTIFICATION_SONG_INFO_HIGHLIGHT_COLOR)

            val shouldExtract = progressColorEnabled || highlightColorEnabled || songInfoHighlightColorEnabled

            val colors = if (shouldExtract) {
                AlbumImageHelper.extractColors(data.albumBitmap)
            } else {
                val default = "#E0E0E0".toColorInt()
                AlbumImageHelper.ExtractedColors(default, default)
            }
            LogManager.d("AppLyricSink", "正在提取封面取色: 主色=${String.format("#%06X", 0xFFFFFF and colors.main)}, 次色=${String.format("#%06X", 0xFFFFFF and colors.secondary)}")
            DynamicLyricData.updateColor(colors.main, colors.secondary)
        }

        // 1. 新歌初始化与缓存清空
        if (isSongChanged) {
            fetchJob?.cancel()
            fetchJob = null
            cachedLyricLines = null
            cachedLyricHash = 0
            currentSongIdentifier = data.identifier
            lyricScheduler.stop()

            // 一切歌，就发送 艺术家 - 标题 的通知，当作占位符
            val placeholder = if (data.identityArtist.isNotBlank()) {
                "${data.identityArtist} - ${data.identityTitle}"
            } else {
                data.identityTitle
            }
            lastDispatchedLrc = placeholder
            notificationPresenter.dispatchLyricContent(placeholder, data, false)
            notificationPresenter.updateState(DynamicLyricData.currentState, force = true)
        }

        val playStateChanged = data.isPlaying != isCurrentlyPlaying
        isCurrentlyPlaying = data.isPlaying
        currentSyncData = data

        val lyricSource = sp.getInt(ServiceConstants.KEY_SERVICE_LYRIC_SOURCE, ServiceConstants.DEFAULT_SERVICE_LYRIC_SOURCE)

        // 2. 本地元数据与在线歌词获取和解析（如果在歌曲生命周期中延迟到达或需要重新解析）
        val source = sourceManager.getSource(lyricSource)
        val currentRawLyric = when (lyricSource) {
            ServiceConstants.LYRIC_SOURCE_AUTO -> data.lyricInfoRaw ?: data.lyricRaw
            ServiceConstants.LYRIC_SOURCE_LYRIC_INFO -> data.lyricInfoRaw
            ServiceConstants.LYRIC_SOURCE_LRC -> data.lyricRaw
            else -> null
        }
        val currentRawHash = currentRawLyric?.hashCode() ?: 0

        val needFetchLyrics = (cachedLyricLines == null && fetchJob == null) ||
                              (currentRawLyric != null && currentRawHash != cachedLyricHash)

        if (needFetchLyrics && lyricSource != ServiceConstants.LYRIC_SOURCE_TITLE) {
            fetchJob?.cancel()
            fetchJob = scope.launch(Dispatchers.IO) {
                val isOnline = lyricSource == ServiceConstants.LYRIC_SOURCE_ONLINE
                if (isOnline) {
                    DynamicLyricData.updateFetchingLyrics(true)
                }

                val rawLines = source.getLyrics(data)

                if (isOnline) {
                    DynamicLyricData.updateFetchingLyrics(false)
                }

                if (currentSongIdentifier == data.identifier) {
                    if (!rawLines.isNullOrEmpty()) {
                        cachedLyricLines = rawLines
                        cachedLyricHash = currentRawHash
                        lastDispatchedLrc = ""
                        lyricScheduler.updateLyrics(rawLines, isSongChanged)
                        lyricScheduler.updateSyncData(data)
                        lyricScheduler.startSchedulers(isSongChanged, playStateChanged)
                        notificationPresenter.updateState(DynamicLyricData.currentState, force = true)
                    } else {
                        // 歌词拉取完成但为空，代表“歌词源无数据”静态状态
                        cachedLyricLines = emptyList()
                        cachedLyricHash = currentRawHash
                        lyricScheduler.updateLyrics(null, isSongChanged)

                        val noLyricText = context.getString(com.lidesheng.hyperlyric.R.string.no_lyric_data)
                        lastDispatchedLrc = noLyricText
                        notificationPresenter.dispatchLyricContent(noLyricText, data, false)
                        notificationPresenter.updateState(DynamicLyricData.currentState, force = true)
                    }
                }
            }
        } else if (lyricSource == ServiceConstants.LYRIC_SOURCE_TITLE && cachedLyricLines == null) {
            // 如果是 Title 模式本身，切歌后立刻标记 cachedLyricLines 为 emptyList 以终止“获取中”挂起态，走静态标题通道
            cachedLyricLines = emptyList()
        }

        // 3. 通知分发与调度器控制
        val linesCache = cachedLyricLines
        if (linesCache != null) {
            if (linesCache.isNotEmpty()) {
                // 有有效的滚动歌词：更新调度状态
                lyricScheduler.updateSyncData(data)
                lyricScheduler.startSchedulers(isSongChanged, playStateChanged)
            } else {
                // 静态无歌词状态，或者 Title 模式
                if (lyricSource == ServiceConstants.LYRIC_SOURCE_TITLE) {
                    val titleChanged = data.dynamicTitle != lastDispatchedLrc
                    if (isSongChanged || playStateChanged || titleChanged) {
                        lastDispatchedLrc = data.dynamicTitle
                        notificationPresenter.dispatchLyricContent(data.dynamicTitle, data, false)
                        notificationPresenter.updateState(DynamicLyricData.currentState, force = isSongChanged || playStateChanged)
                    }
                } else {
                    // “歌词源无数据”静态通知：仅在状态改变或歌曲切换时发送，不跑 Ticker 调度器
                    val noLyricText = context.getString(com.lidesheng.hyperlyric.R.string.no_lyric_data)
                    val textChanged = noLyricText != lastDispatchedLrc
                    if (isSongChanged || playStateChanged || textChanged) {
                        lastDispatchedLrc = noLyricText
                        notificationPresenter.dispatchLyricContent(noLyricText, data, false)
                        notificationPresenter.updateState(DynamicLyricData.currentState, force = isSongChanged || playStateChanged)
                    }
                }

                // 进度条依然可以在后台运行（若有配置开启），但歌词 ticker 不会启动
                if (isSongChanged || playStateChanged) {
                    lyricScheduler.updateSyncData(data)
                    lyricScheduler.startSchedulers(isSongChanged, playStateChanged)
                }
            }
        }
    }

    override fun onLyricTick(lyricText: String, data: SyncData) {
        notificationPresenter.dispatchLyricContent(lyricText, data, true)
        notificationPresenter.updateState(DynamicLyricData.currentState, force = true)
    }

    override fun onProgressTick(progressPercent: Float) {
        DynamicLyricData.emitProgress(progressPercent)
    }
}
