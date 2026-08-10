/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

import android.media.session.PlaybackState
import android.os.SharedMemory
import android.os.SystemClock
import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.media.NextTrackMetadataCache
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.proify.lyricon.central.inflate
import io.github.proify.lyricon.central.json
import io.github.proify.lyricon.central.util.ScreenStateMonitor
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.IRemotePlayer
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class PlayerBinder(
    info: ProviderInfo,
    private val playerEvents: PlayerListener
) : IRemotePlayer.Stub(), ScreenStateMonitor.ScreenStateListener {

    private val recorder = PlayerRecorder(info)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)
    private val isState2Enabled = AtomicBoolean(false)
    private val closeMutex = Mutex()
    private val positionMemory = PositionMemoryBridge(info)
    private val positionTicker = PositionTicker(
        scope = scope,
        readPosition = ::computeCurrentPosition,
        onPosition = ::publishPosition
    )

    @Volatile
    private var positionUpdateInterval: Long = ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL

    @Volatile
    private var lastPlaybackState: PlaybackState? = null
    private val providerInfo = info
    private var lastPositionDiagnosticAtMs = 0L
    private var lastPositionPublishDiagnosticAtMs = 0L
    private val playbackStateSequence = AtomicLong(0L)

    init {
        ScreenStateMonitor.addListener(this)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        ScreenStateMonitor.removeListener(this)
        stopPositionUpdate()

        scope.launch {
            closeMutex.withLock {
                positionMemory.close()
                scope.cancel()
            }
        }
    }

    override fun onScreenOn() {
        if (recorder.isPlaying) startPositionUpdate()
    }

    override fun onScreenOff() {
        stopPositionUpdate()
    }

    override fun onScreenUnlocked() = Unit

    override fun setPositionUpdateInterval(interval: Int) {
        if (closed.get()) return

        val next = interval.toLong().coerceAtLeast(MIN_INTERVAL_MS)
        if (positionUpdateInterval == next) return

        positionUpdateInterval = next
        if (recorder.isPlaying) {
            stopPositionUpdate()
            startPositionUpdate()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun setSong(bytes: ByteArray?) {
        if (closed.get()) return

        scope.launch {
            val song = bytes?.let {
                runCatching {
                    it.inflate()
                        .inputStream()
                        .buffered()
                        .use {
                            json.decodeFromStream(Song.serializer(), it)
                        }
                }.getOrNull()
            }

            val normalized = song?.normalize()
            recorder.song = normalized
            playerEvents.safeNotify { onSongChanged(recorder, normalized) }
        }
    }

    override fun setPlaybackState(isPlaying: Boolean) {
        if (closed.get()) return

        val sequence = playbackStateSequence.incrementAndGet()
        if (BuildConfig.DEBUG) {
            HookLogger.i(
                TAG,
                "[LyricPositionDiag] stage=central_state_input, mode=legacy_boolean, " +
                    "sequence=$sequence, provider=${providerInfo.providerPackageName}, " +
                    "player=${providerInfo.playerPackageName}, process=${providerInfo.processName}, " +
                    "playing=$isPlaying, decision=disable_state2",
            )
        }

        isState2Enabled.set(false)
        lastPlaybackState = null

        if (recorder.isPlaying != isPlaying) {
            recorder.isPlaying = isPlaying
            playerEvents.safeNotify { onPlaybackStateChanged(recorder, isPlaying) }
        }

        if (isPlaying) startPositionUpdate() else stopPositionUpdate()
    }

    override fun setPlaybackState2(state: PlaybackState?) {
        if (closed.get()) return

        val sequence = playbackStateSequence.incrementAndGet()

        if (state == null) {
            if (BuildConfig.DEBUG) {
                HookLogger.i(
                    TAG,
                    "[LyricPositionDiag] stage=central_state_input, mode=playback_state, " +
                        "sequence=$sequence, provider=${providerInfo.providerPackageName}, " +
                        "player=${providerInfo.playerPackageName}, process=${providerInfo.processName}, " +
                        "state=null, decision=disable_state2",
                )
            }
            if (isState2Enabled.compareAndSet(true, false)) {
                lastPlaybackState = null
                stopPositionUpdate()
            }
            return
        }

        if (BuildConfig.DEBUG) {
            val now = SystemClock.elapsedRealtime()
            HookLogger.i(
                TAG,
                "[LyricPositionDiag] stage=central_state_input, mode=playback_state, " +
                    "sequence=$sequence, provider=${providerInfo.providerPackageName}, " +
                    "player=${providerInfo.playerPackageName}, process=${providerInfo.processName}, " +
                    "state=${state.state}, position=${state.position}, " +
                    "updatedAt=${state.lastPositionUpdateTime}, now=$now, " +
                    "anchorAgeMs=${now - state.lastPositionUpdateTime}, " +
                    "speed=${state.playbackSpeed}, buffered=${state.bufferedPosition}, " +
                    "decision=${if (state.state == PlaybackState.STATE_BUFFERING) "ignore_buffering" else "accept_state2"}",
            )
        }

        if (state.state == PlaybackState.STATE_BUFFERING) return

        val isPlaying = state.state == PlaybackState.STATE_PLAYING
        isState2Enabled.set(true)
        lastPlaybackState = state

        if (recorder.isPlaying != isPlaying) {
            recorder.isPlaying = isPlaying
            playerEvents.safeNotify { onPlaybackStateChanged(recorder, isPlaying) }
        }

        if (isPlaying) startPositionUpdate() else stopPositionUpdate()
    }

    override fun seekTo(position: Long) {
        if (closed.get()) return

        val safe = position.coerceAtLeast(0L)
        if (BuildConfig.DEBUG) {
            HookLogger.i(
                TAG,
                "[LyricPositionDiag] stage=central_seek_input, " +
                    "provider=${providerInfo.providerPackageName}, " +
                    "player=${providerInfo.playerPackageName}, process=${providerInfo.processName}, " +
                    "position=$safe",
            )
        }
        recorder.position = safe
        playerEvents.safeNotify { onSeekTo(recorder, safe) }
    }

    override fun sendText(text: String?) {
        if (closed.get()) return

        if (OfficialProviderControlProtocol.isReservedFrame(text)) {
            val frame = OfficialProviderControlProtocol.decodeNextTrack(text)
            val result = if (frame == null) {
                NextTrackMetadataCache.ControlResult.REJECTED_FRAME
            } else {
                NextTrackMetadataCache.accept(
                    providerPackageName = providerInfo.providerPackageName,
                    playerPackageName = providerInfo.playerPackageName,
                    frame = frame,
                )
            }
            if (BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "Next-track control: result=$result, " +
                        "provider=${providerInfo.providerPackageName}, " +
                        "player=${providerInfo.playerPackageName}, " +
                        "process=${providerInfo.processName}, current=${frame?.currentId}, " +
                        "next=${frame?.nextId}",
                )
            }
            return
        }

        recorder.text = text
        playerEvents.safeNotify { onSendText(recorder, text) }
    }

    override fun setDisplayTranslation(isDisplayTranslation: Boolean) {
        if (closed.get()) return

        recorder.isDisplayTranslation = isDisplayTranslation
        playerEvents.safeNotify { onDisplayTranslationChanged(recorder, isDisplayTranslation) }
    }

    override fun setDisplayRoma(isDisplayRoma: Boolean) {
        if (closed.get()) return

        recorder.isDisplayRoma = isDisplayRoma
        playerEvents.safeNotify { onDisplayRomaChanged(recorder, isDisplayRoma) }
    }

    override fun getPositionMemory(): SharedMemory? = positionMemory.sharedMemory

    private fun computeCurrentPosition(): Long {
        val state2Enabled = isState2Enabled.get()
        val position = if (!state2Enabled) {
            positionMemory.readPosition()
        } else {
            val state = lastPlaybackState
            if (state == null) {
                0L
            } else {
                val basePosition = state.position.coerceAtLeast(0L)
                val lastUpdate = state.lastPositionUpdateTime
                if (state.state != PlaybackState.STATE_PLAYING || lastUpdate <= 0L) {
                    basePosition
                } else {
                    val delta = (SystemClock.elapsedRealtime() - lastUpdate).coerceAtLeast(0L)
                    if (state.playbackSpeed == 1.0f) {
                        basePosition + delta
                    } else {
                        basePosition + (delta * state.playbackSpeed).toLong()
                    }
                }
            }
        }
        val safePosition = position.coerceAtLeast(0L)
        logPositionDiagnostic(safePosition, state2Enabled)
        return safePosition
    }

    private fun logPositionDiagnostic(position: Long, state2Enabled: Boolean) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPositionDiagnosticAtMs < POSITION_DIAGNOSTIC_INTERVAL_MS) return
        lastPositionDiagnosticAtMs = now
        val state = lastPlaybackState
        HookLogger.i(
            TAG,
            "[LyricPositionDiag] stage=central_position_read, " +
                "provider=${providerInfo.providerPackageName}, " +
                "player=${providerInfo.playerPackageName}, process=${providerInfo.processName}, " +
                "source=${if (state2Enabled) "playback_state" else "shared_memory"}, " +
                "position=$position, playing=${recorder.isPlaying}, " +
                "state=${state?.state}, statePosition=${state?.position}, " +
                "stateUpdatedAt=${state?.lastPositionUpdateTime}, stateSpeed=${state?.playbackSpeed}, " +
                positionMemory.diagnosticSummary(),
        )
    }

    private fun startPositionUpdate() {
        if (closed.get()) return
        if (ScreenStateMonitor.state == ScreenStateMonitor.ScreenState.OFF) return
        positionTicker.start(positionUpdateInterval)
    }

    private fun stopPositionUpdate() {
        positionTicker.stop()
    }

    private fun publishPosition(position: Long) {
        recorder.position = position
        playerEvents.safeNotify { onPositionChanged(recorder, position) }
        logPositionPublishDiagnostic(position)
    }

    private fun logPositionPublishDiagnostic(position: Long) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPositionPublishDiagnosticAtMs < POSITION_DIAGNOSTIC_INTERVAL_MS) return
        lastPositionPublishDiagnosticAtMs = now
        HookLogger.i(
            TAG,
            "[LyricPositionDiag] stage=central_provider_publish, " +
                "provider=${providerInfo.providerPackageName}, " +
                "player=${providerInfo.playerPackageName}, process=${providerInfo.processName}, " +
                "position=$position, playing=${recorder.isPlaying}, " +
                "state2=${isState2Enabled.get()}"
        )
    }

    private inline fun PlayerListener.safeNotify(crossinline block: PlayerListener.() -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "player event dispatch failed", e)
        }
    }

    private companion object {
        private const val TAG = "PlayerBinder"
        private const val MIN_INTERVAL_MS = 16L
        private const val POSITION_DIAGNOSTIC_INTERVAL_MS = 5_000L
    }
}
