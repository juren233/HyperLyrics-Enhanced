package com.juren233.hyperlyricsenhanced.root.source

import com.hchen.superlyricapi.ISuperLyricReceiver
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricHelper
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSink
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSource
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.RuntimeResourceCleanup
import com.juren233.hyperlyricsenhanced.root.utils.RuntimeResourceCleanupException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SuperLyricSource : LyricSource {
    override val id = "superlyric"
    override val displayName = "SuperLyric"

    private var app: android.app.Application? = null
    private var sink: LyricSink? = null
    @Volatile
    private var receiver: ISuperLyricReceiver? = null
    private var cleanupFailure: RuntimeResourceCleanupException? = null
    private val callbackScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val contentAdapter = SuperLyricContentAdapter(SuperLyricHelper::getLatestLyric)

    fun initialize(app: android.app.Application) {
        this.app = app
    }

    override fun isAvailable(): Boolean = try {
        SuperLyricHelper.isAvailable()
    } catch (e: Exception) {
        HookLogger.w(TAG, "检查数据源可用性失败", e)
        false
    }

    override fun start(sink: LyricSink) {
        cleanupFailure?.let { throw it }
        if (receiver != null) stop()
        this.sink = sink
        contentAdapter.clear()
        check(isAvailable()) { "SuperLyric service is unavailable" }

        val stub = object : ISuperLyricReceiver.Stub() {
            private var firstCallback = true

            override fun onLyric(publisher: String, data: SuperLyricData) {
                val callback = this
                // Binder callbacks share the main-thread owner with start/stop and the timeline.
                callbackScope.launch {
                    if (receiver !== callback) return@launch
                    val currentSink = this@SuperLyricSource.sink ?: return@launch
                    if (BuildConfig.DEBUG && firstCallback) {
                        firstCallback = false
                        HookLogger.d(TAG, "首次歌词回调: publisher=$publisher, full=${data.hasAllLyrics()}")
                    }
                    try {
                        contentAdapter.publish(publisher, data, currentSink) {
                            app?.let { MediaMetadataHelper.getPlaybackPosition(it, publisher) }
                                ?.takeIf { it >= 0L }
                        }
                    } catch (e: Exception) {
                        HookLogger.w(TAG, "处理歌词数据失败", e)
                    }
                }
            }

            override fun onStop(publisher: String, data: SuperLyricData) {
                val callback = this
                callbackScope.launch {
                    if (receiver !== callback) return@launch
                    if (contentAdapter.onStop(publisher)) this@SuperLyricSource.sink?.onStop()
                }
            }
        }
        receiver = stub
        try {
            SuperLyricHelper.registerReceiver(stub)
            val registered = SuperLyricHelper.isReceiverRegistered(stub)
            check(registered) { "SuperLyric receiver registration was not confirmed" }
            HookLogger.i(TAG, "更新接收端注册状态: registered=$registered")
        } catch (error: Throwable) {
            HookLogger.e(TAG, "注册接收端失败", error)
            throw error
        }
    }

    override fun stop() {
        val previousReceiver = receiver
        val previousSink = sink
        receiver = null
        sink = null
        val cleanup = RuntimeResourceCleanup()
        cleanup.attempt("previous SuperLyric cleanup") { cleanupFailure?.let { throw it } }
        cleanup.attempt("SuperLyric receiver") {
            previousReceiver?.let(SuperLyricHelper::unregisterReceiver)
        }
        cleanup.attempt("SuperLyric content") { contentAdapter.clear() }
        cleanup.attempt("SuperLyric sink") { previousSink?.onStop() }
        cleanupFailure = cleanup.failureOrNull()
        cleanupFailure?.let { throw it }
        HookLogger.i(TAG, "数据源已停止")
    }

    private companion object {
        const val TAG = "SuperLyricSource"
    }
}
