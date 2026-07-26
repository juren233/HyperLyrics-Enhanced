package com.juren233.hyperlyricsenhanced.service

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import com.juren233.hyperlyricsenhanced.utils.LogManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.view.KeyEvent
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.ClassicAodSongInfoConfig
import com.juren233.hyperlyricsenhanced.common.ServiceConstants
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.common.lyric.LyricSplitter
import com.juren233.hyperlyricsenhanced.lyric.ConfigRepository
import com.juren233.hyperlyricsenhanced.lyric.DynamicLyricData
import com.juren233.hyperlyricsenhanced.root.ClassicAodFocusNotificationPolicy
import com.juren233.hyperlyricsenhanced.service.source.SyncData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.juren233.hyperlyricsenhanced.service.utils.shizuku.ShizukuManager
import com.juren233.hyperlyricsenhanced.service.utils.NotificationBuilder

/**
 * 通知展示调度中心。
 *
 * 息屏降频优化和通知发射逻辑。LiveLyricService 仅在开关打开时，
 *
 * 管理播控广播接收器 (ACTION_TOGGLE_PLAYBACK)
 */
class NotificationPresenter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val lyricSplitter: LyricSplitter
) {
    private val notificationManager by lazy { context.getSystemService(NotificationManager::class.java) }
    private var lastUiState: NotificationBuilder.UiState? = null
    private var pauseDebounceJob: Job? = null
    private var screenTransitionJob: Job? = null
    private var classicAodVerificationJob: Job? = null
    private val pauseDebounceMs = 150L

    private var networkCutJob: Job? = null
    private val networkCutMutex = kotlinx.coroutines.sync.Mutex()
    private val networkCutDurationMs = 100L
    private var networkCutSeq = 0L
    private var lastClassicAodSongInfo = ""
    private var activeClassicAodNotificationId: Int? = null
    private var cachedSourceIconPackage = ""
    private var cachedSourceIcon: Bitmap? = null

    private val isBypassFocusLimitEnabled: Boolean
        get() = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(ServiceConstants.KEY_BYPASS_FOCUS_NOTIFICATION_LIMIT, ServiceConstants.DEFAULT_BYPASS_FOCUS_NOTIFICATION_LIMIT)

    private val isDisableLyricSplit: Boolean
        get() = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(ServiceConstants.KEY_NOTIFICATION_ISLAND_DISABLE_LYRIC_SPLIT, ServiceConstants.DEFAULT_NOTIFICATION_ISLAND_DISABLE_LYRIC_SPLIT)

    private val notificationType: Int
        get() = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
            .getInt(ServiceConstants.KEY_NOTIFICATION_TYPE, ServiceConstants.DEFAULT_NOTIFICATION_TYPE)

    // ─── 播控广播接收器 ───────────────────────────────────
    private val playbackToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == "com.juren233.hyperlyricsenhanced.ACTION_TOGGLE_PLAYBACK") {
                val audioManager = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val eventTime = android.os.SystemClock.uptimeMillis()
                val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
                audioManager?.dispatchMediaKeyEvent(downEvent)
                val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
                audioManager?.dispatchMediaKeyEvent(upEvent)
            }
        }
    }
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> scheduleClassicAodScreenRefresh()
                Intent.ACTION_SCREEN_ON -> scheduleInteractiveScreenCleanup()
            }
        }
    }

    // ─── 生命周期 ─────────────────────────────────────────

    fun register() {
        val filter = IntentFilter("com.juren233.hyperlyricsenhanced.ACTION_TOGGLE_PLAYBACK")
        context.registerReceiver(playbackToggleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        context.registerReceiver(screenStateReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
        NotificationBuilder.createNotificationChannel(context, notificationManager)
    }

    fun unregister() {
        try {
            context.unregisterReceiver(playbackToggleReceiver)
        } catch (e: Exception) {
            LogManager.w("NotificationPresenter", "注销播放控制接收器失败", e)
        }
        runCatching { context.unregisterReceiver(screenStateReceiver) }
        pauseDebounceJob?.cancel()
        screenTransitionJob?.cancel()
        classicAodVerificationJob?.cancel()
        NotificationBuilder.cancelClassicAodSongInfoNotification(notificationManager)
    }

    // ─── 核心入口：接收数据并决定是否发射通知 ──────────────

    /**
     * 由 LiveLyricService 在每次状态变更时调用。
     * 内部完成：开关检查、白名单过滤、状态去重、息屏降频、防抖，最终发射通知。
     */
    fun updateState(globalState: com.juren233.hyperlyricsenhanced.lyric.LyricState, force: Boolean) {
        val isWhitelisted = ConfigRepository.whitelistState.value.contains(globalState.targetPackageName)
        val classicAodSongInfoEnabled = isClassicAodSongInfoEnabled()
        if (!isWhitelisted && !classicAodSongInfoEnabled) {
            clearClassicAodSongInfoNotification()
            clearNotifications()
            return
        }
        updateClassicAodSongInfoNotification(globalState)

        val sp = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        if (!sp.getBoolean(RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_DYNAMIC_ISLAND)) {
            clearNotifications()
            return
        }

        val duration = globalState.duration
        val safeDuration = if (duration > 0) duration else 100L
        val currentPos = with(DynamicLyricData) { globalState.getCurrentPosition() }.coerceIn(0, safeDuration)
        val progressPercent = if (safeDuration > 1000) ((currentPos.toDouble() / safeDuration.toDouble()) * 100).roundToInt().coerceIn(0, 100) else 0

        val currentUiState = NotificationBuilder.UiState(
            title = globalState.islandTitleRight,
            songLyric = globalState.songLyric,
            songInfo = globalState.songInfo,
            islandTitleLeft = globalState.islandTitleLeft,
            notificationTitleLeft = globalState.notificationTitleLeft,
            notificationTitleRight = globalState.notificationTitleRight,
            albumBitmap = globalState.albumBitmap?.takeIf { !it.isRecycled },
            color = globalState.albumColor,
            colorEnd = globalState.albumColorEnd,
            progress = progressPercent,
            isPlaying = globalState.isPlaying,
            targetPackageName = globalState.targetPackageName,
            showIslandLeftAlbum = globalState.showIslandLeftAlbum,
            disableLyricSplit = isDisableLyricSplit,
            notificationAlbumBitmap = globalState.notificationAlbumBitmap?.takeIf { !it.isRecycled },
            notificationAlbumBitmapCircular = globalState.notificationAlbumBitmapCircular?.takeIf { !it.isRecycled },
            islandLeftIconStyle = sp.getInt(ServiceConstants.KEY_ISLAND_LEFT_ICON, ServiceConstants.DEFAULT_ISLAND_LEFT_ICON),
            focusNotificationType = sp.getInt(ServiceConstants.KEY_NOTIFICATION_FOCUS_STYLE, ServiceConstants.DEFAULT_NOTIFICATION_FOCUS_STYLE),
            showAlbumArt = sp.getBoolean(ServiceConstants.KEY_NOTIFICATION_ALBUM, ServiceConstants.DEFAULT_NOTIFICATION_ALBUM),
            highlightColorEnabled = sp.getBoolean(ServiceConstants.KEY_NOTIFICATION_HIGHLIGHT_COLOR, ServiceConstants.DEFAULT_NOTIFICATION_HIGHLIGHT_COLOR),
            songInfoHighlightColorEnabled = sp.getBoolean(ServiceConstants.KEY_NOTIFICATION_SONG_INFO_HIGHLIGHT_COLOR, ServiceConstants.DEFAULT_NOTIFICATION_SONG_INFO_HIGHLIGHT_COLOR),
            progressColorEnabled = sp.getBoolean(ServiceConstants.KEY_NOTIFICATION_PROGRESS_COLOR, ServiceConstants.DEFAULT_NOTIFICATION_PROGRESS_COLOR),
            focusShowNotification = sp.getBoolean(ServiceConstants.KEY_NOTIFICATION_FOCUS_SHOW, ServiceConstants.DEFAULT_NOTIFICATION_FOCUS_SHOW)
        )

        val isScreenOn = DisplayStateResolver.isInteractive(context)
        val showProgressSetting = sp.getBoolean(ServiceConstants.KEY_NOTIFICATION_SHOW_PROGRESS, ServiceConstants.DEFAULT_NOTIFICATION_SHOW_PROGRESS)

        if (!force && lastUiState != null) {
            if (currentUiState == lastUiState) {
                LogManager.d("NotificationPresenter", "updateState 跳过: 状态未变化")
                return
            }
            val progressOnly = currentUiState.isProgressOnlyChange(lastUiState!!)
            if (progressOnly) {
                // 如果当前关闭了进度条显示，或者屏幕处于关闭状态，则不因进度变化触发通知
                if (!showProgressSetting || !isScreenOn) {
                    LogManager.d("NotificationPresenter", "updateState 跳过: 仅进度变化, 进度条开关=$showProgressSetting, 屏幕=$isScreenOn")
                    return
                }
            }
            LogManager.d("NotificationPresenter", "updateState: 强制=$force, 仅进度变化=$progressOnly, 播放中=${currentUiState.isPlaying}")
        }

        if (currentUiState.isPlaying) {
            pauseDebounceJob?.cancel()
            pauseDebounceJob = null

            dispatchNotifications(currentUiState, safeDuration, isScreenOn)
            lastUiState = currentUiState
        } else {
            lastUiState = currentUiState
            if (pauseDebounceJob == null || pauseDebounceJob?.isActive != true) {
                pauseDebounceJob = scope.launch {
                    delay(pauseDebounceMs)
                    if (DynamicLyricData.currentState.isPlaying) return@launch
                    clearNotifications()
                }
            }
        }
    }

    // ─── 内部方法 ─────────────────────────────────────────

    private fun NotificationBuilder.UiState.isProgressOnlyChange(other: NotificationBuilder.UiState): Boolean {
        return progress != other.progress &&
                focusShowNotification == other.focusShowNotification &&
                title == other.title &&
                islandTitleLeft == other.islandTitleLeft &&
                notificationTitleLeft == other.notificationTitleLeft &&
                notificationTitleRight == other.notificationTitleRight &&
                songLyric == other.songLyric &&
                songInfo == other.songInfo &&
                isPlaying == other.isPlaying &&
                showIslandLeftAlbum == other.showIslandLeftAlbum &&
                islandLeftIconStyle == other.islandLeftIconStyle
    }

    private fun dispatchNotifications(uiState: NotificationBuilder.UiState, duration: Long, isScreenOn: Boolean) {
        val sp = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        val showProgressSetting = sp.getBoolean(ServiceConstants.KEY_NOTIFICATION_SHOW_PROGRESS, ServiceConstants.DEFAULT_NOTIFICATION_SHOW_PROGRESS)
        val actualShowProgress = isScreenOn && showProgressSetting
        LogManager.d("NotificationPresenter", "正在发送通知: 类型=${if (notificationType == 1) "焦点" else "普通"}, bypass=$isBypassFocusLimitEnabled, 进度=$actualShowProgress")

        when (notificationType) {
            0 -> {
                // 实时通知
                val notification = NotificationBuilder.buildNormalNotification(context, uiState, duration, actualShowProgress)
                notifyWrapper(NotificationBuilder.NORMAL_NOTIFICATION_ID, notification)
                NotificationBuilder.cancelFocusNotification(notificationManager)
            }
            1 -> {
                // 焦点通知
                val focusNotification = NotificationBuilder.buildFocusNotification(context, uiState, actualShowProgress)
                if (isBypassFocusLimitEnabled) {
                    networkCutJob?.cancel()
                    val seq = ++networkCutSeq
                    networkCutJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        networkCutMutex.lock()
                        try {
                            // 1. 闪断 XMSF 联网
                            ShizukuManager.setXmsfNetworkingEnabled(context, false)
                            
                            // 2. 极速在主线程发射通知
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                notifyWrapper(NotificationBuilder.FOCUS_NOTIFICATION_ID, focusNotification)
                            }
                            
                            // 3. 保持盲区防抖窗口 (100ms)
                            try {
                                kotlinx.coroutines.delay(networkCutDurationMs)
                            } catch (_: kotlinx.coroutines.CancellationException) {
                                // 被新发生的发送任务 cancel，自动延续断网状态
                            }
                            
                            // 4. 到期安全自动恢复网络
                            if (seq == networkCutSeq) {
                                ShizukuManager.setXmsfNetworkingEnabled(context, true)
                            }
                        } finally {
                            networkCutMutex.unlock()
                        }
                    }
                } else {
                    notifyWrapper(NotificationBuilder.FOCUS_NOTIFICATION_ID, focusNotification)
                }
                NotificationBuilder.cancelNormalNotification(notificationManager)
            }
        }
    }

    private fun notifyWrapper(id: Int, notification: Notification) {
        try {
            notificationManager.notify(id, notification)
        } catch (e: Exception) {
            LogManager.e("NotificationPresenter", "通知发送失败 id=$id", e)
        }
    }

    fun clearNotifications() {
        NotificationBuilder.cancelFocusNotification(notificationManager)
        NotificationBuilder.cancelNormalNotification(notificationManager)
        lastUiState = null

        if (isBypassFocusLimitEnabled) {
            networkCutJob?.cancel()
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                ShizukuManager.setXmsfNetworkingEnabled(context, true)
            }
        }
    }

    fun refreshClassicAodSongInfo() {
        updateClassicAodSongInfoNotification(
            com.juren233.hyperlyricsenhanced.lyric.DynamicLyricData.currentState
        )
    }

    private var lastDispatchedIslandLeft = ""
    private var lastDispatchedIsPlaying = false
    private var lastDispatchedShowAlbum = false

    fun dispatchLyricContent(targetText: String, data: SyncData, hasScrollLyrics: Boolean) {
        val songLyric = if (hasScrollLyrics) targetText else data.dynamicTitle
        val pref = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)

        val islandLeftIconStyle = pref.getInt(ServiceConstants.KEY_ISLAND_LEFT_ICON, ServiceConstants.DEFAULT_ISLAND_LEFT_ICON)
        val showIslandLeftAlbum = islandLeftIconStyle in 0..2
        val showAlbumArt = pref.getBoolean(ServiceConstants.KEY_NOTIFICATION_ALBUM, ServiceConstants.DEFAULT_NOTIFICATION_ALBUM)
        val disableLyricSplit = pref.getBoolean(ServiceConstants.KEY_NOTIFICATION_ISLAND_DISABLE_LYRIC_SPLIT, ServiceConstants.DEFAULT_NOTIFICATION_ISLAND_DISABLE_LYRIC_SPLIT) || notificationType == 0
        val limitMaxWidth = pref.getBoolean(ServiceConstants.KEY_NOTIFICATION_ISLAND_LIMIT_WIDTH, ServiceConstants.DEFAULT_NOTIFICATION_ISLAND_LIMIT_WIDTH)
        val maxWidth = pref.getInt(ServiceConstants.KEY_NOTIFICATION_ISLAND_MAX_WIDTH, ServiceConstants.DEFAULT_NOTIFICATION_ISLAND_MAX_WIDTH)

        val splitResult = lyricSplitter.split(
            songLyric,
            LyricSplitter.Config(
                showIslandLeftAlbum = showIslandLeftAlbum,
                showAlbumArt = showAlbumArt,
                disableLyricSplit = disableLyricSplit,
                limitMaxWidth = limitMaxWidth,
                maxWidth = maxWidth
            )
        )

        val finalIslandLeft = splitResult.islandLeft
        val finalIslandRight = splitResult.islandRight
        val finalNotificationLeft = splitResult.notificationLeft
        val finalNotificationRight = splitResult.notificationRight

        val titleStyle = pref.getInt(ServiceConstants.KEY_NOTIFICATION_TITLE_STYLE, ServiceConstants.DEFAULT_NOTIFICATION_TITLE_STYLE)
        val songInfo = when (titleStyle) {
            0 -> ""
            1 -> data.identityTitle
            2 -> data.identityArtist
            3 -> data.identityAlbum
            4 -> "${data.identityTitle} - ${data.identityArtist}"
            5 -> "${data.identityArtist} - ${data.identityTitle}"
            6 -> "${data.identityArtist} - ${data.identityAlbum}"
            else -> ""
        }
        LogManager.d("NotificationPresenter", "分发歌词: islandLeft=$finalIslandLeft, islandRight=$finalIslandRight, songInfo=$songInfo")

        val shouldUpdateBitmap = data.isNewSong ||
                                finalIslandLeft != lastDispatchedIslandLeft ||
                                data.isPlaying != lastDispatchedIsPlaying ||
                                showIslandLeftAlbum != lastDispatchedShowAlbum

        if (shouldUpdateBitmap) {
            lastDispatchedIslandLeft = finalIslandLeft
            lastDispatchedIsPlaying = data.isPlaying
            lastDispatchedShowAlbum = showIslandLeftAlbum
        }

        DynamicLyricData.updateBitmaps(data.albumBitmap, data.notificationAlbumBitmap, data.notificationAlbumBitmapCircular)
        DynamicLyricData.updateIslandLeftIconStyle(islandLeftIconStyle)
        DynamicLyricData.updateLeftTitles(finalIslandLeft, finalNotificationLeft)
        DynamicLyricData.updateRightTitles(finalIslandRight,
            finalNotificationRight,
            songLyric,
            songInfo,
            data.duration,
            data.isPlaying,
            data.currentPackageName,
            showIslandLeftAlbum,
            newTrackTitle = data.identityTitle,
            newTrackArtist = data.identityArtist,
            newTrackIdentifier = data.identifier,
        )
    }

    private fun updateClassicAodSongInfoNotification(
        state: com.juren233.hyperlyricsenhanced.lyric.LyricState
    ) {
        val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        val displayStyle = ClassicAodSongInfoConfig.displayStyle(prefs)
        val format = ClassicAodSongInfoConfig.format(prefs)
        val title = state.trackTitle.trim()
        val artist = state.trackArtist.trim()
        val songInfo = ClassicAodSongInfoConfig.formatSongInfo(title, artist, format)
        val shouldShow = prefs.getBoolean(
            RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
            RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS,
        ) &&
            displayStyle ==
                RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_FOCUS_NOTIFICATION &&
            !isScreenInteractive() &&
            songInfo.isNotBlank()
        LogManager.i(
            "NotificationPresenter",
            "经典AOD歌曲信息: displayStyle=$displayStyle, format=$format, " +
                "title=$title, artist=$artist, " +
                "interactive=${isScreenInteractive()}, shouldShow=$shouldShow"
        )
        val signature = if (shouldShow) {
            ClassicAodFocusNotificationPolicy.songSignature(
                packageName = state.targetPackageName,
                identifier = state.trackIdentifier,
                title = title,
                artist = artist,
                format = format,
            )
        } else {
            ""
        }
        if (signature == lastClassicAodSongInfo) return
        lastClassicAodSongInfo = signature
        if (!shouldShow) {
            clearClassicAodSongInfoNotification(resetSignature = false)
            return
        }

        val notification = NotificationBuilder.buildFocusNotification(
            context = context,
            uiState = NotificationBuilder.UiState(
                title = songInfo,
                songInfo = "",
                islandTitleLeft = "",
                notificationTitleLeft = songInfo,
                color = 0,
                colorEnd = 0,
                progress = 0,
                isPlaying = state.isPlaying,
                notificationAlbumBitmap = sourceApplicationIcon(state.targetPackageName),
                islandLeftIconStyle = 1,
                disableLyricSplit = true,
                showAlbumArt = true,
                focusShowNotification = true,
            ),
            showProgress = false,
        )
        val notificationId = ClassicAodFocusNotificationPolicy.nextNotificationId(
            activeNotificationId = activeClassicAodNotificationId,
            primaryNotificationId =
                NotificationBuilder.CLASSIC_AOD_SONG_INFO_NOTIFICATION_ID,
            secondaryNotificationId =
                NotificationBuilder.CLASSIC_AOD_SONG_INFO_NOTIFICATION_ID_SECONDARY,
        )
        NotificationBuilder.cancelClassicAodSongInfoNotification(notificationManager)
        notifyWrapper(notificationId, notification)
        activeClassicAodNotificationId = notificationId
        scheduleClassicAodNotificationVerification(
            signature = signature,
            notificationId = notificationId,
            notification = notification,
            songInfo = songInfo,
        )
        LogManager.i(
            "NotificationPresenter",
            "经典AOD歌曲信息焦点通知已重新发布: id=$notificationId, song=$songInfo"
        )
    }

    private fun clearClassicAodSongInfoNotification(resetSignature: Boolean = true) {
        classicAodVerificationJob?.cancel()
        classicAodVerificationJob = null
        if (resetSignature) {
            lastClassicAodSongInfo = ""
        }
        activeClassicAodNotificationId = null
        NotificationBuilder.cancelClassicAodSongInfoNotification(notificationManager)
    }

    private fun scheduleClassicAodNotificationVerification(
        signature: String,
        notificationId: Int,
        notification: Notification,
        songInfo: String,
    ) {
        classicAodVerificationJob?.cancel()
        classicAodVerificationJob = scope.launch {
            delay(400L)
            if (
                signature != lastClassicAodSongInfo ||
                isScreenInteractive()
            ) {
                return@launch
            }
            val stillActive = runCatching {
                notificationManager.activeNotifications.any { it.id == notificationId }
            }.onFailure {
                LogManager.w(
                    "NotificationPresenter",
                    "检查经典AOD焦点通知状态失败: id=$notificationId",
                    it
                )
            }.getOrDefault(true)
            if (stillActive) {
                LogManager.i(
                    "NotificationPresenter",
                    "经典AOD焦点通知状态确认正常: id=$notificationId, song=$songInfo"
                )
                return@launch
            }

            val retryId = ClassicAodFocusNotificationPolicy.nextNotificationId(
                activeNotificationId = notificationId,
                primaryNotificationId =
                    NotificationBuilder.CLASSIC_AOD_SONG_INFO_NOTIFICATION_ID,
                secondaryNotificationId =
                    NotificationBuilder.CLASSIC_AOD_SONG_INFO_NOTIFICATION_ID_SECONDARY,
            )
            NotificationBuilder.cancelClassicAodSongInfoNotification(notificationManager)
            notifyWrapper(retryId, notification)
            activeClassicAodNotificationId = retryId
            LogManager.w(
                "NotificationPresenter",
                "经典AOD焦点通知被系统移除，已自动重发: oldId=$notificationId, " +
                    "newId=$retryId, song=$songInfo"
            )
        }
    }

    private fun scheduleClassicAodScreenRefresh() {
        screenTransitionJob?.cancel()
        screenTransitionJob = scope.launch {
            var previousDelay = 0L
            listOf(200L, 800L, 1_800L).forEach { targetDelay ->
                delay(targetDelay - previousDelay)
                previousDelay = targetDelay
                updateClassicAodSongInfoNotification(
                    com.juren233.hyperlyricsenhanced.lyric.DynamicLyricData.currentState
                )
            }
        }
    }

    private fun scheduleInteractiveScreenCleanup() {
        screenTransitionJob?.cancel()
        screenTransitionJob = scope.launch {
            delay(250L)
            if (isScreenInteractive()) {
                clearClassicAodSongInfoNotification()
            } else {
                updateClassicAodSongInfoNotification(
                    com.juren233.hyperlyricsenhanced.lyric.DynamicLyricData.currentState
                )
            }
        }
    }

    private fun isScreenInteractive(): Boolean = DisplayStateResolver.isInteractive(context)

    private fun sourceApplicationIcon(packageName: String): Bitmap? {
        if (packageName.isBlank()) return null
        if (packageName == cachedSourceIconPackage) return cachedSourceIcon
        cachedSourceIconPackage = packageName
        cachedSourceIcon = runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val size = 128
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(bitmap))
            }
        }.onFailure {
            LogManager.w(
                "NotificationPresenter",
                "读取播放来源图标失败: package=$packageName",
                it
            )
        }.getOrNull()
        return cachedSourceIcon
    }

    private fun isClassicAodSongInfoEnabled(): Boolean {
        val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(
            RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
            RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS,
        ) && ClassicAodSongInfoConfig.displayStyle(prefs) ==
            RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_FOCUS_NOTIFICATION
    }
}
