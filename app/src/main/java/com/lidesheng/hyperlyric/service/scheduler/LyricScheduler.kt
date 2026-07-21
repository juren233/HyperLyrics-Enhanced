package com.lidesheng.hyperlyric.service.scheduler

import android.content.Context
import com.lidesheng.hyperlyric.common.ServiceConstants
import com.lidesheng.hyperlyric.common.UIConstants
import com.lidesheng.hyperlyric.lyric.DynamicLyricData
import com.lidesheng.hyperlyric.lyric.LrcLine
import com.lidesheng.hyperlyric.service.source.SyncData
import com.lidesheng.hyperlyric.utils.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

interface LyricSchedulerListener {
    fun onLyricTick(lyricText: String, data: SyncData)
    fun onProgressTick(progressPercent: Float)
}

class LyricScheduler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: LyricSchedulerListener
) {
    private var tickerJob: Job? = null
    private var progressJob: Job? = null

    @Volatile
    private var currentLyricLines: List<LrcLine>? = null
    @Volatile
    private var currentSyncData: SyncData? = null
    @Volatile
    private var lastDispatchedLrc: String = ""

    /**
     * 更新当前歌曲的歌词行列表，并标记是否是新歌
     */
    fun updateLyrics(lines: List<LrcLine>?, isNewSong: Boolean) {
        currentLyricLines = lines
        if (isNewSong) {
            lastDispatchedLrc = ""
        }
    }

    /**
     * 更新当前媒体状态数据
     */
    fun updateSyncData(data: SyncData) {
        currentSyncData = data
    }

    /**
     * 停止之前的调度器并按需重新启动
     */
    fun startSchedulers(isSongChanged: Boolean, playStateChanged: Boolean) {
        val lines = currentLyricLines
        val data = currentSyncData ?: return

        if (lines != null) {
            // 有滚动歌词
            if (isSongChanged || playStateChanged || tickerJob == null || tickerJob?.isActive != true) {
                launchLyricScheduler(lines)
            }
            if (isSongChanged || playStateChanged || progressJob == null || progressJob?.isActive != true) {
                launchProgressScheduler()
            }
        } else {
            // 没有滚动歌词 (静态标题模式)
            if (isSongChanged || playStateChanged || progressJob == null || progressJob?.isActive != true) {
                launchProgressScheduler()
            }
        }
    }

    /**
     * 停止全部调度任务
     */
    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        progressJob?.cancel()
        progressJob = null
        currentLyricLines = null
        currentSyncData = null
        lastDispatchedLrc = ""
    }

    private fun launchLyricScheduler(lines: List<LrcLine>) {
        tickerJob?.cancel()
        LogManager.d("LyricScheduler", "启动歌词滚动调度器: 行数=${lines.size}")

        tickerJob = scope.launch {
            while (true) {
                val data = currentSyncData ?: break
                val currentPos = with(DynamicLyricData) { musicState.value.getCurrentPosition() }

                val currentLineIndex = lines.indexOfLast { it.startTimeMs <= currentPos }
                val targetLine = if (currentLineIndex != -1) lines[currentLineIndex].content else data.dynamicTitle

                if (targetLine != lastDispatchedLrc) {
                    lastDispatchedLrc = targetLine
                    listener.onLyricTick(targetLine, data)
                }

                if (!data.isPlaying) break
                delay(150.milliseconds)
            }
        }
    }

    private fun launchProgressScheduler() {
        progressJob?.cancel()
        val sp = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        val showProgress = sp.getBoolean(ServiceConstants.KEY_NOTIFICATION_SHOW_PROGRESS, ServiceConstants.DEFAULT_NOTIFICATION_SHOW_PROGRESS)
        if (!showProgress) return
        LogManager.d("LyricScheduler", "启动播放进度调度器")

        progressJob = scope.launch {
            var lastPercent = -1
            while (true) {
                val data = currentSyncData ?: break
                val duration = data.duration
                if (!data.isPlaying || duration <= 1000) break

                val currentPos = with(DynamicLyricData) { musicState.value.getCurrentPosition() }
                val currentPercent = ((currentPos.toDouble() / duration.toDouble()) * 100).toInt().coerceIn(0, 100)

                if (currentPercent != lastPercent) {
                    listener.onProgressTick(currentPercent.toFloat())
                    lastPercent = currentPercent
                }

                if (currentPercent >= 100) break
                delay(1000.milliseconds)
            }
        }
    }
}
