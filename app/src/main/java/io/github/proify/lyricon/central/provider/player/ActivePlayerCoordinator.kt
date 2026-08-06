/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

import android.os.SystemClock
import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.central.Constants
import io.github.proify.lyricon.central.provider.player.PlayerRecorder.LyricType.NONE
import io.github.proify.lyricon.central.provider.player.PlayerRecorder.LyricType.SONG
import io.github.proify.lyricon.central.provider.player.PlayerRecorder.LyricType.TEXT
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.ProviderInfo
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal class ActivePlayerCoordinator : PlayerListener {

    private val debug = Constants.isDebug()
    private val lock = ReentrantReadWriteLock()
    private val listeners = CopyOnWriteArraySet<ActivePlayerListener>()

    @Volatile
    private var activeRecorder: PlayerRecorder? = null

    private val activeInfo: ProviderInfo? get() = activeRecorder?.providerInfo

    @Volatile
    private var activeIsPlaying: Boolean = false
    private var lastPositionDiagnosticAtMs = 0L

    fun addListener(listener: ActivePlayerListener) {
        if (listeners.add(listener)) {
            syncLatestState(listener)
        }
    }

    fun removeListener(listener: ActivePlayerListener) = listeners.remove(listener)

    fun syncLatestState(listener: ActivePlayerListener) {
        val snapshot = lock.read {
            activeRecorder?.snapshot(activeIsPlaying)
        } ?: return

        dispatchSnapshot(snapshot, listener)
    }

    fun notifyProviderInvalid(provider: ProviderInfo) {
        val shouldNotify = lock.write {
            if (activeInfo == provider) {
                activeRecorder = null
                activeIsPlaying = false
                true
            } else {
                false
            }
        }

        if (shouldNotify) {
            broadcast {
                it.onActiveProviderChanged(null)
                it.onPlaybackStateChanged(false)
            }
        }
    }

    override fun onSongChanged(recorder: PlayerRecorder, song: Song?) {
        if (debug) Log.d(TAG, "onSongChanged: $song")
        dispatchIfActive(recorder, allowDuplicateIfSwitching = false) {
            it.onSongChanged(song)
        }
    }

    override fun onPlaybackStateChanged(recorder: PlayerRecorder, isPlaying: Boolean) {
        if (debug) Log.d(TAG, "onPlaybackStateChanged: $isPlaying")
        dispatchIfActive(recorder) {
            it.onPlaybackStateChanged(isPlaying)
        }
    }

    override fun onPositionChanged(recorder: PlayerRecorder, position: Long) {
        dispatchIfActive(recorder, diagnosticPosition = position) {
            it.onPositionChanged(position)
        }
    }

    override fun onSeekTo(recorder: PlayerRecorder, position: Long) {
        dispatchIfActive(recorder) {
            it.onSeekTo(position)
        }
    }

    override fun onSendText(recorder: PlayerRecorder, text: String?) {
        dispatchIfActive(recorder, allowDuplicateIfSwitching = false) {
            it.onSendText(text)
        }
    }

    override fun onDisplayTranslationChanged(
        recorder: PlayerRecorder,
        isDisplayTranslation: Boolean
    ) {
        dispatchIfActive(recorder, allowDuplicateIfSwitching = false) {
            it.onDisplayTranslationChanged(isDisplayTranslation)
        }
    }

    override fun onDisplayRomaChanged(recorder: PlayerRecorder, displayRoma: Boolean) {
        dispatchIfActive(recorder, allowDuplicateIfSwitching = false) {
            it.onDisplayRomaChanged(displayRoma)
        }
    }

    fun syncNewProviderState(recorder: PlayerRecorder, listener: ActivePlayerListener) {
        val snapshot = lock.read {
            recorder.snapshot(activeIsPlaying)
        }
        dispatchSnapshot(snapshot, listener)
    }

    private fun dispatchSnapshot(snapshot: ActivePlayerSnapshot, listener: ActivePlayerListener) {
        listener.onActiveProviderChanged(snapshot.providerInfo)
        listener.onPlaybackStateChanged(snapshot.isPlaying)

        when (snapshot.lyricType) {
            SONG -> listener.onSongChanged(snapshot.song)
            TEXT -> listener.onSendText(snapshot.text)
            NONE -> Unit
        }

        listener.onDisplayTranslationChanged(snapshot.isDisplayTranslation)
        listener.onDisplayRomaChanged(snapshot.isDisplayRoma)
        listener.onPositionChanged(snapshot.position)
    }

    private inline fun dispatchIfActive(
        recorder: PlayerRecorder,
        allowDuplicateIfSwitching: Boolean = true,
        diagnosticPosition: Long? = null,
        crossinline notifier: (ActivePlayerListener) -> Unit
    ) {
        val recorderInfo = recorder.providerInfo
        val recorderPlaying = recorder.isPlaying
        var isSwitched = false
        var shouldBroadcastOriginal = false
        var decision = "unknown"
        var previousInfo: ProviderInfo? = null
        var resultingInfo: ProviderInfo? = null

        lock.write {
            val currentInfo = activeInfo
            previousInfo = currentInfo
            if (currentInfo === recorderInfo) {
                activeIsPlaying = recorderPlaying
                shouldBroadcastOriginal = true
                decision = "accepted_active"
            } else {
                val samePlayer = currentInfo?.playerPackageName == recorderInfo.playerPackageName
                val priorityComparison = if (currentInfo == null || !samePlayer) {
                    0
                } else {
                    ProviderSourcePriorityResolver.resolve(recorderInfo).rank.compareTo(
                        ProviderSourcePriorityResolver.resolve(currentInfo).rank
                    )
                }
                val canSwitch = when {
                    currentInfo == null -> true
                    samePlayer && priorityComparison > 0 -> true
                    samePlayer && priorityComparison < 0 -> false
                    else -> !activeIsPlaying && recorderPlaying
                }
                if (canSwitch) {
                    activeRecorder = recorder
                    activeIsPlaying = recorderPlaying
                    isSwitched = true
                    shouldBroadcastOriginal = allowDuplicateIfSwitching
                    decision = when {
                        currentInfo == null -> "switched_no_active"
                        samePlayer && priorityComparison > 0 -> "switched_higher_priority"
                        else -> "switched_playback_state"
                    }
                } else {
                    decision = when {
                        samePlayer && priorityComparison < 0 -> "dropped_lower_priority"
                        activeIsPlaying -> "dropped_active_still_playing"
                        !recorderPlaying -> "dropped_candidate_not_playing"
                        else -> "dropped_switch_rejected"
                    }
                }
            }
            resultingInfo = activeInfo
        }

        diagnosticPosition?.let { position ->
            logPositionDiagnostic(
                recorderInfo = recorderInfo,
                previousInfo = previousInfo,
                resultingInfo = resultingInfo,
                recorderPlaying = recorderPlaying,
                position = position,
                decision = decision,
                isSwitched = isSwitched,
                broadcastOriginal = shouldBroadcastOriginal,
            )
        }

        if (isSwitched) {
            broadcast { syncNewProviderState(recorder, it) }
        }

        if (shouldBroadcastOriginal) {
            broadcast(notifier)
        }
    }

    private fun logPositionDiagnostic(
        recorderInfo: ProviderInfo,
        previousInfo: ProviderInfo?,
        resultingInfo: ProviderInfo?,
        recorderPlaying: Boolean,
        position: Long,
        decision: String,
        isSwitched: Boolean,
        broadcastOriginal: Boolean,
    ) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPositionDiagnosticAtMs < POSITION_DIAGNOSTIC_INTERVAL_MS) return
        lastPositionDiagnosticAtMs = now
        Log.i(
            TAG,
            "[LyricPositionDiag] stage=central_route, decision=$decision, " +
                "incoming=${recorderInfo.providerPackageName}/${recorderInfo.playerPackageName}, " +
                "previous=${previousInfo?.providerPackageName}/${previousInfo?.playerPackageName}, " +
                "result=${resultingInfo?.providerPackageName}/${resultingInfo?.playerPackageName}, " +
                "position=$position, incomingPlaying=$recorderPlaying, " +
                "activePlaying=$activeIsPlaying, switched=$isSwitched, " +
                "broadcast=$broadcastOriginal, listeners=${listeners.size}"
        )
    }

    private inline fun broadcast(crossinline notifier: (ActivePlayerListener) -> Unit) {
        for (listener in listeners) {
            try {
                notifier(listener)
            } catch (e: Exception) {
                if (debug) Log.e(TAG, "Dispatch failed for listener: ${listener.javaClass.name}", e)
            }
        }
    }

    private fun PlayerRecorder.snapshot(isPlaying: Boolean) = ActivePlayerSnapshot(
        providerInfo = providerInfo,
        isPlaying = isPlaying,
        song = song,
        text = text,
        lyricType = lyricType,
        isDisplayTranslation = isDisplayTranslation,
        isDisplayRoma = isDisplayRoma,
        position = position
    )

    private data class ActivePlayerSnapshot(
        val providerInfo: ProviderInfo,
        val isPlaying: Boolean,
        val song: Song?,
        val text: String?,
        val lyricType: PlayerRecorder.LyricType,
        val isDisplayTranslation: Boolean,
        val isDisplayRoma: Boolean,
        val position: Long
    )

    private companion object {
        private const val TAG = "ActivePlayerCoordinator"
        private const val POSITION_DIAGNOSTIC_INTERVAL_MS = 5_000L
    }
}
