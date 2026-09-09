/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import android.media.AudioDeviceInfo
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap

internal const val APPLE_AUDIO_VARIANT_DOLBY_ATMOS = 4
internal const val APPLE_VOLUME_BALANCE_TARGET_LUFS = -16f
internal const val APPLE_ATMOS_FALLBACK_GAIN_DB = 6f
internal const val APPLE_ATMOS_UNKNOWN_PEAK_MAX_GAIN_DB = 4f
internal const val APPLE_ATMOS_MAX_INPUT_GAIN_DB = 10f
internal const val APPLE_ATMOS_LIMITER_THRESHOLD_DBFS = -1f
internal const val APPLE_ATMOS_HOT_UPGRADE_RAMP_DURATION_MS = 240L
internal const val APPLE_ATMOS_HOT_UPGRADE_RAMP_STEPS = 12

internal fun atmosphereDiagnosticElapsedRealtime(): Long =
    runCatching(SystemClock::elapsedRealtime).getOrDefault(-1L)

internal enum class AppleAtmosOutputRoute {
    BUILT_IN_SPEAKER,
    NON_SPEAKER,
    UNKNOWN,
}

internal fun resolveAppleAtmosOutputRoute(deviceType: Int?): AppleAtmosOutputRoute = when (deviceType) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> AppleAtmosOutputRoute.BUILT_IN_SPEAKER
    null,
    AudioDeviceInfo.TYPE_UNKNOWN -> AppleAtmosOutputRoute.UNKNOWN
    else -> AppleAtmosOutputRoute.NON_SPEAKER
}

internal data class AppleAtmosGainDecision(
    val inputGainDb: Float,
    val metadataLoudness: Float?,
    val requestedInputGainDb: Float,
    val peakDbfs: Float?,
    val peakSource: String,
    val peakHeadroomDb: Float?,
    val peakLimited: Boolean,
    val fallback: Boolean,
)

internal fun resolveAppleAtmosGain(
    loudness: Float,
    peakMetadata: AppleAtmosPeakMetadata? = null,
): AppleAtmosGainDecision {
    val metadataLoudness = loudness.takeIf(Float::isFinite)
    val requestedGain = (metadataLoudness?.let {
        APPLE_VOLUME_BALANCE_TARGET_LUFS - it
    } ?: APPLE_ATMOS_FALLBACK_GAIN_DB).coerceAtLeast(0f)
    val matchingPeakMetadata = peakMetadata?.takeIf { metadata ->
        metadataLoudness == null ||
            kotlin.math.abs(metadata.loudness - metadataLoudness) <= 0.05f
    }
    val peakDbfs = matchingPeakMetadata?.truePeakDbfs?.takeIf(Float::isFinite)
        ?: matchingPeakMetadata?.samplePeakDbfs?.takeIf(Float::isFinite)
    val peakSource = when {
        matchingPeakMetadata?.truePeakDbfs?.isFinite() == true -> "true_peak"
        matchingPeakMetadata?.samplePeakDbfs?.isFinite() == true -> "sample_peak"
        else -> "missing"
    }
    val peakHeadroomDb = peakDbfs?.let { peak ->
        (APPLE_ATMOS_LIMITER_THRESHOLD_DBFS - peak).coerceAtLeast(0f)
    }
    val peakBoundedGain = peakHeadroomDb?.let { headroom ->
        minOf(requestedGain, headroom)
    } ?: minOf(requestedGain, APPLE_ATMOS_UNKNOWN_PEAK_MAX_GAIN_DB)
    val inputGain = peakBoundedGain.coerceIn(0f, APPLE_ATMOS_MAX_INPUT_GAIN_DB)
    return AppleAtmosGainDecision(
        inputGainDb = inputGain,
        metadataLoudness = metadataLoudness,
        requestedInputGainDb = requestedGain,
        peakDbfs = peakDbfs,
        peakSource = peakSource,
        peakHeadroomDb = peakHeadroomDb,
        peakLimited = inputGain + 0.001f < requestedGain,
        fallback = metadataLoudness == null,
    )
}

internal class AppleAtmosVolumeProcessor(
    internal val preferenceEnabled: () -> Boolean,
    internal val effectFactory: (
        audioSessionId: Int,
        channelCount: Int,
        decision: AppleAtmosGainDecision,
        initialInputGainDb: Float,
    ) -> AppleSessionDynamicsEffect = { sessionId, _, _, initialInputGainDb ->
        AndroidAppleSessionLoudnessEffect(sessionId, initialInputGainDb)
    },
    internal val scheduleDelayed: (delayMs: Long, action: () -> Unit) -> Unit =
        { delayMs, action -> Handler(Looper.getMainLooper()).postDelayed(action, delayMs) },
) {
    internal data class PlayerState(
        var audioSessionId: Int = 0,
        var audioVariant: Int = -1,
        var periodId: Long = 0L,
        var loudness: Float = Float.NaN,
        var peakMetadata: AppleAtmosPeakMetadata? = null,
        var channelCount: Int = 2,
        var sessionGeneration: Long = 0L,
        var sessionGenerationAtLastVariant: Long = 0L,
        var pendingHotUpgradeSessionId: Int = 0,
        var pendingHotUpgradeTrackIds: Set<Int> = emptySet(),
        var rampHotUpgradeOnNextApply: Boolean = false,
        var pcmFrontPowerSum: Double = 0.0,
        var pcmFrontFrameCount: Long = 0L,
        var pcmLearnedPeakDbfs: Float = Float.NaN,
    ) {
        fun resetPcmStats() {
            pcmFrontPowerSum = 0.0
            pcmFrontFrameCount = 0L
            pcmLearnedPeakDbfs = Float.NaN
        }

        fun integratedFrontPowerDb(): Float? =
            if (pcmFrontFrameCount > 0L) {
                (10.0 * Math.log10(pcmFrontPowerSum / pcmFrontFrameCount)).toFloat()
            } else {
                null
            }
    }

    internal val playerStates = WeakIdentityMap<Any, PlayerState>()
    internal val activeAudioTrackIdsBySession = mutableMapOf<Int, MutableSet<Int>>()
    internal val audioTrackRoutes = mutableMapOf<Int, AppleAtmosOutputRoute>()
    internal var activePlayer: Any? = null
    internal var activeEffect: AppleSessionDynamicsEffect? = null
    internal var activeEffectSessionId = 0
    internal var activeEffectGeneration = 0L
    internal var activeEffectInputGainDb = 0f
    internal var failedSessionId = 0
    internal var nonAtmosReferenceDbfs: Float? = null
    internal var pcmGeneration = 0L
    internal var pcmTargetGainDb: Float? = null

    @Synchronized
    fun onPlayerActivated(player: Any) {
        if (activePlayer !== player) {
            invalidatePcm()
            releaseActiveEffect("active_player_changed")
            activePlayer = player
            playerStates[player]?.resetPcmStats()
            failedSessionId = 0
        }
        val state = playerState(player)
        logProcessorState("player_activated", "received", player, state)
        reconcileActivePlayer("player_activated")
    }

    @Synchronized
    fun onAudioSessionId(player: Any, audioSessionId: Int) {
        val state = playerState(player)
        val previousSessionId = state.audioSessionId
        val sessionChanged = previousSessionId != audioSessionId
        state.sessionGeneration += 1L
        if (sessionChanged) {
            if (state.pendingHotUpgradeSessionId != 0 &&
                state.pendingHotUpgradeSessionId != audioSessionId
            ) {
                clearPendingHotUpgrade(state)
            }
            state.audioSessionId = audioSessionId
            state.resetPcmStats()
            if (activePlayer === player) {
                invalidatePcm()
                releaseActiveEffect("player_session_changed")
                failedSessionId = 0
            }
        }
        logProcessorState(
            event = "player_session",
            action = if (sessionChanged) "updated" else "repeated",
            player = player,
            state = state,
            extra = "previousSessionId=$previousSessionId,callbackSessionId=$audioSessionId," +
                "sessionGeneration=${state.sessionGeneration}",
        )
        if (activePlayer === player) reconcileActivePlayer("player_session")
    }

    @Synchronized
    fun onAudioVariantChanged(
        player: Any,
        audioVariant: Int,
        periodId: Long,
        loudness: Float,
        channelCount: Int,
        peakMetadata: AppleAtmosPeakMetadata? = null,
    ) {
        val state = playerState(player)
        val previousVariant = state.audioVariant
        val previousPeriodId = state.periodId
        val previousSessionId = state.audioSessionId
        val effectivePeriodId = periodId.takeIf { it != 0L } ?: previousPeriodId
        val periodChanged = previousPeriodId != effectivePeriodId
        val freshSessionSincePreviousVariant =
            state.sessionGeneration > state.sessionGenerationAtLastVariant
        val samePeriodAtmosUpgrade = !periodChanged &&
            previousVariant != APPLE_AUDIO_VARIANT_DOLBY_ATMOS &&
            audioVariant == APPLE_AUDIO_VARIANT_DOLBY_ATMOS
        state.audioVariant = audioVariant
        state.periodId = effectivePeriodId
        state.loudness = loudness
        state.peakMetadata = peakMetadata
        state.channelCount = channelCount.coerceAtLeast(1)
        state.sessionGenerationAtLastVariant = state.sessionGeneration
        if (periodChanged || audioVariant != APPLE_AUDIO_VARIANT_DOLBY_ATMOS) {
            state.resetPcmStats()
        }
        if (activePlayer === player) {
            failedSessionId = 0
            if (periodChanged || previousVariant != audioVariant) {
                invalidatePcm()
                // Numeric Session IDs can be reused. Never leave the old Period effect/ramp alive.
                if (periodChanged) releaseActiveEffect("player_period_changed")
            }
        }

        // A cross-period transition may keep the numeric Session ID, but it must have produced a
        // fresh callback after the preceding variant before that Session can be associated with the
        // new Period. This preserves stale-session isolation while allowing callback-before-format.
        val mustWaitForFreshSession = periodChanged && previousVariant != -1 &&
            !freshSessionSincePreviousVariant
        if (mustWaitForFreshSession) {
            state.audioSessionId = 0
            clearPendingHotUpgrade(state)
        }

        if (audioVariant != APPLE_AUDIO_VARIANT_DOLBY_ATMOS) {
            clearPendingHotUpgrade(state)
            if (activePlayer === player) releaseActiveEffect("non_atmos")
            logProcessorState(
                event = "player_variant",
                action = if (mustWaitForFreshSession) {
                    "non_atmos_wait_fresh_session_cross_period"
                } else {
                    "non_atmos_session_retained"
                },
                player = player,
                state = state,
                extra = "rawPeriodId=$periodId,previousVariant=$previousVariant," +
                    "previousPeriodId=$previousPeriodId,previousSessionId=$previousSessionId," +
                    "periodChanged=$periodChanged," +
                    "freshSessionSincePreviousVariant=$freshSessionSincePreviousVariant," +
                    "sessionGeneration=${state.sessionGeneration}",
            )
            return
        }

        if (mustWaitForFreshSession) {
            logProcessorState(
                event = "player_variant",
                action = "wait_fresh_session_cross_period",
                player = player,
                state = state,
                extra = "rawPeriodId=$periodId,previousVariant=$previousVariant," +
                    "previousPeriodId=$previousPeriodId,discardedSessionId=$previousSessionId," +
                    "periodChanged=true,freshSessionSincePreviousVariant=false," +
                    "sessionGeneration=${state.sessionGeneration}",
            )
            return
        }

        if (samePeriodAtmosUpgrade && state.audioSessionId > 0) {
            val oldTrackIds = activeAudioTrackIdsBySession[state.audioSessionId]
                ?.toSet()
                .orEmpty()
            state.pendingHotUpgradeSessionId = state.audioSessionId
            state.pendingHotUpgradeTrackIds = oldTrackIds
            state.rampHotUpgradeOnNextApply = true
            if (activePlayer === player) releaseActiveEffect("same_period_hot_upgrade_pending")
            logProcessorState(
                event = "player_variant",
                action = if (oldTrackIds.isEmpty()) {
                    "same_period_hot_upgrade_no_old_track"
                } else {
                    "defer_same_period_hot_upgrade_until_old_track_stops"
                },
                player = player,
                state = state,
                extra = "rawPeriodId=$periodId,previousVariant=$previousVariant," +
                    "previousPeriodId=$previousPeriodId,previousSessionId=$previousSessionId," +
                    "periodChanged=false,samePeriodAtmosUpgrade=true," +
                    "oldTrackIds=${oldTrackIds.sorted()}," +
                    "sessionGeneration=${state.sessionGeneration}",
            )
            if (oldTrackIds.isNotEmpty()) return
        } else {
            clearPendingHotUpgrade(state)
        }

        logProcessorState(
            event = "player_variant",
            action = if (samePeriodAtmosUpgrade) {
                "same_period_hot_upgrade_ready"
            } else {
                "atmos_received"
            },
            player = player,
            state = state,
            extra = "rawPeriodId=$periodId,previousVariant=$previousVariant," +
                "previousPeriodId=$previousPeriodId,previousSessionId=$previousSessionId," +
                "periodChanged=$periodChanged,samePeriodAtmosUpgrade=$samePeriodAtmosUpgrade," +
                "freshSessionSincePreviousVariant=$freshSessionSincePreviousVariant," +
                "sessionGeneration=${state.sessionGeneration}",
        )
        if (activePlayer === player) reconcileActivePlayer("player_variant")
    }

    @Synchronized
    fun onAudioTrackPlayed(
        audioSessionId: Int,
        trackIdentity: Int,
        routedDeviceType: Int?,
    ) {
        if (audioSessionId <= 0) return
        val route = resolveAppleAtmosOutputRoute(routedDeviceType)
        val added = activeAudioTrackIdsBySession.getOrPut(audioSessionId, ::mutableSetOf).add(trackIdentity)
        val routeChanged = audioTrackRoutes[trackIdentity] != route
        audioTrackRoutes[trackIdentity] = route
        if ((added || routeChanged) && activePlayer?.let(playerStates::get)?.audioSessionId == audioSessionId) {
            invalidatePcm()
        }
        val player = activePlayer
        val state = player?.let(playerStates::get)
        if (player != null && state != null) {
            logProcessorState(
                event = "audio_track_play",
                action = "tracked",
                player = player,
                state = state,
                extra = "track=$trackIdentity,trackSessionId=$audioSessionId," +
                    "route=$route,routedDeviceType=$routedDeviceType," +
                    "activeTracks=${activeAudioTrackIdsBySession[audioSessionId]?.sorted()}",
            )
            if (state.audioSessionId == audioSessionId) {
                reconcileActivePlayer("audio_track_play_route")
            }
        }
    }

    @Synchronized
    fun onAudioTrackRouteChanged(
        audioSessionId: Int,
        trackIdentity: Int,
        routedDeviceType: Int?,
    ) {
        val route = resolveAppleAtmosOutputRoute(routedDeviceType)
        val isTracked = trackIdentity in activeAudioTrackIdsBySession[audioSessionId].orEmpty()
        if (isTracked) {
            if (audioTrackRoutes[trackIdentity] != route &&
                activePlayer?.let(playerStates::get)?.audioSessionId == audioSessionId
            ) invalidatePcm()
            audioTrackRoutes[trackIdentity] = route
        }
        val player = activePlayer ?: return
        val state = playerStates[player] ?: return
        logProcessorState(
            event = "audio_route_changed",
            action = if (isTracked) "tracked_route_updated" else "ignored_inactive_track",
            player = player,
            state = state,
            extra = "track=$trackIdentity,trackSessionId=$audioSessionId," +
                "route=$route,routedDeviceType=$routedDeviceType",
        )
        if (isTracked && state.audioSessionId == audioSessionId) {
            reconcileActivePlayer("audio_route_changed")
        }
    }

    @Synchronized
    fun onAudioTrackStopped(audioSessionId: Int, trackIdentity: Int, source: String) {
        if (trackIdentity in activeAudioTrackIdsBySession[audioSessionId].orEmpty() &&
            activePlayer?.let(playerStates::get)?.audioSessionId == audioSessionId
        ) invalidatePcm()
        audioTrackRoutes.remove(trackIdentity)
        activeAudioTrackIdsBySession[audioSessionId]?.let { tracks ->
            tracks.remove(trackIdentity)
            if (tracks.isEmpty()) activeAudioTrackIdsBySession.remove(audioSessionId)
        }
        val player = activePlayer ?: return
        val state = playerStates[player] ?: return
        if (state.pendingHotUpgradeSessionId != audioSessionId ||
            trackIdentity !in state.pendingHotUpgradeTrackIds
        ) {
            logProcessorState(
                event = "audio_track_end",
                action = "not_pending_hot_upgrade_track",
                player = player,
                state = state,
                extra = "source=$source,track=$trackIdentity," +
                    "trackSessionId=$audioSessionId",
            )
            if (state.audioSessionId == audioSessionId) {
                reconcileActivePlayer("audio_track_end_route")
            }
            return
        }

        state.pendingHotUpgradeTrackIds = state.pendingHotUpgradeTrackIds - trackIdentity
        logProcessorState(
            event = "audio_track_end",
            action = if (state.pendingHotUpgradeTrackIds.isEmpty()) {
                "old_tracks_drained_apply_hot_upgrade"
            } else {
                "waiting_remaining_old_tracks"
            },
            player = player,
            state = state,
            extra = "source=$source,track=$trackIdentity,trackSessionId=$audioSessionId," +
                "remainingOldTrackIds=${state.pendingHotUpgradeTrackIds.sorted()}",
        )
        if (state.pendingHotUpgradeTrackIds.isEmpty()) {
            reconcileActivePlayer("old_audio_track_ended")
        }
    }

    @Synchronized
    fun onPreferenceChanged() {
        invalidatePcm()
        if (!runCatching(preferenceEnabled).getOrDefault(false)) nonAtmosReferenceDbfs = null
        failedSessionId = 0
        activePlayer?.let { player ->
            playerStates[player]?.let { state ->
                logProcessorState("preference_changed", "received", player, state)
            }
        }
        reconcileActivePlayer("preference_changed")
    }

    internal fun invalidatePcm(resetStats: Boolean = true) {
        pcmGeneration++
        if (resetStats) {
            activePlayer?.let(playerStates::get)?.resetPcmStats()
            pcmTargetGainDb = null
            activeEffectGeneration++
        }
    }

}
