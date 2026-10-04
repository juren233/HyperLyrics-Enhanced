package com.juren233.hyperlyricsenhanced.root.source

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.RuntimeResourceCleanup
import com.juren233.hyperlyricsenhanced.root.utils.RuntimeResourceCleanupException

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
    private var receiver: BroadcastReceiver? = null
    private var registrationContext: Context? = null
    private var cleanupFailure: RuntimeResourceCleanupException? = null

    fun startOnce(context: Context) {
        if (!BuildConfig.DEBUG) return
        cleanupFailure?.let { throw it }
        if (receiver != null) return
        val pendingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (receiver !== this) return
                val keys = runCatching { intent.extras?.keySet()?.joinToString(",") }.getOrNull()
                HookLogger.i(TAG, "[debug] bridge_traffic action=${intent.action} extrasKeys=$keys")
            }
        }
        receiver = pendingReceiver
        registrationContext = context
        try {
            ContextCompat.registerReceiver(
                context,
                pendingReceiver,
                IntentFilter().apply {
                    addAction(ACTION_REGISTER_PROVIDER)
                    addAction(ACTION_REGISTER_SUBSCRIBER)
                    addAction(ACTION_CENTRAL_BOOT_COMPLETED)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
            HookLogger.i(TAG, "[debug] bridge_traffic_observer_started")
        } catch (error: Throwable) {
            runCatching { stop() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    fun stop() {
        val previousContext = registrationContext
        val previousReceiver = receiver
        receiver = null
        registrationContext = null
        val cleanup = RuntimeResourceCleanup()
        cleanup.attempt("previous bridge traffic cleanup") { cleanupFailure?.let { throw it } }
        cleanup.attempt("bridge traffic receiver") {
            if (previousContext != null && previousReceiver != null) {
                previousContext.unregisterReceiver(previousReceiver)
            }
        }
        cleanupFailure = cleanup.failureOrNull()
        cleanupFailure?.let { throw it }
    }
}
