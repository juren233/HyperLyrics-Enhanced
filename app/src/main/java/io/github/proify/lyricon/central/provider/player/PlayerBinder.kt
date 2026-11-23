/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

import com.juren233.hyperlyricsenhanced.common.coroutines.ModuleDispatchers
import android.media.session.PlaybackState
import android.os.SharedMemory
import android.os.SystemClock
import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.media.NextTrackMetadataCache
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.proify.lyricon.central.inflate
import io.github.proify.lyricon.central.ProviderFlowDiagnostics
import io.github.proify.lyricon.central.json
import io.github.proify.lyricon.central.util.ScreenStateMonitor
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.IRemotePlayer
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderInfo
import kotlinx.coroutines.CoroutineScope
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
    private val playerEvents: PlayerListener,
    /** The position memory a provider already writes to, inherited on hot reload. */
    adoptedMemory: SharedMemory? = null,
) : IRemotePlayer.Stub(), ScreenStateMonitor.ScreenStateListener {

    private val recorder = PlayerRecorder(info)
    private val scope = CoroutineScope(SupervisorJob() + ModuleDispatchers.Default)
    private val closed = AtomicBoolean(false)
    private val isState2Enabled = AtomicBoolean(false)
    private val closeMutex = Mutex()
    private val positionMemory = PositionMemoryBridge(info, adoptedMemory)
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
    private val positionDiagnosticSampler = if (BuildConfig.DEBUG) {
        PlaybackPositionDiagnosticSampler()
    } else null
    private var lastPositionPublishDiagnosticAtMs = 0L
    private val playbackStateSequence = AtomicLong(0L)
    private val closedInputDiagnostic = if (BuildConfig.DEBUG) AtomicBoolean(false) else null

    // The provider's last inputs. A provider sends them once and resends only after it reconnects,
    // which a hot reload never causes, so the next generation replays them (see [handoverState]).
    @Volatile private var lastInterval: Int? = null
    @Volatile private var lastDisplayTranslation: Boolean? = null
    @Volatile private var lastDisplayRoma: Boolean? = null
    @Volatile private var lastLyric: String = LYRIC_NONE
    @Volatile private var lastSongBytes: ByteArray? = null
    @Volatile private var lastText: String? = null
    @Volatile private var lastControlFrame: String? = null
    @Volatile private var lastPlayback: String = PLAYBACK_NONE
    @Volatile private var lastPlaying: Boolean = false
    @Volatile private var lastState: PlaybackState? = null

    init {
        ScreenStateMonitor.addListener(this)
        ProviderFlowDiagnostics.log("player_created", providerInfo) {
            "playerBinder=${ProviderFlowDiagnostics.id(this)}"
        }
    }

    /**
     * Stops this binder. With [keepMemory] the position memory stays open for the next generation,
     * which inherits it together with [handoverState].
     */
    fun close(keepMemory: Boolean = false) {
        if (!closed.compareAndSet(false, true)) return
        ProviderFlowDiagnostics.log("player_close", providerInfo) {
            "playerBinder=${ProviderFlowDiagnostics.id(this)}, stateSequence=${playbackStateSequence.get()}, " +
                "songId=${recorder.song?.id}, playing=${recorder.isPlaying}"
        }
        ScreenStateMonitor.removeListener(this)
        stopPositionUpdate()

        scope.launch {
            closeMutex.withLock {
                positionMemory.close(keepMemory)
                scope.cancel()
            }
        }
    }

    override fun onScreenOn() {
        if (recorder.isPlaying) startPositionUpdate()
    }

    override fun onScreenOff() {
        // Root normally estimates between authoritative ticks while the screen is off. Buffering
        // must keep publishing the frozen anchor, otherwise Root would continue that estimation
        // from the last PLAYING state and AOD lyrics would drift forward again.
        if (!isBuffering()) stopPositionUpdate()
    }

    override fun onScreenUnlocked() = Unit

    override fun setPositionUpdateInterval(interval: Int) {
        if (closed.get()) return
        lastInterval = interval

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
        ProviderFlowDiagnostics.log("player_song_input", providerInfo) {
            "playerBinder=${ProviderFlowDiagnostics.id(this)}, bytes=${bytes?.size}, closed=${closed.get()}"
        }
        if (closed.get()) return
        lastLyric = LYRIC_SONG
        lastSongBytes = bytes

        scope.launch {
            val song = bytes?.let {
                runCatching {
                    it.inflate()
                        .inputStream()
                        .buffered()
                        .use {
                            json.decodeFromStream(Song.serializer(), it)
                        }
                }.onFailure { error ->
                    ProviderFlowDiagnostics.log("player_song_decode_failed", providerInfo) {
                        "playerBinder=${ProviderFlowDiagnostics.id(this@PlayerBinder)}, error=${error.javaClass.name}"
                    }
                }.getOrNull()
            }

            val normalized = song?.normalize()
            ProviderFlowDiagnostics.log("player_song_decoded", providerInfo) {
                "playerBinder=${ProviderFlowDiagnostics.id(this@PlayerBinder)}, closed=${closed.get()}, " +
                    "songId=${normalized?.id}, lyrics=${normalized?.lyrics?.size ?: 0}"
            }
            recorder.song = normalized
            playerEvents.safeNotify { onSongChanged(recorder, normalized) }
            // Song decoding is asynchronous: lyrics may arrive after the playback anchor.
            // Refresh the subscriber's position after the song event resets its lyric cursor.
            publishPlaybackAnchor("song_changed")
        }
    }

    override fun setPlaybackState(isPlaying: Boolean) {
        if (closed.get()) {
            logClosedInput("playback_boolean")
            return
        }

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

        lastPlayback = PLAYBACK_MANUAL
        lastPlaying = isPlaying
        isState2Enabled.set(false)
        lastPlaybackState = null

        if (recorder.isPlaying != isPlaying) {
            recorder.isPlaying = isPlaying
            playerEvents.safeNotify { onPlaybackStateChanged(recorder, isPlaying) }
        }

        if (isPlaying) startPositionUpdate() else stopPositionUpdate()
    }

    override fun setPlaybackState2(state: PlaybackState?) {
        if (closed.get()) {
            logClosedInput("playback_state")
            return
        }

        val sequence = playbackStateSequence.incrementAndGet()
        lastPlayback = PLAYBACK_STATE
        lastState = state

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
                    "decision=${if (state.state == PlaybackState.STATE_BUFFERING) "freeze_buffering" else "accept_state2"}",
            )
        }

        val isPlaying = PlaybackStatePositionPolicy.keepsSessionActive(state.state)
        isState2Enabled.set(true)
        lastPlaybackState = state

        if (recorder.isPlaying != isPlaying) {
            recorder.isPlaying = isPlaying
            playerEvents.safeNotify { onPlaybackStateChanged(recorder, isPlaying) }
        }

        // Accepting state must also deliver its anchor. The screen-off ticker is deliberately
        // stopped, and PLAYING -> PLAYING does not emit a boolean playback event.
        publishPlaybackAnchor("playback_state")
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
            lastControlFrame = text
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

        lastLyric = LYRIC_TEXT
        lastText = text
        recorder.text = text
        playerEvents.safeNotify { onSendText(recorder, text) }
    }

    override fun setDisplayTranslation(isDisplayTranslation: Boolean) {
        if (closed.get()) return
        lastDisplayTranslation = isDisplayTranslation

        recorder.isDisplayTranslation = isDisplayTranslation
        playerEvents.safeNotify { onDisplayTranslationChanged(recorder, isDisplayTranslation) }
    }

    override fun setDisplayRoma(isDisplayRoma: Boolean) {
        if (closed.get()) return
        lastDisplayRoma = isDisplayRoma

        recorder.isDisplayRoma = isDisplayRoma
        playerEvents.safeNotify { onDisplayRomaChanged(recorder, isDisplayRoma) }
    }

    override fun getPositionMemory(): SharedMemory? = positionMemory.sharedMemory

    /** The provider's last inputs as JDK and framework values, for the next generation. */
    fun handoverState(): HashMap<String, Any?> = hashMapOf(
        KEY_INTERVAL to lastInterval,
        KEY_DISPLAY_TRANSLATION to lastDisplayTranslation,
        KEY_DISPLAY_ROMA to lastDisplayRoma,
        KEY_LYRIC to lastLyric,
        KEY_SONG to lastSongBytes,
        KEY_TEXT to lastText,
        KEY_CONTROL_FRAME to lastControlFrame,
        KEY_PLAYBACK to lastPlayback,
        KEY_PLAYING to lastPlaying,
        KEY_STATE to lastState,
    )

    /**
     * Feeds an inherited [handoverState] through this binder's own inputs, in the order the
     * provider library resends them after a reconnect (`CachedRemotePlayer.syncs`), so the
     * coordinator and subscribers see exactly what a reconnect would show them. A manual-mode
     * provider is resumed at the position it keeps writing to the inherited memory.
     */
    fun replay(state: Map<*, *>) {
        (state[KEY_INTERVAL] as? Int)?.let(::setPositionUpdateInterval)
        (state[KEY_DISPLAY_TRANSLATION] as? Boolean)?.let(::setDisplayTranslation)
        (state[KEY_DISPLAY_ROMA] as? Boolean)?.let(::setDisplayRoma)
        when (state[KEY_LYRIC]) {
            LYRIC_SONG -> setSong(state[KEY_SONG] as? ByteArray)
            LYRIC_TEXT -> sendText(state[KEY_TEXT] as? String)
        }
        (state[KEY_CONTROL_FRAME] as? String)?.let(::sendText)
        when (state[KEY_PLAYBACK]) {
            PLAYBACK_MANUAL -> {
                setPlaybackState(state[KEY_PLAYING] == true)
                seekTo(positionMemory.readPosition())
            }
            PLAYBACK_STATE -> setPlaybackState2(state[KEY_STATE] as? PlaybackState)
        }
    }

    private fun computeCurrentPosition(): Long {
        val state2Enabled = isState2Enabled.get()
        val position = if (!state2Enabled) {
            positionMemory.readPosition()
        } else {
            val state = lastPlaybackState
            if (state == null) {
                0L
            } else {
                PlaybackStatePositionPolicy.positionAt(
                    state = state.state,
                    basePosition = state.position,
                    lastUpdateTime = state.lastPositionUpdateTime,
                    playbackSpeed = state.playbackSpeed,
                    now = SystemClock.elapsedRealtime(),
                )
            }
        }
        val safePosition = position.coerceAtLeast(0L)
        logPositionDiagnostic(safePosition, state2Enabled)
        return safePosition
    }

    private fun logPositionDiagnostic(position: Long, state2Enabled: Boolean) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        val sequence = playbackStateSequence.get()
        val sampleReason = positionDiagnosticSampler?.sample(now, sequence, state2Enabled) ?: return
        val state = lastPlaybackState
        HookLogger.i(
            TAG,
            "[LyricPositionDiag] stage=central_position_read, " +
                "provider=${providerInfo.providerPackageName}, " +
                "player=${providerInfo.playerPackageName}, process=${providerInfo.processName}, " +
                "stateSequence=$sequence, sampleReason=$sampleReason, screen=${ScreenStateMonitor.state}, " +
                "source=${if (state2Enabled) "playback_state" else "shared_memory"}, " +
                "position=$position, playing=${recorder.isPlaying}, " +
                "state=${state?.state}, statePosition=${state?.position}, " +
                "stateUpdatedAt=${state?.lastPositionUpdateTime}, stateSpeed=${state?.playbackSpeed}, " +
                "anchorAgeMs=${state?.let { now - it.lastPositionUpdateTime }}, " +
                positionMemory.diagnosticSummary(),
        )
    }

    private fun startPositionUpdate() {
        if (closed.get()) {
            logPositionTickerDiagnostic("start_skipped", "closed")
            return
        }
        if (ScreenStateMonitor.state == ScreenStateMonitor.ScreenState.OFF) {
            logPositionTickerDiagnostic("start_skipped", "screen_off")
            return
        }
        positionTicker.start(positionUpdateInterval)
        logPositionTickerDiagnostic("start_requested", "playing")
    }

    private fun stopPositionUpdate() {
        positionTicker.stop()
        logPositionTickerDiagnostic("stop_requested", "lifecycle_or_playback")
    }

    private fun logPositionTickerDiagnostic(event: String, reason: String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            HookLogger.i(
                TAG,
                "[LyricPositionDiag] stage=central_position_ticker, event=$event, reason=$reason, " +
                    "stateSequence=${playbackStateSequence.get()}, player=${providerInfo.playerPackageName}, " +
                    "process=${providerInfo.processName}, screen=${ScreenStateMonitor.state}, " +
                    "playing=${recorder.isPlaying}, automatic=${isState2Enabled.get()}, interval=$positionUpdateInterval",
            )
        }
    }

    private fun isBuffering(): Boolean =
        isState2Enabled.get() && lastPlaybackState?.state == PlaybackState.STATE_BUFFERING

    private fun publishPlaybackAnchor(reason: String) {
        if (closed.get() || !isState2Enabled.get()) return
        val position = computeCurrentPosition()
        publishPosition(position)
        if (BuildConfig.DEBUG) {
            HookLogger.i(
                TAG,
                "[LyricPositionDiag] stage=central_anchor_publish, reason=$reason, " +
                    "stateSequence=${playbackStateSequence.get()}, player=${providerInfo.playerPackageName}, " +
                    "screen=${ScreenStateMonitor.state}, position=$position, " +
                    "state=${lastPlaybackState?.state}, songId=${recorder.song?.id}",
            )
        }
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
            ProviderFlowDiagnostics.log("player_dispatch_failed", providerInfo) {
                "playerBinder=${ProviderFlowDiagnostics.id(this@PlayerBinder)}, error=${e.javaClass.name}"
            }
            Log.e(TAG, "player event dispatch failed", e)
        }
    }

    private fun logClosedInput(input: String) {
        if (closedInputDiagnostic?.compareAndSet(false, true) != true) return
        ProviderFlowDiagnostics.log("closed_player_input", providerInfo) {
            "playerBinder=${ProviderFlowDiagnostics.id(this)}, input=$input"
        }
    }

    private companion object {
        private const val KEY_INTERVAL = "interval"
        private const val KEY_DISPLAY_TRANSLATION = "displayTranslation"
        private const val KEY_DISPLAY_ROMA = "displayRoma"
        private const val KEY_LYRIC = "lyric"
        private const val KEY_SONG = "song"
        private const val KEY_TEXT = "text"
        private const val KEY_CONTROL_FRAME = "controlFrame"
        private const val KEY_PLAYBACK = "playback"
        private const val KEY_PLAYING = "playing"
        private const val KEY_STATE = "state"
        private const val LYRIC_NONE = "none"
        private const val LYRIC_SONG = "song"
        private const val LYRIC_TEXT = "text"
        private const val PLAYBACK_NONE = "none"
        private const val PLAYBACK_MANUAL = "manual"
        private const val PLAYBACK_STATE = "state"
        private const val TAG = "PlayerBinder"
        private const val MIN_INTERVAL_MS = 16L
        private const val POSITION_DIAGNOSTIC_INTERVAL_MS = 5_000L
    }
}
