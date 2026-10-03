/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.media.session.MediaSession
import android.os.Looper
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger

/**
 * Reuses the notification card's installed listener, including its clickIntent and
 * ActivityStarter / keyguard / DynamicIslandController.notificationClick handling.
 *
 * Original SystemUI classes2.dex (APK SHA-256 22afac555f2df2a33749083fb2093393835cf7dbdeb3f27595c2d07bb88b5ff0):
 * MiuiMediaViewControllerImpl.bindMediaData(MediaData)V binds setClickAction$1 to
 * MiuiMediaViewHolder.player. That listener ignores its View argument and posts the
 * native startPendingIntentDismissingKeyguard flow. No synthetic name is resolved here.
 * Existing AOD hooks track attach/bind/detach even when AOD lyrics are disabled.
 */
internal object NotificationMediaCardClick {
    fun launch(packageName: String, token: MediaSession.Token?): Boolean =
        click(packageName, token, "native_notification_click") { api, holder -> api.getPlayer(holder) }

    private fun click(packageName: String, token: MediaSession.Token?, event: String,
        view: (NativeApi, Any) -> View?): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        return runCatching {
            val controllers = synchronized(NotificationMediaAodLyricHooker.states) {
                NotificationMediaAodLyricHooker.states.keys.toList()
            }
            val targets = controllers.mapNotNull { controller ->
                val api = NotificationMediaAodLyricHooker.resolveApi(controller.javaClass.classLoader)
                    ?: return@mapNotNull null
                // Read the live binding, not an old PendingIntent cached at touch-down.
                val data = api.getMediaData(controller) ?: return@mapNotNull null
                val holder = api.getHolder(controller) ?: return@mapNotNull null
                val player = view(api, holder) ?: return@mapNotNull null
                if (!player.hasOnClickListeners()) return@mapNotNull null
                MediaCardClickTarget(api.packageName(data), api.mediaSessionToken(controller), player)
            }
            val player = selectMediaCardClickTarget(targets, packageName, token) ?: return false
            // callOnClick preserves the native listener without synthesizing touch events
            // or sending a false accessibility click event for the hidden notification card.
            player.callOnClick().also { clicked ->
                if (BuildConfig.DEBUG) HookLogger.d("IslandTouch", "$event=$clicked")
            }
        }.onFailure {
            if (BuildConfig.DEBUG) HookLogger.w("IslandTouch", "$event unavailable: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
    }
}
