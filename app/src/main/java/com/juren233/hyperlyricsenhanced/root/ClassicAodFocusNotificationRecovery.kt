package com.juren233.hyperlyricsenhanced.root

import android.app.AppOpsManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.juren233.hyperlyricsenhanced.common.ClassicAodSongInfoConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.service.LiveLyricService
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.concurrent.Executors

internal object ClassicAodFocusNotificationRecovery {
    private const val TAG = "ClassicAodFocusRecovery"
    private const val MODULE_PACKAGE = "com.juren233.hyperlyricsenhanced"
    private const val MIUI_OP_AUTO_START = 10008
    private const val REFRESH_BROADCAST_DEBOUNCE_MS = 300L
    @Volatile
    private var recoveryRequested = false
    @Volatile
    private var lastRefreshBroadcastElapsedRealtime = 0L
    @Volatile
    private var confirmedLyricPlayer: String? = null
    @Volatile
    private var lastSentLyricPackage: String? = null
    // 跨进程调用可能拉起 App 进程，不能阻塞 SystemUI 主线程或歌词回调线程。
    private val refreshExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HyperLyrics Enhanced-ClassicAodFocus").apply { isDaemon = true }
    }

    fun ensureListenerCanRecover(app: Application, prefs: SharedPreferences) {
        val requiresAutoStart = ClassicAodFocusNotificationPolicy.requiresAutoStart(
                aodLyricsEnabled = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
                    RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS
                ),
                songInfoDisplayStyle = ClassicAodSongInfoConfig.displayStyle(
                    prefs
                )
            )
        if (!requiresAutoStart) {
            recoveryRequested = false
            return
        }
        if (recoveryRequested) return

        runCatching {
            val uid = app.packageManager.getApplicationInfo(MODULE_PACKAGE, 0).uid
            val appOps = app.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: error("AppOpsManager unavailable")
            val setMode = appOps.javaClass.getMethod(
                "setMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            setMode.invoke(
                appOps,
                MIUI_OP_AUTO_START,
                uid,
                MODULE_PACKAGE,
                AppOpsManager.MODE_ALLOWED
            )
            NotificationListenerService.requestRebind(
                ComponentName(MODULE_PACKAGE, LiveLyricService::class.java.name)
            )
            recoveryRequested = true
            HookLogger.i(TAG, "已确认 MIUI 自启动权限并请求重绑焦点通知服务")
        }.onFailure {
            recoveryRequested = false
            HookLogger.e(TAG, "恢复焦点通知服务失败", it)
        }
    }

    /**
     * 由 [com.juren233.hyperlyricsenhanced.root.timeline.LocalTimelineDriver] 在确认歌词来源播放器时调用：
     * 歌词源以自身包名提交并已应用的内容，或模块目录内音乐 App 的标题回退。
     * 变化时同步给 App，经典AOD焦点通知只为该播放器发布，视频等媒体会话不会被当作歌曲。
     */
    fun onLyricPlayerConfirmed(packageName: String) {
        if (packageName.isBlank()) return
        confirmedLyricPlayer = packageName
        if (packageName == lastSentLyricPackage) return
        val context = HookEntry.instance?.runtimeAppContext() ?: return
        requestAppRefresh(context, "lyric_player_confirmed")
    }

    fun resetConfirmedLyricPlayer() {
        confirmedLyricPlayer = null
        lastSentLyricPackage = null
    }

    fun requestAppRefresh(context: Context, reason: String) {
        val prefs = HookEntry.instance?.prefs ?: return
        if (
            !ClassicAodFocusNotificationPolicy.requiresAutoStart(
                aodLyricsEnabled = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
                    RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS,
                ),
                songInfoDisplayStyle = ClassicAodSongInfoConfig.displayStyle(prefs),
            )
        ) {
            return
        }
        if (FullScreenAodSetting.isActive(context)) {
            HookLogger.d(
                TAG,
                "锁屏全屏AOD启用，经典AOD焦点通知已隔离，跳过刷新请求: reason=$reason"
            )
            return
        }

        val lyricPackage = confirmedLyricPlayer
        val now = SystemClock.elapsedRealtime()
        if (
            lyricPackage == lastSentLyricPackage &&
            now - lastRefreshBroadcastElapsedRealtime < REFRESH_BROADCAST_DEBOUNCE_MS
        ) {
            return
        }
        lastRefreshBroadcastElapsedRealtime = now
        // 先占位，避免元数据回调连续触发时重复排队；发送失败后清空以便下次重试。
        if (lyricPackage != null) lastSentLyricPackage = lyricPackage

        val appContext = context.applicationContext ?: context
        refreshExecutor.execute {
            runCatching {
                appContext.contentResolver.call(
                    Uri.parse(
                        "content://${RootConstants.CLASSIC_AOD_FOCUS_REFRESH_AUTHORITY}"
                    ),
                    RootConstants.CLASSIC_AOD_FOCUS_REFRESH_METHOD,
                    reason,
                    Bundle().apply {
                        putString(
                            RootConstants.CLASSIC_AOD_FOCUS_EXTRA_LYRIC_PACKAGE,
                            lyricPackage,
                        )
                    },
                )
                HookLogger.i(
                    TAG,
                    "已请求应用刷新 AOD 焦点通知: reason=$reason, lyricPackage=$lyricPackage"
                )
            }.onFailure {
                if (lastSentLyricPackage == lyricPackage) lastSentLyricPackage = null
                HookLogger.e(TAG, "请求应用刷新 AOD 焦点通知失败: reason=$reason", it)
            }
        }
    }
}

internal object ClassicAodFocusNotificationPolicy {
    /**
     * Binary evidence (OS4.0.0.6 MiuiSystemUI classes3.dex): the keyguard repository
     * Lcom/miui/keyguard/data/repository/KeyguardCommonSettingsRepository; reads the
     * Settings.Secure key "full_screen_aod_on" into fullscreenAodEnabled, the
     * fullscreen (lockscreen) AOD master switch. The classic AOD focus renderer
     * (com.miui.aod AODView/AodContainerView reading mFocusNotification) only covers
     * the classic AOD surface, so the focus-notification song info must stay off
     * while the fullscreen lockscreen AOD is the system's active AOD; otherwise the
     * contentView-less notification is listed as a blank row under the media card.
     */
    const val SETTING_FULL_SCREEN_AOD_ON = "full_screen_aod_on"

    fun isFullScreenAodActive(rawSetting: String?): Boolean = rawSetting == "1"

    fun requiresAutoStart(aodLyricsEnabled: Boolean, songInfoDisplayStyle: Int): Boolean =
        aodLyricsEnabled &&
            songInfoDisplayStyle ==
                RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_FOCUS_NOTIFICATION

    /** 媒体会话来源必须是 SystemUI 确认的歌词来源播放器；尚未收到同步时不发布。 */
    fun isLyricPlayer(targetPackageName: String, lyricPackageName: String?): Boolean =
        targetPackageName.isNotBlank() && targetPackageName == lyricPackageName

    /** 多个媒体会话并存时优先歌词来源播放器，否则保持原有的列表首项。 */
    fun <T> preferLyricPlayer(
        items: List<T>,
        packageOf: (T) -> String?,
        lyricPackageName: String?,
    ): T? = lyricPackageName
        ?.takeIf { it.isNotBlank() }
        ?.let { lyricPackage -> items.firstOrNull { packageOf(it) == lyricPackage } }
        ?: items.firstOrNull()

    fun songSignature(
        packageName: String,
        identifier: String,
        title: String,
        artist: String,
        format: Int,
    ): String = listOf(
        packageName.trim(),
        identifier.trim(),
        title.trim(),
        artist.trim(),
        format.toString(),
    ).joinToString("\u001F")

    fun nextNotificationId(
        activeNotificationId: Int?,
        primaryNotificationId: Int,
        secondaryNotificationId: Int,
    ): Int = if (activeNotificationId == primaryNotificationId) {
        secondaryNotificationId
    } else {
        primaryNotificationId
    }
}

/**
 * Short-TTL cache so per-second presenter/hook polls avoid a Settings binder round
 * trip; the fullscreen AOD switch changes at system-settings timescale only.
 */
internal object FullScreenAodSetting {
    private const val CACHE_MS = 2_000L

    private data class CachedRaw(val atElapsedRealtime: Long, val raw: String?)

    @Volatile
    private var cache: CachedRaw? = null

    fun raw(context: Context): String? {
        val now = SystemClock.elapsedRealtime()
        cache?.takeIf { now - it.atElapsedRealtime < CACHE_MS }?.let { return it.raw }
        val value = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                ClassicAodFocusNotificationPolicy.SETTING_FULL_SCREEN_AOD_ON,
            )
        }.getOrNull()
        cache = CachedRaw(now, value)
        return value
    }

    fun isActive(context: Context): Boolean =
        ClassicAodFocusNotificationPolicy.isFullScreenAodActive(raw(context))
}
