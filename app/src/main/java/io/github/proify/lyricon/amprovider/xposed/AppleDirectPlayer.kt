/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.content.ContextCompat
import com.juren233.hyperlyricsenhanced.IAppleMusicLyricBridge
import io.github.proify.extensions.deflate
import io.github.proify.extensions.json
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.RemotePlayer
import kotlinx.serialization.encodeToString

internal object AppleDirectBridgeContract {
    const val ACTION_REQUEST =
        "com.juren233.hyperlyricsenhanced.applemusic.REQUEST_DIRECT_BRIDGE"
    const val ACTION_REGISTER =
        "com.juren233.hyperlyricsenhanced.applemusic.REGISTER_DIRECT_BRIDGE"
    const val EXTRA_BINDER = "bridge"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
}

/** Sends Apple Music data straight to HyperLyrics Enhanced in SystemUI when Central is absent. */
internal class AppleDirectPlayer(private val context: Context) : RemotePlayer {
    companion object {
        private const val MAX_DIRECT_PAYLOAD_BYTES = 768 * 1024
    }

    @Volatile
    private var bridge: IAppleMusicLyricBridge? = null
    @Volatile
    private var latestSongPayload: ByteArray? = null
    private var registered = false

    private val registrationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AppleDirectBridgeContract.ACTION_REGISTER) return
            if (Build.VERSION.SDK_INT >= 34 &&
                sentFromUid >= 0 &&
                sentFromUid != Process.SYSTEM_UID
            ) {
                ProviderLogger.diagnostic(
                    "拒绝非 SystemUI 直连注册：uid=$sentFromUid, package=$sentFromPackage"
                )
                return
            }
            val binder = intent.extras?.getBinder(AppleDirectBridgeContract.EXTRA_BINDER) ?: return
            connect(binder)
        }
    }

    fun start() {
        if (registered) return
        ContextCompat.registerReceiver(
            context,
            registrationReceiver,
            IntentFilter(AppleDirectBridgeContract.ACTION_REGISTER),
            ContextCompat.RECEIVER_EXPORTED
        )
        registered = true
        requestBridge()
    }

    private fun requestBridge() {
        context.sendBroadcast(
            Intent(AppleDirectBridgeContract.ACTION_REQUEST)
                .setPackage(AppleDirectBridgeContract.SYSTEM_UI_PACKAGE)
        )
    }

    private fun connect(binder: IBinder) {
        bridge = IAppleMusicLyricBridge.Stub.asInterface(binder)
        runCatching {
            binder.linkToDeath({
                bridge = null
                requestBridge()
            }, 0)
        }
        ProviderLogger.diagnostic("HyperLyrics Enhanced Apple Music 直连已建立")
        latestSongPayload?.let { payload ->
            val replayed = send { target -> target.onSongChanged(payload) }
            ProviderLogger.debug(
                "直连重连后补发当前歌曲：bytes=${payload.size}, success=$replayed"
            )
        }
    }

    override val isActive: Boolean
        get() = bridge?.asBinder()?.isBinderAlive == true

    override fun setSong(song: Song?): Boolean {
        val payload = song?.let {
            json.encodeToString(it).toByteArray(Charsets.UTF_8).deflate()
        } ?: byteArrayOf()
        if (payload.size > MAX_DIRECT_PAYLOAD_BYTES) {
            ProviderLogger.error("直连歌词载荷过大：bytes=${payload.size}")
            return false
        }
        latestSongPayload = payload
        return send { target -> target.onSongChanged(payload) }
    }

    override fun setPlaybackState(playing: Boolean): Boolean =
        send { it.onPlaybackStateChanged(playing) }

    override fun seekTo(position: Long): Boolean = send { it.onSeekTo(position) }

    override fun setPosition(position: Long): Boolean = send { it.onPositionChanged(position) }

    override fun setPositionUpdateInterval(interval: Int): Boolean = true

    override fun sendText(text: String?): Boolean = send { it.onReceiveText(text) }

    override fun setDisplayTranslation(displayTranslation: Boolean): Boolean =
        send { it.onDisplayTranslationChanged(displayTranslation) }

    override fun setDisplayRoma(displayRoma: Boolean): Boolean =
        send { it.onDisplayRomaChanged(displayRoma) }

    override fun setPlaybackState(state: PlaybackState?): Boolean =
        setPlaybackState(state?.state == PlaybackState.STATE_PLAYING)

    private inline fun send(action: (IAppleMusicLyricBridge) -> Unit): Boolean {
        val target = bridge ?: return false
        return runCatching {
            action(target)
            true
        }.onFailure {
            bridge = null
            requestBridge()
        }.getOrDefault(false)
    }
}

internal class CompositeRemotePlayer(
    private val central: RemotePlayer,
    private val direct: RemotePlayer
) : RemotePlayer {
    override val isActive: Boolean
        get() = central.isActive || direct.isActive

    override fun setSong(song: Song?): Boolean = both { it.setSong(song) }

    override fun setPlaybackState(playing: Boolean): Boolean =
        both { it.setPlaybackState(playing) }

    override fun seekTo(position: Long): Boolean = both { it.seekTo(position) }

    override fun setPosition(position: Long): Boolean = both { it.setPosition(position) }

    override fun setPositionUpdateInterval(interval: Int): Boolean =
        both { it.setPositionUpdateInterval(interval) }

    override fun sendText(text: String?): Boolean = both { it.sendText(text) }

    override fun setDisplayTranslation(displayTranslation: Boolean): Boolean =
        both { it.setDisplayTranslation(displayTranslation) }

    override fun setDisplayRoma(displayRoma: Boolean): Boolean =
        both { it.setDisplayRoma(displayRoma) }

    override fun setPlaybackState(state: PlaybackState?): Boolean =
        both { it.setPlaybackState(state) }

    private inline fun both(action: (RemotePlayer) -> Boolean): Boolean {
        val centralResult = runCatching { action(central) }.getOrDefault(false)
        val directResult = runCatching { action(direct) }.getOrDefault(false)
        return centralResult || directResult
    }
}
