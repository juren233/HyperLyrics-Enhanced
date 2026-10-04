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
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.bridge.AppleDirectBinderConnection
import com.juren233.hyperlyricsenhanced.common.bridge.withAppleDirectLifecycle
import com.juren233.hyperlyricsenhanced.common.bridge.AppleDirectReconnectPolicy
import com.juren233.hyperlyricsenhanced.common.bridge.sendAppleDirectBroadcast
import com.juren233.hyperlyricsenhanced.IAppleMusicLyricBridge
import com.juren233.hyperlyricsenhanced.IAppleMusicTranslationReceiver
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.LyricRuntimeDiagnostics
import com.juren233.hyperlyricsenhanced.root.utils.RuntimeResourceCleanup
import com.juren233.hyperlyricsenhanced.root.utils.RuntimeResourceCleanupException
import io.github.proify.extensions.deflate
import io.github.proify.extensions.inflate
import io.github.proify.extensions.json
import io.github.proify.lyricon.amprovider.xposed.AppleDirectBridgeContract
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.concurrent.atomic.AtomicBoolean

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
    @Volatile
    private var registered = false
    private var registrationAttempted = false
    private val lifecycleLock = Any()
    private var cleanupFailure: RuntimeResourceCleanupException? = null
    private val registrationRetry = AppleDirectReconnectPolicy()
    private val bridgeInstance = Integer.toHexString(System.identityHashCode(this))
    private var recoverySequence = 0L
    private var recoveryReason = "none"
    private val staleSongDropLogged = AtomicBoolean(false)
    private var awaitingTranslationReceiver = false
    private val registrationRetryRunnable = Runnable {
        withAppleDirectLifecycle(lifecycleLock) { runRegistrationAttemptLocked() }
    }
    private val translationConnection = AppleDirectBinderConnection<IAppleMusicTranslationReceiver>(
        onDeath = {
            recoveryDiagnostic("direct_translation_receiver_died")
            bridgeDiagnostic("stage=translation_receiver_died")
            requestRegistration("translation_receiver_died", onlyWhenDisconnected = true)
        },
        onLinkFailure = { error ->
            HookLogger.e(TAG, "监听 Apple Music 翻译接收器失败", error)
        },
    )
    private val translationReceiver: IAppleMusicTranslationReceiver?
        get() = translationConnection.current?.target
    @Volatile
    private var latestOnlineTranslationPayload: ByteArray? = null
    @Volatile
    private var latestOnlineTranslationGeneration: Int? = null
    @Volatile
    private var latestMissingLyricsSupplementPayload: ByteArray? = null

    private val binder = object : IAppleMusicLyricBridge.Stub() {
        override fun registerTranslationReceiver(receiver: IAppleMusicTranslationReceiver?) {
            withAppleDirectLifecycle(lifecycleLock) {
                if (!isCurrentConnection()) return
                val entry = translationConnection.replace(receiver)
                if (entry != null && translationConnection.current === entry && entry.binder.isBinderAlive) {
                    // This callback registration, rather than a live lyric Binder or an enqueue,
                    // proves that Apple received an advertisement from this runtime instance.
                    awaitingTranslationReceiver = false
                    registrationRetry.connected()
                    mainHandler.removeCallbacks(registrationRetryRunnable)
                    recoveryDiagnostic("direct_recovery_connected", "receiverRegistered=true")
                } else {
                    requestRegistration("translation_receiver_missing", onlyWhenDisconnected = true)
                }
            }
            bridgeDiagnostic(
                "stage=translation_receiver_registered, receiverPresent=${receiver != null}, " +
                    "binderAlive=${receiver?.asBinder()?.isBinderAlive}, " +
                    "cachedOnlineBytes=${latestOnlineTranslationPayload?.size ?: 0}, " +
                    "cachedSupplementBytes=${latestMissingLyricsSupplementPayload?.size ?: 0}",
            )
            pronunciationDiagnostic(
                "stage=bridge_receiver_registered, generation=$latestOnlineTranslationGeneration, " +
                    "receiverPresent=${receiver != null}, " +
                    "cachedBytes=${latestOnlineTranslationPayload?.size ?: 0}"
            )
            latestOnlineTranslationPayload?.let { payload ->
                sendOnlineTranslationPayload(payload, latestOnlineTranslationGeneration)
            }
            latestMissingLyricsSupplementPayload?.let { payload ->
                sendMissingLyricsSupplementPayload(payload)
            }
        }

        override fun onSongChanged(compressedSong: ByteArray) {
            if (!isCurrentConnection()) {
                recordStaleSongDrop("before_post", compressedSong.size)
                return
            }
            bridgeDiagnostic(
                "stage=direct_song_payload_received, bytes=${compressedSong.size}, " +
                    "empty=${compressedSong.isEmpty()}",
            )
            if (compressedSong.isEmpty()) {
                postToSource(songPayloadBytes = compressedSong.size) { source.onDirectSongChanged(null) }
                return
            }
            val decoded = runCatching {
                json.decodeFromString<Song>(
                    compressedSong.inflate().toString(Charsets.UTF_8)
                )
            }.onFailure {
                // Serialization errors can embed the input JSON, including complete lyric lines.
                val details = "bytes=${compressedSong.size} error=${it.javaClass.name}"
                recoveryDiagnostic("direct_song_decode_failed", details)
                HookLogger.e(TAG, "解析 Apple Music 直连歌词失败: $details")
            }
            if (decoded.isFailure) return
            val song = decoded.getOrThrow()
            bridgeDiagnostic(
                "stage=direct_song_payload_decoded, id=${song.id}, " +
                    "lyrics=${song.lyrics.orEmpty().size}",
            )
            postToSource(songPayloadBytes = compressedSong.size) { source.onDirectSongChanged(song) }
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            postToSource { source.onDirectPlaybackStateChanged(isPlaying) }
        }

        override fun onPositionChanged(position: Long) {
            postToSource { source.onDirectPositionChanged(position) }
        }

        override fun onSeekTo(position: Long) {
            postToSource { source.onDirectSeekTo(position) }
        }

        override fun onReceiveText(text: String?) {
            postToSource { source.onDirectText(text) }
        }

        override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) = Unit

        override fun onDisplayRomaChanged(isDisplayRoma: Boolean) = Unit

        override fun requestOnlineLyricContentSource(
            requestId: Long,
            songId: String?,
            contentType: String?,
            sourceName: String?,
        ) {
            postToSource {
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
            if (!isCurrentConnection() || intent.action != AppleDirectBridgeContract.ACTION_REQUEST) return
            LyricRuntimeDiagnostics.record("direct_request_received") {
                val senderUid = if (Build.VERSION.SDK_INT >= 34) sentFromUid else -1
                "senderUid=$senderUid package=${app.packageName}"
            }
            val senderPackage = if (Build.VERSION.SDK_INT >= 34) sentFromPackage else null
            bridgeDiagnostic(
                "stage=direct_bridge_request_received, senderPackage=$senderPackage",
            )
            if (Build.VERSION.SDK_INT >= 34) {
                sentFromPackage?.let {
                    if (it != AppleDirectBridgeContract.APPLE_MUSIC_PACKAGE) {
                        bridgeDiagnostic(
                            "stage=direct_bridge_request_rejected, senderPackage=$it",
                        )
                        return
                    }
                }
            }
            requestRegistration("player_request", immediately = true)
        }
    }

    fun start() {
        cleanupFailure?.let { throw it }
        bridgeDiagnostic("stage=direct_bridge_start_requested, registered=$registered")
        if (registered) {
            bridgeDiagnostic("stage=direct_bridge_start_skipped, reason=already_registered")
            return
        }
        check(!registrationAttempted) { "Stop the previous direct bridge registration before retrying" }
        // Own the potential registration before the platform call, which may partially succeed.
        registrationAttempted = true
        ContextCompat.registerReceiver(
            app,
            requestReceiver,
            IntentFilter(AppleDirectBridgeContract.ACTION_REQUEST),
            ContextCompat.RECEIVER_EXPORTED
        )
        registered = true
        bridgeDiagnostic("stage=direct_bridge_receiver_registered")
        requestRegistration("initial", immediately = true)
    }

    fun stop() {
        bridgeDiagnostic("stage=direct_bridge_stop_requested, registered=$registered")
        withAppleDirectLifecycle(lifecycleLock) {
            cleanupFailure?.let { throw it }
            val shouldUnregister = registrationAttempted
            registered = false
            registrationAttempted = false
            awaitingTranslationReceiver = false
            registrationRetry.stop()
            if (!shouldUnregister) return
            val cleanup = RuntimeResourceCleanup()
            cleanup.attempt("Apple direct bridge callbacks") { mainHandler.removeCallbacksAndMessages(null) }
            cleanup.attempt("Apple translation receiver death recipient") {
                // Source ownership is already revoked. Clear the former owner's Apple translation
                // directly, without re-entering normal send/reconnect paths or accepting callbacks.
                translationConnection.clearWithFinalNotification { receiver ->
                    receiver.onOnlineTranslationCleared(null)
                }?.let { result ->
                    recoveryDiagnostic(
                        "direct_stop_translation_cleared",
                        "success=${result.isSuccess} error=${result.exceptionOrNull()?.javaClass?.name ?: "none"}",
                    )
                }
                // A dead remote cannot prevent local cleanup or make rollback uncertain.
            }
            cleanup.attempt("Apple direct bridge request receiver") { app.unregisterReceiver(requestReceiver) }
            // A subsequent stop/start cannot turn an uncertain unregister into a clean rollback.
            cleanupFailure = cleanup.failureOrNull()
            cleanupFailure?.let { throw it }
        }
        bridgeDiagnostic("stage=direct_bridge_stopped")
    }

    private fun isCurrentConnection(): Boolean = registered && source.directBridge === this

    private fun postToSource(songPayloadBytes: Int? = null, action: () -> Unit) {
        if (!isCurrentConnection()) {
            songPayloadBytes?.let { recordStaleSongDrop("before_post", it) }
            return
        }
        mainHandler.post {
            if (isCurrentConnection()) action()
            else songPayloadBytes?.let { recordStaleSongDrop("queued_execution", it) }
        }
    }

    private fun recordStaleSongDrop(checkpoint: String, bytes: Int) {
        // Only SONG callbacks use this gate; stale position ticks remain silent.
        if (staleSongDropLogged.compareAndSet(false, true)) {
            recoveryDiagnostic("direct_song_stale_dropped", "checkpoint=$checkpoint bytes=$bytes")
        }
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

    fun publishMissingLyricsSupplement(song: LocalSong): Boolean {
        val payload = json.encodeToString(song)
            .toByteArray(Charsets.UTF_8)
            .deflate()
        if (payload.size > MAX_DIRECT_PAYLOAD_BYTES) {
            HookLogger.e(TAG, "Apple Music 无歌词补充回传载荷过大: bytes=${payload.size}")
            return false
        }
        latestMissingLyricsSupplementPayload = payload
        return sendMissingLyricsSupplementPayload(payload)
    }

    fun clearMissingLyricsSupplement(songId: String?) {
        withAppleDirectLifecycle(lifecycleLock) {
            latestMissingLyricsSupplementPayload = null
            if (!isCurrentConnection()) return
            val entry = translationConnection.current ?: return
            val target = entry.target
            runCatching {
                target.onMissingLyricsSupplementCleared(songId)
            }.onFailure {
                onTranslationSendFailure(entry)
                HookLogger.e(TAG, "清除 Apple Music 无歌词补充失败", it)
            }
        }
    }

    fun clearOnlineTranslation(songId: String?) {
        withAppleDirectLifecycle(lifecycleLock) {
            latestOnlineTranslationPayload = null
            latestOnlineTranslationGeneration = null
            pronunciationDiagnostic(
                "stage=bridge_payload_cleared_systemui, id=$songId, " +
                    "receiverPresent=${translationReceiver != null}"
            )
            if (!isCurrentConnection()) return
            val entry = translationConnection.current ?: return
            val target = entry.target
            runCatching {
                target.onOnlineTranslationCleared(songId)
            }.onFailure {
                onTranslationSendFailure(entry)
                HookLogger.e(TAG, "清除 Apple Music 原生在线翻译失败", it)
            }
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
        withAppleDirectLifecycle(lifecycleLock) {
            if (!isCurrentConnection()) return false
            val entry = translationConnection.current ?: return false
            val target = entry.target
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
                onTranslationSendFailure(entry)
                HookLogger.e(TAG, "回传 Apple Music 在线翻译来源切换结果失败", it)
            }.getOrDefault(false)
        }
    }

    private fun requestRegistration(
        reason: String,
        immediately: Boolean = false,
        onlyWhenDisconnected: Boolean = false,
    ) {
        withAppleDirectLifecycle(lifecycleLock) {
            if (!isCurrentConnection()) return
            // A queued death/send failure from a replaced receiver must not reopen its handshake.
            if (onlyWhenDisconnected && translationConnection.current?.binder?.isBinderAlive == true) return
            val now = SystemClock.elapsedRealtime()
            if (!registrationRetry.request(now)) return
            recoverySequence++
            recoveryReason = reason
            awaitingTranslationReceiver = true
            recoveryDiagnostic("direct_recovery_scheduled")
            bridgeDiagnostic("stage=direct_registration_retry_scheduled, reason=$reason")
            if (immediately && registrationRetry.nextAttemptAtMs?.let { it <= now } == true) {
                runRegistrationAttemptLocked()
            } else {
                scheduleRegistrationRetryLocked()
            }
        }
    }

    /** lifecycleLock serializes sends with stop and callback registration, including reentrant callbacks. */
    private fun runRegistrationAttemptLocked() {
        if (!isCurrentConnection() || !awaitingTranslationReceiver) {
            registrationRetry.connected()
            mainHandler.removeCallbacks(registrationRetryRunnable)
            return
        }
        if (registrationRetry.takeAttempt(SystemClock.elapsedRealtime())) {
            recoveryDiagnostic("direct_recovery_attempt", "state=awaiting_callback")
            runCatching { sendRegistration() }.onFailure { error ->
                // The local receiver is already owned. Delivery failure is handled by this finite
                // burst; a platform receiver-registration failure still escapes start for rollback.
                recoveryDiagnostic("direct_recovery_send_failed", "error=${error.javaClass.name}")
                HookLogger.e(TAG, "广播 Apple Music 直连注册失败", error)
            }
            // A queued callback can still complete the handshake after the last advertisement.
            if (awaitingTranslationReceiver &&
                registrationRetry.snapshot(SystemClock.elapsedRealtime()).budgetConsumed
            ) {
                recoveryDiagnostic("direct_recovery_budget_consumed", "state=awaiting_callback")
            }
        }
        scheduleRegistrationRetryLocked()
    }

    private fun scheduleRegistrationRetryLocked() {
        mainHandler.removeCallbacks(registrationRetryRunnable)
        val next = registrationRetry.nextAttemptAtMs ?: return
        mainHandler.postDelayed(
            registrationRetryRunnable,
            (next - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
        )
    }

    private fun onTranslationSendFailure(
        entry: AppleDirectBinderConnection.Entry<IAppleMusicTranslationReceiver>,
    ) {
        if (translationConnection.clear(entry)) {
            requestRegistration("translation_send_failed", onlyWhenDisconnected = true)
        }
    }

    private fun sendRegistration() {
        val extras = Bundle().apply {
            putBinder(AppleDirectBridgeContract.EXTRA_BINDER, binder.asBinder())
        }
        val intent = Intent(AppleDirectBridgeContract.ACTION_REGISTER)
            .setPackage(AppleDirectBridgeContract.APPLE_MUSIC_PACKAGE)
            .putExtras(extras)
        bridgeDiagnostic(
            "stage=direct_bridge_registration_sending, target=${intent.`package`}, " +
                "binderAlive=${binder.asBinder().isBinderAlive}",
        )
        app.sendAppleDirectBroadcast(intent)
        LyricRuntimeDiagnostics.record("direct_registration_sent") {
            "package=${app.packageName} target=${intent.`package`}"
        }
        bridgeDiagnostic("stage=direct_bridge_registration_sent")
    }

    private fun sendOnlineTranslationPayload(
        payload: ByteArray,
        generation: Int?,
    ): Boolean {
        withAppleDirectLifecycle(lifecycleLock) {
            if (!isCurrentConnection()) return false
            val entry = translationConnection.current ?: run {
                pronunciationDiagnostic(
                    "stage=binder_send_result, generation=$generation, success=false, " +
                        "reason=receiver_missing, bytes=${payload.size}"
                )
                return false
            }
            val target = entry.target
            val success = runCatching {
                target.onOnlineTranslationResult(payload)
                true
            }.onFailure {
                onTranslationSendFailure(entry)
                HookLogger.e(TAG, "回传 Apple Music 原生在线翻译失败", it)
            }.getOrDefault(false)
            pronunciationDiagnostic(
                "stage=binder_send_result, generation=$generation, success=$success, " +
                    "reason=${if (success) "delivered" else "binder_exception"}, bytes=${payload.size}"
            )
            return success
        }
    }

    private fun sendMissingLyricsSupplementPayload(payload: ByteArray): Boolean {
        withAppleDirectLifecycle(lifecycleLock) {
            if (!isCurrentConnection()) return false
            val entry = translationConnection.current ?: run {
                bridgeDiagnostic(
                    "stage=missing_lyrics_supplement_send_result, success=false, " +
                        "reason=receiver_missing, bytes=${payload.size}",
                )
                return false
            }
            val target = entry.target
            val success = runCatching {
                target.onMissingLyricsSupplementResult(payload)
                true
            }.onFailure {
                onTranslationSendFailure(entry)
                HookLogger.e(TAG, "回传 Apple Music 无歌词补充失败", it)
            }.getOrDefault(false)
            bridgeDiagnostic(
                "stage=missing_lyrics_supplement_send_result, success=$success, " +
                    "reason=${if (success) "delivered" else "binder_exception"}, " +
                    "bytes=${payload.size}",
            )
            return success
        }
    }

    private fun pronunciationDiagnostic(message: String) {
        if (BuildConfig.DEBUG) Log.i(PRONUNCIATION_DIAGNOSTIC_TAG, message)
    }

    private fun bridgeDiagnostic(message: String) {
        if (BuildConfig.DEBUG) HookLogger.i(TAG, "[debug] $message")
    }

    private fun recoveryDiagnostic(stage: String, details: String = "") {
        LyricRuntimeDiagnostics.record(stage) {
            withAppleDirectLifecycle(lifecycleLock) {
                "side=systemui bridgeInstance=$bridgeInstance recoverySequence=$recoverySequence " +
                    "reason=$recoveryReason " +
                    registrationRetry.snapshot(SystemClock.elapsedRealtime()).diagnosticFields() +
                    " $details"
            }
        }
    }
}
