package com.juren233.hyperlyricsenhanced.root

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.NotificationMediaAodLyricHooker
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.MediaCardDiagnosticLogger

internal object SystemUiScreenStateMonitor {
    private const val TAG = "SystemUiScreenState"

    private var registeredApp: Application? = null
    @Volatile private var receiver: BroadcastReceiver? = null
    private var cleanupFailure: Throwable? = null

    fun initialize(app: Application) {
        if (registeredApp === app && receiver != null) return
        cleanup()

        val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (receiver !== this) return
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        MediaCardDiagnosticLogger.log(
                            stage = "screen",
                            event = "screen_on",
                            details = "action=${intent.action},source=${MediaCardDiagnosticLogger.identity(context)}",
                        )
                        HookLogger.d(TAG, "收到亮屏事件，刷新超级岛状态")
                        BaseIslandRenderer.onScreenInteractive()
                        NotificationMediaAodLyricHooker.hideLockScreenOverlays()
                    }

                    Intent.ACTION_SCREEN_OFF -> {
                        MediaCardDiagnosticLogger.log(
                            stage = "screen",
                            event = "screen_off",
                            details = "action=${intent.action},source=${MediaCardDiagnosticLogger.identity(context)}",
                        )
                        HookLogger.d(TAG, "收到息屏事件，取消超级岛亮屏恢复任务")
                        BaseIslandRenderer.onScreenNonInteractive()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registeredApp = app
        receiver = screenReceiver
        app.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    fun cleanup() {
        val app = registeredApp
        val activeReceiver = receiver
        registeredApp = null
        receiver = null
        // Never let a failed release look clean to a later startup rollback.
        cleanupFailure?.let { throw it }
        if (app != null && activeReceiver != null) {
            try {
                app.unregisterReceiver(activeReceiver)
            } catch (failure: Throwable) {
                cleanupFailure = failure
                throw failure
            }
        }
    }
}
