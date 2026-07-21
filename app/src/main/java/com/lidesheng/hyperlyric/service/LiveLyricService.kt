package com.lidesheng.hyperlyric.service

import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.service.notification.NotificationListenerService
import com.lidesheng.hyperlyric.common.lyric.LyricSplitter
import com.lidesheng.hyperlyric.lyric.ConfigRepository
import com.lidesheng.hyperlyric.lyric.DynamicLyricData
import com.lidesheng.hyperlyric.service.source.AppLyricSink
import com.lidesheng.hyperlyric.service.source.MetadataSource
import com.lidesheng.hyperlyric.utils.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

class LiveLyricService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var metadataSource: MetadataSource
    private lateinit var appLyricSink: AppLyricSink
    private lateinit var notificationPresenter: NotificationPresenter

    override fun onCreate() {
        super.onCreate()

        val textPaint = createTextPaint()
        val lyricSplitter = LyricSplitter(textPaint, resources.displayMetrics)

        notificationPresenter = NotificationPresenter(this, serviceScope, lyricSplitter)
        notificationPresenter.register()

        ConfigRepository.initWhitelist(this)

        val componentName = ComponentName(this, LiveLyricService::class.java)
        metadataSource = MetadataSource(this, serviceScope, componentName)
        appLyricSink = AppLyricSink(this, serviceScope, notificationPresenter)

        appLyricSink.startCollecting(metadataSource.lyricUpdateFlow, metadataSource.newSongFlow)

        serviceScope.launch {
            combine(
                DynamicLyricData.musicState,
                DynamicLyricData.progressFlow.onStart { emit(0f) },
                ConfigRepository.whitelistState
            ) { state, _, _ -> state }.collect { state ->
                notificationPresenter.updateState(state, force = false)
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        metadataSource.connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        appLyricSink.stop()
        metadataSource.disconnect()
        notificationPresenter.unregister()
        notificationPresenter.clearNotifications()
        serviceScope.cancel()
    }

    private fun createTextPaint(): Paint {
        val islandBitmapHeight = 128
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
            textSize = 100f
            val rawHeight = fontMetrics.descent - fontMetrics.ascent
            textSize = 100f * (islandBitmapHeight.toFloat() / rawHeight)
        }
    }

    companion object {
        fun ensureListenerBound(context: Context) {
            LogManager.d("LiveLyricService", "正在尝试静默重连 NotificationListenerService")
            try {
                val pm = context.packageManager
                val cn = ComponentName(context, LiveLyricService::class.java)
                pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                requestRebind(cn)
            } catch (e: Exception) {
                LogManager.e("LiveLyricService", "静默重连失败", e)
            }
        }
    }
}
