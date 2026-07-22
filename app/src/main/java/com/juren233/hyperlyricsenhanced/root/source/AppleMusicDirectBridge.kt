package com.juren233.hyperlyricsenhanced.root.source

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.juren233.hyperlyricsenhanced.IAppleMusicLyricBridge
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.proify.extensions.inflate
import io.github.proify.extensions.json
import io.github.proify.lyricon.amprovider.xposed.AppleDirectBridgeContract
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.serialization.decodeFromString

/** Direct Binder bridge used by the built-in Apple Music provider without Lyricon Central. */
internal class AppleMusicDirectBridge(
    private val app: Application,
    private val source: LyriconSource
) {
    companion object {
        private const val TAG = "AppleMusicDirectBridge"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var registered = false

    private val binder = object : IAppleMusicLyricBridge.Stub() {
        override fun onSongChanged(compressedSong: ByteArray) {
            if (compressedSong.isEmpty()) {
                mainHandler.post { source.onDirectSongChanged(null) }
                return
            }
            val decoded = runCatching {
                json.decodeFromString<Song>(
                    compressedSong.inflate().toString(Charsets.UTF_8)
                )
            }.onFailure {
                HookLogger.e(TAG, "解析 Apple Music 直连歌词失败", it)
            }
            if (decoded.isFailure) return
            mainHandler.post { source.onDirectSongChanged(decoded.getOrThrow()) }
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            mainHandler.post { source.onDirectPlaybackStateChanged(isPlaying) }
        }

        override fun onPositionChanged(position: Long) {
            mainHandler.post { source.onDirectPositionChanged(position) }
        }

        override fun onSeekTo(position: Long) {
            mainHandler.post { source.onDirectSeekTo(position) }
        }

        override fun onReceiveText(text: String?) {
            mainHandler.post { source.onDirectText(text) }
        }

        override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) = Unit

        override fun onDisplayRomaChanged(isDisplayRoma: Boolean) = Unit
    }

    private val requestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AppleDirectBridgeContract.ACTION_REQUEST) return
            if (Build.VERSION.SDK_INT >= 34) {
                sentFromPackage?.let {
                    if (it != AppleDirectBridgeContract.APPLE_MUSIC_PACKAGE) return
                }
            }
            sendRegistration()
        }
    }

    fun start() {
        if (registered) return
        ContextCompat.registerReceiver(
            app,
            requestReceiver,
            IntentFilter(AppleDirectBridgeContract.ACTION_REQUEST),
            ContextCompat.RECEIVER_EXPORTED
        )
        registered = true
        sendRegistration()
    }

    fun stop() {
        if (!registered) return
        runCatching { app.unregisterReceiver(requestReceiver) }
        registered = false
    }

    private fun sendRegistration() {
        val extras = Bundle().apply {
            putBinder(AppleDirectBridgeContract.EXTRA_BINDER, binder.asBinder())
        }
        val intent = Intent(AppleDirectBridgeContract.ACTION_REGISTER)
            .setPackage(AppleDirectBridgeContract.APPLE_MUSIC_PACKAGE)
            .putExtras(extras)
        app.sendBroadcast(intent)
    }
}
