/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.bridge.AppleDirectBinderConnection
import com.juren233.hyperlyricsenhanced.common.bridge.AppleDirectReconnectPolicy
import com.juren233.hyperlyricsenhanced.common.bridge.appleDirectSenderIdentity
import com.juren233.hyperlyricsenhanced.common.bridge.sendAppleDirectBroadcast
import com.juren233.hyperlyricsenhanced.root.utils.AppleMetadataFlowDiagnostics
import com.juren233.hyperlyricsenhanced.root.utils.LyricRuntimeDiagnostics
import com.juren233.hyperlyricsenhanced.IAppleMusicLyricBridge
import com.juren233.hyperlyricsenhanced.IAppleMusicTranslationReceiver
import io.github.proify.extensions.deflate
import io.github.proify.extensions.json
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.PlaybackStateActivityPolicy
import io.github.proify.lyricon.provider.RemotePlayer
import kotlinx.serialization.encodeToString

internal object AppleDirectBridgeContract {
    const val ACTION_REQUEST =
        "com.juren233.hyperlyricsenhanced.applemusic.REQUEST_DIRECT_BRIDGE"
    const val ACTION_REGISTER =
        "com.juren233.hyperlyricsenhanced.applemusic.REGISTER_DIRECT_BRIDGE"
    const val ACTION_RESOLVE_ORIGINAL_METADATA =
        "com.juren233.hyperlyricsenhanced.applemusic.RESOLVE_ORIGINAL_METADATA"
    const val EXTRA_BINDER = "bridge"
    const val EXTRA_MEDIA_ID = "media_id"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
}

/** Sends Apple Music data straight to HyperLyrics Enhanced in SystemUI when Central is absent. */
internal class AppleDirectPlayer(
    private val context: Context,
    private val onOriginalMetadataRequested: (String) -> Unit,
    private val onOnlineTranslationReceived: (ByteArray) -> Unit,
    private val onOnlineTranslationCleared: (String?) -> Unit,
    private val onMissingLyricsSupplementReceived: (ByteArray) -> Unit,
    private val onMissingLyricsSupplementCleared: (String?) -> Unit,
    private val onOnlineTranslationSourceSwitchResult:
        (Long, String?, String?, String?, String?, Boolean) -> Unit,
) : RemotePlayer {
    companion object {
        private const val MAX_DIRECT_PAYLOAD_BYTES = 768 * 1024
        private const val PRONUNCIATION_DIAGNOSTIC_TAG = "ApplePronunciationDiag"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectLock = Any()
    private val reconnectPolicy = AppleDirectReconnectPolicy()
    private val bridgeInstance = Integer.toHexString(System.identityHashCode(this))
    private var recoverySequence = 0L
    private var recoveryReason = "none"
    private val reconnectRunnable = Runnable { runReconnectAttempt() }
    private val connection = AppleDirectBinderConnection<IAppleMusicLyricBridge>(
        onDeath = {
            recoveryDiagnostic("direct_bridge_binder_died")
            ProviderLogger.diagnostic("直连诊断: stage=bridge_binder_died")
            requestReconnect("binder_died")
        },
        onLinkFailure = { error ->
            LyricRuntimeDiagnostics.record("direct_death_listener_failed") {
                "error=${error.javaClass.name}"
            }
        },
    )
    @Volatile
    private var latestSongPayload: ByteArray? = null
    @Volatile
    private var registered = false

    private val translationReceiver = object : IAppleMusicTranslationReceiver.Stub() {
        override fun onOnlineTranslationResult(compressedSong: ByteArray) {
            val callbackStartedAtNanos = SystemClock.elapsedRealtimeNanos()
            pronunciationDiagnostic(
                "stage=bridge_payload_callback, bytes=${compressedSong.size}"
            )
            ProviderLogger.diagnostic(
                "[SourceSwitchPerf] stage=bridge_translation_callback_enter, " +
                    "bytes=${compressedSong.size}, thread=${Thread.currentThread().name}"
            )
            this@AppleDirectPlayer.onOnlineTranslationReceived(compressedSong)
            ProviderLogger.diagnostic(
                "[SourceSwitchPerf] stage=bridge_translation_callback_delegate_finished, " +
                    "bytes=${compressedSong.size}, elapsedMs=" +
                    ((SystemClock.elapsedRealtimeNanos() - callbackStartedAtNanos) / 1_000_000.0) +
                    ", thread=${Thread.currentThread().name}"
            )
        }

        override fun onOnlineTranslationCleared(songId: String?) {
            pronunciationDiagnostic("stage=bridge_payload_cleared, id=$songId")
            this@AppleDirectPlayer.onOnlineTranslationCleared(songId)
        }

        override fun onMissingLyricsSupplementCleared(songId: String?) {
            pronunciationDiagnostic("stage=bridge_supplement_payload_cleared, id=$songId")
            this@AppleDirectPlayer.onMissingLyricsSupplementCleared(songId)
        }

        override fun onMissingLyricsSupplementResult(compressedSong: ByteArray) {
            val callbackStartedAtNanos = SystemClock.elapsedRealtimeNanos()
            pronunciationDiagnostic(
                "stage=bridge_supplement_payload_callback, bytes=${compressedSong.size}"
            )
            ProviderLogger.diagnostic(
                "[SourceSwitchPerf] stage=bridge_supplement_callback_enter, " +
                    "bytes=${compressedSong.size}, thread=${Thread.currentThread().name}"
            )
            this@AppleDirectPlayer.onMissingLyricsSupplementReceived(compressedSong)
            ProviderLogger.diagnostic(
                "[SourceSwitchPerf] stage=bridge_supplement_callback_delegate_finished, " +
                    "bytes=${compressedSong.size}, elapsedMs=" +
                    ((SystemClock.elapsedRealtimeNanos() - callbackStartedAtNanos) / 1_000_000.0) +
                    ", thread=${Thread.currentThread().name}"
            )
        }

        override fun onOnlineTranslationSourceSwitchResult(
            requestId: Long,
            songId: String?,
            contentType: String?,
            requestedSource: String?,
            actualSource: String?,
            successful: Boolean,
        ) {
            this@AppleDirectPlayer.onOnlineTranslationSourceSwitchResult(
                requestId,
                songId,
                contentType,
                requestedSource,
                actualSource,
                successful,
            )
        }
    }

    private val registrationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!registered) return
            if (intent.action != AppleDirectBridgeContract.ACTION_REGISTER &&
                intent.action != AppleDirectBridgeContract.ACTION_RESOLVE_ORIGINAL_METADATA
            ) return
            val identity = appleDirectSenderIdentity(context, AppleDirectBridgeContract.SYSTEM_UI_PACKAGE)
            val senderInfo = "uid=${identity.sender.uid}, package=${identity.sender.packageName}, " +
                "expectedUid=${identity.expectedUid}, identity=${identity.decision}"
            recoveryDiagnostic("direct_registration_callback", "action=${intent.action} $senderInfo")
            if (!identity.decision.accepted) {
                recoveryDiagnostic("direct_registration_rejected", senderInfo)
                ProviderLogger.diagnostic("拒绝非 SystemUI 直连消息：$senderInfo")
                return
            }
            // LEGACY_UNKNOWN_IDENTITY deliberately stays visible and is never called authenticated.
            ProviderLogger.diagnostic("直连诊断: stage=sender_identity, $senderInfo")
            when (intent.action) {
                AppleDirectBridgeContract.ACTION_REGISTER -> {
                    val binder = intent.extras
                        ?.getBinder(AppleDirectBridgeContract.EXTRA_BINDER)
                        ?: run {
                            ProviderLogger.diagnostic(
                                "直连诊断: stage=registration_missing_binder, $senderInfo",
                            )
                            return
                        }
                    ProviderLogger.diagnostic(
                        "直连诊断: stage=registration_received, " +
                        "alive=${binder.isBinderAlive}, $senderInfo",
                    )
                    pronunciationDiagnostic(
                        "stage=bridge_registration_received, " +
                        "alive=${binder.isBinderAlive}, $senderInfo"
                    )
                    connect(binder)
                }
                AppleDirectBridgeContract.ACTION_RESOLVE_ORIGINAL_METADATA -> {
                    val mediaId = intent.getStringExtra(AppleDirectBridgeContract.EXTRA_MEDIA_ID)
                        ?.takeIf { it.all(Char::isDigit) }
                        ?: return
                    onOriginalMetadataRequested(mediaId)
                }
            }
        }
    }

    fun start() {
        ProviderLogger.diagnostic(
            "直连诊断: stage=player_start_requested, registered=$registered",
        )
        if (registered) {
            ProviderLogger.diagnostic(
                "直连诊断: stage=player_start_skipped, reason=already_registered",
            )
            return
        }
        ContextCompat.registerReceiver(
            context,
            registrationReceiver,
            IntentFilter().apply {
                addAction(AppleDirectBridgeContract.ACTION_REGISTER)
                addAction(AppleDirectBridgeContract.ACTION_RESOLVE_ORIGINAL_METADATA)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        registered = true
        ProviderLogger.diagnostic("直连诊断: stage=player_receiver_registered")
        pronunciationDiagnostic("stage=direct_player_started")
        requestReconnect("initial")
    }

    private fun requestReconnect(reason: String, allowReconnect: Boolean = true) {
        synchronized(reconnectLock) {
            if (!registered || !reconnectPolicy.request(SystemClock.elapsedRealtime(), allowReconnect)) return
            recoverySequence++
            recoveryReason = reason
            recoveryDiagnostic("direct_recovery_scheduled")
            ProviderLogger.diagnostic("直连诊断: stage=reconnect_scheduled, reason=$reason")
            scheduleReconnectLocked()
        }
    }

    private fun scheduleReconnectLocked() {
        mainHandler.removeCallbacks(reconnectRunnable)
        val next = reconnectPolicy.nextAttemptAtMs ?: return
        mainHandler.postDelayed(reconnectRunnable, (next - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    private fun runReconnectAttempt() {
        val shouldSend = synchronized(reconnectLock) {
            if (!registered || isActive) {
                reconnectPolicy.connected()
                false
            } else reconnectPolicy.takeAttempt(SystemClock.elapsedRealtime()).also { taken ->
                if (taken) recoveryDiagnostic("direct_recovery_attempt", "state=awaiting_callback")
            }
        }
        if (shouldSend) {
            runCatching { requestBridge() }.onFailure { error ->
                recoveryDiagnostic("direct_recovery_send_failed", "error=${error.javaClass.name}")
                ProviderLogger.error("请求 Apple Music 直连桥接失败", error)
            }
        }
        synchronized(reconnectLock) {
            // Consuming the last send is not a failed handshake: its callback may still arrive.
            if (shouldSend && !isActive &&
                reconnectPolicy.snapshot(SystemClock.elapsedRealtime()).budgetConsumed
            ) {
                recoveryDiagnostic("direct_recovery_budget_consumed", "state=awaiting_callback")
            }
            scheduleReconnectLocked()
        }
    }

    private fun requestBridge() {
        LyricRuntimeDiagnostics.record("direct_request_sending") {
            "package=${context.packageName} target=${AppleDirectBridgeContract.SYSTEM_UI_PACKAGE}"
        }
        ProviderLogger.diagnostic(
            "直连诊断: stage=bridge_request_sending, " +
                "target=${AppleDirectBridgeContract.SYSTEM_UI_PACKAGE}",
        )
        pronunciationDiagnostic("stage=bridge_requested")
        context.sendAppleDirectBroadcast(
            Intent(AppleDirectBridgeContract.ACTION_REQUEST)
                .setPackage(AppleDirectBridgeContract.SYSTEM_UI_PACKAGE)
        )
        ProviderLogger.diagnostic("直连诊断: stage=bridge_request_sent")
    }

    private fun connect(binder: IBinder) {
        val entry = connection.replace(IAppleMusicLyricBridge.Stub.asInterface(binder))
        if (entry == null) {
            requestReconnect("death_listener_failed")
            return
        }
        val receiverRegistered = send { it.registerTranslationReceiver(translationReceiver) }
        if (receiverRegistered) synchronized(reconnectLock) {
            if (connection.current === entry && entry.binder.isBinderAlive) {
                reconnectPolicy.connected()
                mainHandler.removeCallbacks(reconnectRunnable)
                recoveryDiagnostic("direct_recovery_connected", "receiverRegistered=true")
            }
        }
        pronunciationDiagnostic(
            "stage=bridge_connected, alive=${binder.isBinderAlive}, " +
                "receiverRegistered=$receiverRegistered"
        )
        ProviderLogger.diagnostic(
            "直连诊断: stage=bridge_connected, alive=${binder.isBinderAlive}, " +
                "receiverRegistered=$receiverRegistered",
        )
        latestSongPayload?.let { payload ->
            val replayed = send { target -> target.onSongChanged(payload) }
            recoveryDiagnostic("direct_song_replay", "bytes=${payload.size} success=$replayed")
            ProviderLogger.debug(
                "直连重连后补发当前歌曲：bytes=${payload.size}, success=$replayed"
            )
            if (BuildConfig.DEBUG) AppleMetadataFlowDiagnostics.record("direct_replay") {
                "bytes=${payload.size} success=$replayed"
            }
        }
    }

    override val isActive: Boolean
        get() = connection.current?.binder?.isBinderAlive == true

    override fun setSong(song: Song?): Boolean {
        val payload = song?.let {
            json.encodeToString(it).toByteArray(Charsets.UTF_8).deflate()
        } ?: byteArrayOf()
        if (payload.size > MAX_DIRECT_PAYLOAD_BYTES) {
            ProviderLogger.error("直连歌词载荷过大：bytes=${payload.size}")
            return false
        }
        latestSongPayload = payload
        return send(reconnectIfMissing = true) { target -> target.onSongChanged(payload) }
    }

    override fun setPlaybackState(playing: Boolean): Boolean =
        send(reconnectIfMissing = true) { it.onPlaybackStateChanged(playing) }

    override fun seekTo(position: Long): Boolean = send(reconnectIfMissing = true) { it.onSeekTo(position) }

    override fun setPosition(position: Long): Boolean = send { it.onPositionChanged(position) }

    override fun setPositionUpdateInterval(interval: Int): Boolean = true

    override fun sendText(text: String?): Boolean = send(reconnectIfMissing = true) { it.onReceiveText(text) }

    override fun setDisplayTranslation(displayTranslation: Boolean): Boolean =
        send(reconnectIfMissing = true) { it.onDisplayTranslationChanged(displayTranslation) }

    override fun setDisplayRoma(displayRoma: Boolean): Boolean =
        send(reconnectIfMissing = true) { it.onDisplayRomaChanged(displayRoma) }

    fun requestOnlineLyricContentSource(
        requestId: Long,
        songId: String,
        contentType: String,
        source: String,
    ): Boolean = send(reconnectIfMissing = true) {
        it.requestOnlineLyricContentSource(requestId, songId, contentType, source)
    }

    override fun setPlaybackState(state: PlaybackState?): Boolean =
        setPlaybackState(PlaybackStateActivityPolicy.keepsSessionActive(state?.state))

    private inline fun send(
        reconnectIfMissing: Boolean = false,
        action: (IAppleMusicLyricBridge) -> Unit,
    ): Boolean {
        // Song, playback and user actions may renew a bounded burst; position ticks cannot.
        val entry = connection.current ?: run {
            requestReconnect("send_without_bridge", allowReconnect = reconnectIfMissing)
            return false
        }
        val target = entry.target
        return runCatching {
            action(target)
            true
        }.onFailure { error ->
            LyricRuntimeDiagnostics.record("direct_send_failed") {
                "error=${error.javaClass.name} binderAlive=${target.asBinder()?.isBinderAlive}"
            }
            if (connection.clear(entry)) requestReconnect("send_failed")
        }.getOrDefault(false)
    }

    private fun pronunciationDiagnostic(message: String) {
        if (BuildConfig.DEBUG) Log.i(PRONUNCIATION_DIAGNOSTIC_TAG, message)
    }

    private fun recoveryDiagnostic(stage: String, details: String = "") {
        LyricRuntimeDiagnostics.record(stage) {
            synchronized(reconnectLock) {
                "side=player bridgeInstance=$bridgeInstance recoverySequence=$recoverySequence " +
                    "reason=$recoveryReason " +
                    reconnectPolicy.snapshot(SystemClock.elapsedRealtime()).diagnosticFields() +
                    " $details"
            }
        }
    }
}

internal class CompositeRemotePlayer(
    private val central: RemotePlayer,
    private val direct: RemotePlayer
) : RemotePlayer {
    private var lastPositionDiagnosticAtMs = 0L
    private var lastPositionDiagnosticState: String? = null

    override val isActive: Boolean
        get() = central.isActive || direct.isActive

    override fun setSong(song: Song?): Boolean {
        val centralResult = runCatching { central.setSong(song) }.getOrDefault(false)
        val directResult = runCatching { direct.setSong(song) }.getOrDefault(false)
        if (BuildConfig.DEBUG) AppleMetadataFlowDiagnostics.record("provider_fanout") {
            "centralResult=$centralResult directResult=$directResult " +
                "song=${AppleMetadataFlowDiagnostics.provider(song)}"
        }
        return centralResult || directResult
    }

    override fun setPlaybackState(playing: Boolean): Boolean =
        both { it.setPlaybackState(playing) }

    override fun seekTo(position: Long): Boolean = both { it.seekTo(position) }

    override fun setPosition(position: Long): Boolean {
        val centralActive = central.isActive
        val directActive = direct.isActive
        val centralResult = runCatching { central.setPosition(position) }.getOrDefault(false)
        val directResult = runCatching { direct.setPosition(position) }.getOrDefault(false)
        if (BuildConfig.DEBUG) {
            val now = SystemClock.elapsedRealtime()
            AppleMetadataFlowDiagnostics.checkpoint("apple_playback") {
                "centralActive=$centralActive directActive=$directActive"
            }
            val state = "$centralActive|$centralResult|$directActive|$directResult"
            if (state != lastPositionDiagnosticState ||
                now - lastPositionDiagnosticAtMs >= POSITION_DIAGNOSTIC_INTERVAL_MS
            ) {
                ProviderLogger.diagnostic(
                    "Timing position fanout: position=$position, centralActive=$centralActive, " +
                        "centralResult=$centralResult, directActive=$directActive, " +
                        "directResult=$directResult"
                )
                lastPositionDiagnosticAtMs = now
                lastPositionDiagnosticState = state
            }
        }
        return centralResult || directResult
    }

    override fun setPositionUpdateInterval(interval: Int): Boolean =
        both { it.setPositionUpdateInterval(interval) }

    override fun sendText(text: String?): Boolean = both { it.sendText(text) }

    override fun setDisplayTranslation(displayTranslation: Boolean): Boolean =
        both { it.setDisplayTranslation(displayTranslation) }

    override fun setDisplayRoma(displayRoma: Boolean): Boolean =
        both { it.setDisplayRoma(displayRoma) }

    override fun setPlaybackState(state: PlaybackState?): Boolean {
        val centralResult = runCatching { central.setPlaybackState(state) }.getOrDefault(false)
        val directResult = runCatching { direct.setPlaybackState(state) }.getOrDefault(false)
        if (BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "Timing playback anchor fanout: state=${state?.state}, " +
                    "position=${state?.position}, centralResult=$centralResult, " +
                    "directResult=$directResult"
            )
        }
        return centralResult || directResult
    }

    private inline fun both(action: (RemotePlayer) -> Boolean): Boolean {
        val centralResult = runCatching { action(central) }.getOrDefault(false)
        val directResult = runCatching { action(direct) }.getOrDefault(false)
        return centralResult || directResult
    }

    private companion object {
        private const val POSITION_DIAGNOSTIC_INTERVAL_MS = 5_000L
    }
}
