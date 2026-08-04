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
import android.util.Log
import androidx.core.content.ContextCompat
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.IAppleMusicLyricBridge
import com.juren233.hyperlyricsenhanced.IAppleMusicTranslationReceiver
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.proify.extensions.deflate
import io.github.proify.extensions.inflate
import io.github.proify.extensions.json
import io.github.proify.lyricon.amprovider.xposed.AppleDirectBridgeContract
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Direct Binder bridge used by the built-in Apple Music provider without Lyricon Central. */
internal class AppleMusicDirectBridge(
    private val app: Application,
    private val source: LyriconSource
) {
    companion object {
        private const val TAG = "AppleMusicDirectBridge"
        private const val MAX_DIRECT_PAYLOAD_BYTES = 768 * 1024
        private const val PRONUNCIATION_DIAGNOSTIC_TAG = "ApplePronunciationDiag"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var registered = false
    @Volatile
    private var translationReceiver: IAppleMusicTranslationReceiver? = null
    @Volatile
    private var latestOnlineTranslationPayload: ByteArray? = null
    @Volatile
    private var latestOnlineTranslationGeneration: Int? = null

    private val binder = object : IAppleMusicLyricBridge.Stub() {
        override fun registerTranslationReceiver(receiver: IAppleMusicTranslationReceiver?) {
            translationReceiver = receiver
            receiver?.asBinder()?.let { binder ->
                runCatching {
                    binder.linkToDeath({ translationReceiver = null }, 0)
                }
            }
            pronunciationDiagnostic(
                "stage=bridge_receiver_registered, generation=$latestOnlineTranslationGeneration, " +
                    "receiverPresent=${receiver != null}, " +
                    "cachedBytes=${latestOnlineTranslationPayload?.size ?: 0}"
            )
            latestOnlineTranslationPayload?.let { payload ->
                sendOnlineTranslationPayload(payload, latestOnlineTranslationGeneration)
            }
        }

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

        override fun requestOnlineLyricContentSource(
            requestId: Long,
            songId: String?,
            contentType: String?,
            sourceName: String?,
        ) {
            mainHandler.post {
                source.onDirectOnlineLyricContentSourceRequested(
                    requestId = requestId,
                    songId = songId,
                    contentType = contentType,
                    sourceName = sourceName,
                )
            }
        }
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
        translationReceiver = null
        registered = false
    }

    fun publishOnlineTranslation(song: LocalSong, generation: Int? = null): Boolean {
        val payload = json.encodeToString(song)
            .toByteArray(Charsets.UTF_8)
            .deflate()
        val romanizedLines = song.lyrics.orEmpty().count { !it.roma.isNullOrBlank() }
        pronunciationDiagnostic(
            "stage=bridge_payload_prepared, generation=$generation, id=${song.id}, " +
                "romanizedLines=$romanizedLines, bytes=${payload.size}, " +
                "receiverPresent=${translationReceiver != null}"
        )
        if (payload.size > MAX_DIRECT_PAYLOAD_BYTES) {
            pronunciationDiagnostic(
                "stage=binder_send_result, generation=$generation, id=${song.id}, " +
                    "success=false, reason=payload_too_large, bytes=${payload.size}"
            )
            HookLogger.e(TAG, "Apple Music 在线翻译回传载荷过大: bytes=${payload.size}")
            return false
        }
        latestOnlineTranslationPayload = payload
        latestOnlineTranslationGeneration = generation
        return sendOnlineTranslationPayload(payload, generation)
    }

    fun clearOnlineTranslation(songId: String?) {
        latestOnlineTranslationPayload = null
        latestOnlineTranslationGeneration = null
        pronunciationDiagnostic(
            "stage=bridge_payload_cleared_systemui, id=$songId, " +
                "receiverPresent=${translationReceiver != null}"
        )
        val target = translationReceiver ?: return
        runCatching {
            target.onOnlineTranslationCleared(songId)
        }.onFailure {
            translationReceiver = null
            HookLogger.e(TAG, "清除 Apple Music 原生在线翻译失败", it)
        }
    }

    fun publishOnlineTranslationSourceSwitchResult(
        requestId: Long,
        songId: String?,
        contentType: String?,
        requestedSource: String?,
        actualSource: String?,
        successful: Boolean,
    ): Boolean {
        val target = translationReceiver ?: return false
        return runCatching {
            target.onOnlineTranslationSourceSwitchResult(
                requestId,
                songId,
                contentType,
                requestedSource,
                actualSource,
                successful,
            )
            true
        }.onFailure {
            translationReceiver = null
            HookLogger.e(TAG, "回传 Apple Music 在线翻译来源切换结果失败", it)
        }.getOrDefault(false)
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

    private fun sendOnlineTranslationPayload(
        payload: ByteArray,
        generation: Int?,
    ): Boolean {
        val target = translationReceiver ?: run {
            pronunciationDiagnostic(
                "stage=binder_send_result, generation=$generation, success=false, " +
                    "reason=receiver_missing, bytes=${payload.size}"
            )
            return false
        }
        val success = runCatching {
            target.onOnlineTranslationResult(payload)
            true
        }.onFailure {
            translationReceiver = null
            HookLogger.e(TAG, "回传 Apple Music 原生在线翻译失败", it)
        }.getOrDefault(false)
        pronunciationDiagnostic(
            "stage=binder_send_result, generation=$generation, success=$success, " +
                "reason=${if (success) "delivered" else "binder_exception"}, bytes=${payload.size}"
        )
        return success
    }

    private fun pronunciationDiagnostic(message: String) {
        if (BuildConfig.DEBUG) Log.i(PRONUNCIATION_DIAGNOSTIC_TAG, message)
    }
}
