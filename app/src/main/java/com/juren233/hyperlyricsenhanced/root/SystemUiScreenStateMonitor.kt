package com.juren233.hyperlyricsenhanced.root

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.NotificationMediaAodLyricHooker
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger

internal object SystemUiScreenStateMonitor {
    private const val TAG = "SystemUiScreenState"

    private var registeredApp: Application? = null
    private var receiver: BroadcastReceiver? = null

    fun initialize(app: Application) {
        if (registeredApp === app && receiver != null) return
        cleanup()

        val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        HookLogger.d(TAG, "收到亮屏事件，刷新超级岛与息屏歌词状态")
                        NotificationMediaAodLyricHooker.refresh()
                        BaseIslandRenderer.onScreenInteractive()
                    }

                    Intent.ACTION_SCREEN_OFF -> {
                        HookLogger.d(TAG, "收到息屏事件，等待系统 AOD 展示窗口")
                        BaseIslandRenderer.onScreenNonInteractive()
                        NotificationMediaAodLyricHooker.refresh()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        app.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        registeredApp = app
        receiver = screenReceiver
    }

    fun cleanup() {
        val app = registeredApp
        val activeReceiver = receiver
        if (app != null && activeReceiver != null) {
            runCatching { app.unregisterReceiver(activeReceiver) }
                .onFailure { HookLogger.w(TAG, "注销屏幕状态监听失败", it) }
        }
        registeredApp = null
        receiver = null
    }
}
