/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

import android.os.SystemClock
import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.proify.lyricon.central.Constants
import io.github.proify.lyricon.central.provider.player.PlayerRecorder.LyricType.NONE
import io.github.proify.lyricon.central.provider.player.PlayerRecorder.LyricType.SONG
import io.github.proify.lyricon.central.provider.player.PlayerRecorder.LyricType.TEXT
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.ProviderInfo
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal class ActivePlayerCoordinator(
    private val officialProviderPreference: (String) -> Boolean? = { null },
    private val decisionLogger: (String) -> Unit = { message -> Log.i(TAG, message) },
    activeAudioPlaybackMonitor: ActiveAudioPlaybackMonitor = ActiveAudioPlaybackMonitor { null },
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val audioConflictConfirmationMs: Long = AUDIO_CONFLICT_CONFIRMATION_MS,
) : PlayerListener {

    private val debug = Constants.isDebug()
    private val lock = ReentrantReadWriteLock()
    private val listeners = CopyOnWriteArraySet<ActivePlayerListener>()
    private val loggedSourceDecisions = ConcurrentHashMap<String, String>()
    private val audioSuppressedPlayers = HashSet<String>()
    private val audioConflictFirstSeenAt = HashMap<String, Long>()

    @Volatile
    private var activeRecorder: PlayerRecorder? = null

    private val activeInfo: ProviderInfo? get() = activeRecorder?.providerInfo

    @Volatile
    private var activeIsPlaying: Boolean = false
    @Volatile
    private var audioPlaybackMonitor = activeAudioPlaybackMonitor
    private var lastPositionDiagnosticAtMs = 0L

    fun setActiveAudioPlaybackMonitor(monitor: ActiveAudioPlaybackMonitor) {
        audioPlaybackMonitor = monitor
    }

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
                audioSuppressedPlayers.remove(provider.playerPackageName)
                audioConflictFirstSeenAt.remove(provider.playerPackageName)
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

    /** Reconciles an already active source immediately after official Pack preferences change. */
    fun onOfficialProviderPreferencesChanged(playerPackageNames: Set<String>) {
        if (playerPackageNames.isEmpty()) return
        loggedSourceDecisions.keys.removeAll { key ->
            playerPackageNames.any { playerPackageName -> key.endsWith("/$playerPackageName") }
        }

        var removedInfo: ProviderInfo? = null
        lock.write {
            val currentInfo = activeInfo
            if (currentInfo != null &&
                currentInfo.playerPackageName in playerPackageNames &&
                !isProviderAllowed(currentInfo)
            ) {
                removedInfo = currentInfo
                audioSuppressedPlayers.remove(currentInfo.playerPackageName)
                audioConflictFirstSeenAt.remove(currentInfo.playerPackageName)
                activeRecorder = null
                activeIsPlaying = false
            }
        }

        removedInfo?.let { providerInfo ->
            decisionLogger(
                "官方 Provider 配置变更后清除不再允许的来源: " +
                    "provider=${providerInfo.providerPackageName}, " +
                    "player=${providerInfo.playerPackageName}, " +
                    "officialPreferred=${officialProviderPreference(providerInfo.playerPackageName)}",
            )
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
        dispatchIfActive(recorder, reportsPlaybackState = true) {
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
        reportsPlaybackState: Boolean = false,
        crossinline notifier: (ActivePlayerListener) -> Unit
    ) {
        val recorderInfo = recorder.providerInfo
        val reportedPlaying = recorder.isPlaying
        val audioConflict = audioPlaybackMonitor.conflictFor(
            recorderInfo.playerPackageName,
        )
        val eventTimeMs = elapsedRealtime()
        var recorderPlaying = reportedPlaying
        var audioSuppressed = false
        var audioConflictConfirmed = false
        var isSwitched = false
        var shouldBroadcastOriginal = false
        var effectivePlaybackChanged = false
        var decision = "unknown"
        var previousInfo: ProviderInfo? = null
        var resultingInfo: ProviderInfo? = null
        var invalidActiveCleared = false

        lock.write {
            when (audioConflict) {
                true -> {
                    val firstSeenAt = audioConflictFirstSeenAt.getOrPut(
                        recorderInfo.playerPackageName,
                    ) { eventTimeMs }
                    audioConflictConfirmed =
                        eventTimeMs - firstSeenAt >= audioConflictConfirmationMs
                    if (audioConflictConfirmed) {
                        audioSuppressedPlayers += recorderInfo.playerPackageName
                    }
                }
                false -> {
                    audioConflictFirstSeenAt.remove(recorderInfo.playerPackageName)
                    audioSuppressedPlayers.remove(recorderInfo.playerPackageName)
                }
                null -> audioConflictFirstSeenAt.remove(recorderInfo.playerPackageName)
            }
            audioSuppressed = recorderInfo.playerPackageName in audioSuppressedPlayers
            recorderPlaying = reportedPlaying && !audioSuppressed

            var currentInfo = activeInfo
            previousInfo = currentInfo
            if (currentInfo != null && !isProviderAllowed(currentInfo)) {
                activeRecorder = null
                activeIsPlaying = false
                invalidActiveCleared = true
                currentInfo = null
            }

            if (!isProviderAllowed(recorderInfo)) {
                decision = when (ProviderSourcePriorityResolver.resolve(recorderInfo)) {
                    ProviderSourcePriority.OFFICIAL_PLUGIN -> "dropped_official_pack_disabled"
                    ProviderSourcePriority.LEGACY_APK -> "dropped_legacy_official_pack_preferred"
                    ProviderSourcePriority.BUILT_IN -> "dropped_source_not_allowed"
                }
            } else if (currentInfo === recorderInfo) {
                effectivePlaybackChanged = activeIsPlaying != recorderPlaying
                activeIsPlaying = recorderPlaying
                shouldBroadcastOriginal = !audioSuppressed
                decision = if (audioSuppressed) {
                    "suppressed_active_audio_output"
                } else {
                    "accepted_active"
                }
            } else if (audioSuppressed) {
                decision = "dropped_audio_output"
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

        if (decision.startsWith("dropped_official_pack_") ||
            decision.startsWith("dropped_legacy_official_pack_")
        ) {
            logSourceDecisionOnce(recorderInfo, decision)
        }

        diagnosticPosition?.let { position ->
            logPositionDiagnostic(
                recorderInfo = recorderInfo,
                previousInfo = previousInfo,
                resultingInfo = resultingInfo,
                recorderPlaying = recorderPlaying,
                reportedPlaying = reportedPlaying,
                audioConflict = audioConflict,
                audioConflictConfirmed = audioConflictConfirmed,
                audioSuppressed = audioSuppressed,
                position = position,
                decision = decision,
                isSwitched = isSwitched,
                broadcastOriginal = shouldBroadcastOriginal,
            )
        }

        if (invalidActiveCleared && !isSwitched) {
            broadcast {
                it.onActiveProviderChanged(null)
                it.onPlaybackStateChanged(false)
            }
        }

        if (isSwitched) {
            broadcast { syncNewProviderState(recorder, it) }
        }

        if (!isSwitched && effectivePlaybackChanged &&
            (!reportsPlaybackState || audioSuppressed)
        ) {
            broadcast { it.onPlaybackStateChanged(recorderPlaying) }
        }

        if (shouldBroadcastOriginal) {
            broadcast(notifier)
        }
    }

    private fun isProviderAllowed(providerInfo: ProviderInfo): Boolean {
        val preference = officialProviderPreference(providerInfo.playerPackageName) ?: return true
        return when (ProviderSourcePriorityResolver.resolve(providerInfo)) {
            ProviderSourcePriority.BUILT_IN -> true
            ProviderSourcePriority.OFFICIAL_PLUGIN -> preference
            ProviderSourcePriority.LEGACY_APK -> !preference
        }
    }

    private fun logSourceDecisionOnce(providerInfo: ProviderInfo, decision: String) {
        val sourceKey = "${providerInfo.providerPackageName}/${providerInfo.playerPackageName}"
        if (loggedSourceDecisions.put(sourceKey, decision) == decision) return
        decisionLogger(
            "Provider 来源仲裁: decision=$decision, provider=${providerInfo.providerPackageName}, " +
                "player=${providerInfo.playerPackageName}, " +
                "officialPreferred=${officialProviderPreference(providerInfo.playerPackageName)}",
        )
    }

    private fun logPositionDiagnostic(
        recorderInfo: ProviderInfo,
        previousInfo: ProviderInfo?,
        resultingInfo: ProviderInfo?,
        recorderPlaying: Boolean,
        reportedPlaying: Boolean,
        audioConflict: Boolean?,
        audioConflictConfirmed: Boolean,
        audioSuppressed: Boolean,
        position: Long,
        decision: String,
        isSwitched: Boolean,
        broadcastOriginal: Boolean,
    ) {
        if (!BuildConfig.DEBUG) return
        val now = elapsedRealtime()
        if (now - lastPositionDiagnosticAtMs < POSITION_DIAGNOSTIC_INTERVAL_MS) return
        lastPositionDiagnosticAtMs = now
        HookLogger.i(
            TAG,
            "[LyricPositionDiag] stage=central_route, decision=$decision, " +
                "incoming=${recorderInfo.providerPackageName}/${recorderInfo.playerPackageName}, " +
                "previous=${previousInfo?.providerPackageName}/${previousInfo?.playerPackageName}, " +
                "result=${resultingInfo?.providerPackageName}/${resultingInfo?.playerPackageName}, " +
                "position=$position, reportedPlaying=$reportedPlaying, " +
                "effectivePlaying=$recorderPlaying, audioConflict=$audioConflict, " +
                "audioConflictConfirmed=$audioConflictConfirmed, " +
                "audioSuppressed=$audioSuppressed, " +
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
        private const val AUDIO_CONFLICT_CONFIRMATION_MS = 200L
    }
}
