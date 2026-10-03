package com.juren233.hyperlyricsenhanced.root.source

import com.hchen.superlyricapi.ISuperLyricReceiver
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricHelper
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSink
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSource
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SuperLyricSource : LyricSource {
    override val id = "superlyric"
    override val displayName = "SuperLyric"

    private var app: android.app.Application? = null
    private var sink: LyricSink? = null
    private var receiver: ISuperLyricReceiver? = null
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
        if (receiver != null) stop()
        this.sink = sink
        contentAdapter.clear()
        if (!isAvailable()) {
            HookLogger.w(TAG, "跳过接收端注册: reason=service_unavailable")
            return
        }

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
            HookLogger.i(TAG, "更新接收端注册状态: registered=$registered")
        } catch (e: Exception) {
            HookLogger.e(TAG, "注册接收端失败", e)
        }
    }

    override fun stop() {
        val previousReceiver = receiver
        receiver = null
        previousReceiver?.let {
            try {
                SuperLyricHelper.unregisterReceiver(it)
            } catch (e: Exception) {
                HookLogger.w(TAG, "注销接收端失败", e)
            }
        }
        contentAdapter.clear()
        sink?.onStop()
        sink = null
        HookLogger.i(TAG, "数据源已停止")
    }

    private companion object {
        const val TAG = "SuperLyricSource"
    }
}
