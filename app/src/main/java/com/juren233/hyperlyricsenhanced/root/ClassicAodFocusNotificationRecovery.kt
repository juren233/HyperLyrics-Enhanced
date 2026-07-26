package com.juren233.hyperlyricsenhanced.root

import android.app.AppOpsManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import com.juren233.hyperlyricsenhanced.common.ClassicAodSongInfoConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.service.LiveLyricService
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger

internal object ClassicAodFocusNotificationRecovery {
    private const val TAG = "ClassicAodFocusRecovery"
    private const val MODULE_PACKAGE = "com.juren233.hyperlyricsenhanced"
    private const val MIUI_OP_AUTO_START = 10008
    private const val REFRESH_BROADCAST_DEBOUNCE_MS = 300L
    @Volatile
    private var recoveryRequested = false
    @Volatile
    private var lastRefreshBroadcastElapsedRealtime = 0L

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

        val now = SystemClock.elapsedRealtime()
        if (now - lastRefreshBroadcastElapsedRealtime < REFRESH_BROADCAST_DEBOUNCE_MS) return
        lastRefreshBroadcastElapsedRealtime = now

        runCatching {
            context.contentResolver.call(
                Uri.parse(
                    "content://${RootConstants.CLASSIC_AOD_FOCUS_REFRESH_AUTHORITY}"
                ),
                RootConstants.CLASSIC_AOD_FOCUS_REFRESH_METHOD,
                reason,
                null,
            )
            HookLogger.i(TAG, "已请求应用刷新 AOD 焦点通知: reason=$reason")
        }.onFailure {
            HookLogger.e(TAG, "请求应用刷新 AOD 焦点通知失败: reason=$reason", it)
        }
    }
}

internal object ClassicAodFocusNotificationPolicy {
    fun requiresAutoStart(aodLyricsEnabled: Boolean, songInfoDisplayStyle: Int): Boolean =
        aodLyricsEnabled &&
            songInfoDisplayStyle ==
                RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_FOCUS_NOTIFICATION

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
