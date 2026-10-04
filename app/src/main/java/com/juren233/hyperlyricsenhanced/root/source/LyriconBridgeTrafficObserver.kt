package com.juren233.hyperlyricsenhanced.root.source

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger

/**
 * 只读观察 Lyricon bridge 广播流量，用于诊断独立词幕链路的注册活动。
 * 仅 Debug 构建注册；不响应、不拦截任何广播。
 */
internal object LyriconBridgeTrafficObserver {
    private const val TAG = "LyriconBridge"

    internal const val ACTION_REGISTER_PROVIDER =
        "io.github.proify.lyricon.lyric.bridge.REGISTER_PROVIDER"
    internal const val ACTION_REGISTER_SUBSCRIBER =
        "io.github.proify.lyricon.lyric.bridge.REGISTER_SUBSCRIBER"
    internal const val ACTION_CENTRAL_BOOT_COMPLETED =
        "io.github.proify.lyricon.lyric.bridge.CENTRAL_BOOT_COMPLETED"

    @Volatile
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!BuildConfig.DEBUG) return
            val keys = runCatching { intent.extras?.keySet()?.joinToString(",") }.getOrNull()
            HookLogger.i(TAG, "[debug] bridge_traffic action=${intent.action} extrasKeys=$keys")
        }
    }

    fun startOnce(context: Context) {
        if (!BuildConfig.DEBUG || registered) return
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter().apply {
                    addAction(ACTION_REGISTER_PROVIDER)
                    addAction(ACTION_REGISTER_SUBSCRIBER)
                    addAction(ACTION_CENTRAL_BOOT_COMPLETED)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
            registered = true
            HookLogger.i(TAG, "[debug] bridge_traffic_observer_started")
        }.onFailure {
            HookLogger.w(TAG, "bridge 流量观察注册失败: ${it.javaClass.name}")
        }
    }
}
