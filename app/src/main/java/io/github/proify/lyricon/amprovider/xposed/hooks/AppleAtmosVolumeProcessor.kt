/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import android.media.audiofx.DynamicsProcessing
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap

internal const val APPLE_AUDIO_VARIANT_DOLBY_ATMOS = 4
internal const val APPLE_VOLUME_BALANCE_TARGET_LUFS = -16f
internal const val APPLE_ATMOS_ROUTE_COMPENSATION_DB = 4.5f
internal const val APPLE_ATMOS_FALLBACK_GAIN_DB = 6.5f
internal const val APPLE_ATMOS_MAX_INPUT_GAIN_DB = 10f
internal const val APPLE_ATMOS_LIMITER_THRESHOLD_DBFS = -1f
internal const val APPLE_ATMOS_LIMITER_ATTACK_MS = 1f
internal const val APPLE_ATMOS_LIMITER_RELEASE_MS = 100f
internal const val APPLE_ATMOS_LIMITER_RATIO = 20f
internal const val APPLE_ATMOS_HOT_UPGRADE_RAMP_DURATION_MS = 240L
internal const val APPLE_ATMOS_HOT_UPGRADE_RAMP_STEPS = 12

private fun atmosphereDiagnosticElapsedRealtime(): Long =
    runCatching(SystemClock::elapsedRealtime).getOrDefault(-1L)

internal data class AppleAtmosGainDecision(
    val inputGainDb: Float,
    val metadataLoudness: Float?,
    val fallback: Boolean,
)

internal fun resolveAppleAtmosGain(loudness: Float): AppleAtmosGainDecision {
    val metadataLoudness = loudness.takeIf(Float::isFinite)
    val rawGain = metadataLoudness?.let {
        APPLE_VOLUME_BALANCE_TARGET_LUFS - it + APPLE_ATMOS_ROUTE_COMPENSATION_DB
    } ?: APPLE_ATMOS_FALLBACK_GAIN_DB
    return AppleAtmosGainDecision(
        inputGainDb = rawGain.coerceIn(0f, APPLE_ATMOS_MAX_INPUT_GAIN_DB),
        metadataLoudness = metadataLoudness,
        fallback = metadataLoudness == null,
    )
}

internal interface AppleSessionDynamicsEffect {
    fun setEnabled(enabled: Boolean)
    fun setInputGainDb(inputGainDb: Float)
    fun release()
    fun scheduleDiagnosticVerification(context: String) = Unit
}

private class AndroidAppleSessionDynamicsEffect(
    audioSessionId: Int,
    channelCount: Int,
    initialInputGainDb: Float,
) : AppleSessionDynamicsEffect {
    private val effect = DynamicsProcessing(
        0,
        audioSessionId,
        DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
            channelCount.coerceAtLeast(1),
            false,
            0,
            false,
            0,
            false,
            0,
            true,
        )
            .setPreferredFrameDuration(10f)
            .setInputGainAllChannelsTo(initialInputGainDb)
            .setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(
                    true,
                    true,
                    0,
                    APPLE_ATMOS_LIMITER_ATTACK_MS,
                    APPLE_ATMOS_LIMITER_RELEASE_MS,
                    APPLE_ATMOS_LIMITER_RATIO,
                    APPLE_ATMOS_LIMITER_THRESHOLD_DBFS,
                    0f,
                )
            )
            .build(),
    )

    override fun setEnabled(enabled: Boolean) {
        effect.enabled = enabled
    }

    override fun setInputGainDb(inputGainDb: Float) {
        effect.setInputGainAllChannelsTo(inputGainDb)
    }

    override fun release() {
        effect.release()
    }

    override fun scheduleDiagnosticVerification(context: String) {
        if (!BuildConfig.DEBUG) return
        val handler = Handler(Looper.getMainLooper())
        listOf(100L, 1_000L).forEach { delayMs ->
            handler.postDelayed({
                val state = runCatching {
                    val channels = effect.channelCount
                    val gains = (0 until channels).joinToString(",") { index ->
                        effect.getInputGainByChannelIndex(index).toString()
                    }
                    val limiter = effect.getLimiterByChannelIndex(0)
                    "enabled=${effect.enabled},configuredChannels=$channels," +
                        "inputGains=[$gains],limiterEnabled=${limiter.isEnabled}," +
                        "limiterInUse=${limiter.isInUse}," +
                        "limiterThresholdDb=${limiter.threshold}," +
                        "limiterRatio=${limiter.ratio}"
                }.fold(
                    onSuccess = { it },
                    onFailure = { error ->
                        "readError=${error.javaClass.simpleName}:${error.message}"
                    },
                )
                ProviderLogger.diagnostic(
                    "[AtmosVolumeDiag] event=dynamics_verify,elapsedMs=" +
                        "${atmosphereDiagnosticElapsedRealtime()},delayMs=$delayMs,$context,$state"
                )
            }, delayMs)
        }
    }
}

internal class AppleAtmosVolumeProcessor(
    private val preferenceEnabled: () -> Boolean,
    private val effectFactory: (
        audioSessionId: Int,
        channelCount: Int,
        decision: AppleAtmosGainDecision,
        initialInputGainDb: Float,
    ) -> AppleSessionDynamicsEffect = { sessionId, channelCount, _, initialInputGainDb ->
        AndroidAppleSessionDynamicsEffect(sessionId, channelCount, initialInputGainDb)
    },
    private val scheduleDelayed: (delayMs: Long, action: () -> Unit) -> Unit =
        { delayMs, action -> Handler(Looper.getMainLooper()).postDelayed(action, delayMs) },
) {
    private data class PlayerState(
        var audioSessionId: Int = 0,
        var audioVariant: Int = -1,
        var periodId: Long = 0L,
        var loudness: Float = Float.NaN,
        var channelCount: Int = 2,
        var sessionGeneration: Long = 0L,
        var sessionGenerationAtLastVariant: Long = 0L,
        var pendingHotUpgradeSessionId: Int = 0,
        var pendingHotUpgradeTrackIds: Set<Int> = emptySet(),
        var rampHotUpgradeOnNextApply: Boolean = false,
    )

    private val playerStates = WeakIdentityMap<Any, PlayerState>()
    private val activeAudioTrackIdsBySession = mutableMapOf<Int, MutableSet<Int>>()
    private var activePlayer: Any? = null
    private var activeEffect: AppleSessionDynamicsEffect? = null
    private var activeEffectSessionId = 0
    private var activeEffectGeneration = 0L
    private var failedSessionId = 0

    @Synchronized
    fun onPlayerActivated(player: Any) {
        if (activePlayer !== player) {
            releaseActiveEffect("active_player_changed")
            activePlayer = player
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
            if (activePlayer === player) {
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
        state.channelCount = channelCount.coerceAtLeast(1)
        state.sessionGenerationAtLastVariant = state.sessionGeneration
        if (activePlayer === player) failedSessionId = 0

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
    fun onAudioTrackPlayed(audioSessionId: Int, trackIdentity: Int) {
        if (audioSessionId <= 0) return
        activeAudioTrackIdsBySession.getOrPut(audioSessionId, ::mutableSetOf).add(trackIdentity)
        activePlayer?.let { player ->
            playerStates[player]?.let { state ->
                logProcessorState(
                    event = "audio_track_play",
                    action = "tracked",
                    player = player,
                    state = state,
                    extra = "track=$trackIdentity,trackSessionId=$audioSessionId," +
                        "activeTracks=${activeAudioTrackIdsBySession[audioSessionId]?.sorted()}",
                )
            }
        }
    }

    @Synchronized
    fun onAudioTrackStopped(audioSessionId: Int, trackIdentity: Int, source: String) {
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
        failedSessionId = 0
        activePlayer?.let { player ->
            playerStates[player]?.let { state ->
                logProcessorState("preference_changed", "received", player, state)
            }
        }
        reconcileActivePlayer("preference_changed")
    }

    @Synchronized
    fun onPlayerReleased(player: Any) {
        playerStates.remove(player)
        if (activePlayer === player) {
            releaseActiveEffect("player_released")
            activePlayer = null
            failedSessionId = 0
        }
    }

    private fun playerState(player: Any): PlayerState =
        playerStates[player] ?: PlayerState().also { playerStates[player] = it }

    private fun reconcileActivePlayer(trigger: String) {
        val player = activePlayer
        val state = player?.let(playerStates::get)
        val enabled = runCatching(preferenceEnabled).getOrDefault(false)
        val blockedReason = when {
            state == null -> "no_active_state"
            !enabled -> "preference_disabled"
            state.audioVariant != APPLE_AUDIO_VARIANT_DOLBY_ATMOS -> "not_atmos"
            state.audioSessionId <= 0 -> "no_session"
            state.pendingHotUpgradeTrackIds.isNotEmpty() -> "waiting_old_audio_tracks"
            else -> null
        }
        if (blockedReason != null) {
            releaseActiveEffect("reconcile_$blockedReason")
            if (player != null && state != null) {
                logProcessorState(
                    event = "processor_reconcile",
                    action = "blocked_$blockedReason",
                    player = player,
                    state = state,
                    extra = "trigger=$trigger,preferenceEnabled=$enabled",
                )
            }
            return
        }
        checkNotNull(player)
        checkNotNull(state)
        if (activeEffect != null && activeEffectSessionId == state.audioSessionId) {
            logProcessorState(
                event = "processor_reconcile",
                action = "already_active",
                player = player,
                state = state,
                extra = "trigger=$trigger,preferenceEnabled=$enabled",
            )
            return
        }
        releaseActiveEffect("replace_effect")
        if (failedSessionId == state.audioSessionId) {
            logProcessorState(
                event = "processor_reconcile",
                action = "blocked_failed_session",
                player = player,
                state = state,
                extra = "trigger=$trigger,preferenceEnabled=$enabled",
            )
            return
        }

        val decision = resolveAppleAtmosGain(state.loudness)
        val hotUpgradeRamp = state.rampHotUpgradeOnNextApply
        val initialInputGainDb = if (hotUpgradeRamp) 0f else decision.inputGainDb
        logProcessorState(
            event = "processor_reconcile",
            action = "creating_effect",
            player = player,
            state = state,
            extra = "trigger=$trigger,preferenceEnabled=$enabled," +
                "inputGainDb=${decision.inputGainDb},initialInputGainDb=$initialInputGainDb," +
                "hotUpgradeRamp=$hotUpgradeRamp,fallback=${decision.fallback}",
        )
        var createdEffect: AppleSessionDynamicsEffect? = null
        runCatching {
            effectFactory(
                state.audioSessionId,
                state.channelCount,
                decision,
                initialInputGainDb,
            ).also { effect ->
                createdEffect = effect
                effect.setEnabled(true)
            }
        }.onSuccess { effect ->
            activeEffect = effect
            activeEffectSessionId = state.audioSessionId
            activeEffectGeneration += 1L
            val effectGeneration = activeEffectGeneration
            val appliedPeriodId = state.periodId
            val appliedSessionId = state.audioSessionId
            state.pendingHotUpgradeSessionId = 0
            state.pendingHotUpgradeTrackIds = emptySet()
            state.rampHotUpgradeOnNextApply = false
            val diagnosticContext =
                "player=${System.identityHashCode(player)},periodId=$appliedPeriodId," +
                    "sessionId=$appliedSessionId,expectedInputGainDb=${decision.inputGainDb}"
            runCatching {
                ProviderLogger.info(
                    "[AtmosVolumeDiag] event=dynamics_apply," +
                        "elapsedMs=${atmosphereDiagnosticElapsedRealtime()}," +
                        "player=${System.identityHashCode(player)}," +
                        "periodId=$appliedPeriodId,sessionId=$appliedSessionId," +
                        "loudness=${decision.metadataLoudness},channels=${state.channelCount}," +
                        "initialInputGainDb=$initialInputGainDb," +
                        "targetInputGainDb=${decision.inputGainDb}," +
                        "hotUpgradeRamp=$hotUpgradeRamp,fallback=${decision.fallback}," +
                        "limiterThresholdDb=$APPLE_ATMOS_LIMITER_THRESHOLD_DBFS," +
                        "limiterRatio=$APPLE_ATMOS_LIMITER_RATIO"
                )
            }
            if (hotUpgradeRamp && decision.inputGainDb > initialInputGainDb) {
                scheduleHotUpgradeRamp(
                    effect = effect,
                    effectGeneration = effectGeneration,
                    playerIdentity = System.identityHashCode(player),
                    periodId = appliedPeriodId,
                    sessionId = appliedSessionId,
                    targetInputGainDb = decision.inputGainDb,
                    diagnosticContext = diagnosticContext,
                )
            } else {
                effect.scheduleDiagnosticVerification(diagnosticContext)
            }
        }.onFailure { error ->
            createdEffect?.let { effect ->
                runCatching { effect.setEnabled(false) }
                runCatching(effect::release)
            }
            failedSessionId = state.audioSessionId
            logProcessorState(
                event = "processor_reconcile",
                action = "effect_create_failed",
                player = player,
                state = state,
                extra = "trigger=$trigger,error=${error.javaClass.simpleName}:${error.message}",
            )
            runCatching {
                ProviderLogger.error(
                    "Apple Music 当前音频会话不支持 DynamicsProcessing 音量平衡：" +
                        "session=${state.audioSessionId}",
                    error,
                )
            }
        }
    }

    private fun scheduleHotUpgradeRamp(
        effect: AppleSessionDynamicsEffect,
        effectGeneration: Long,
        playerIdentity: Int,
        periodId: Long,
        sessionId: Int,
        targetInputGainDb: Float,
        diagnosticContext: String,
    ) {
        repeat(APPLE_ATMOS_HOT_UPGRADE_RAMP_STEPS) { zeroBasedStep ->
            val step = zeroBasedStep + 1
            val delayMs = APPLE_ATMOS_HOT_UPGRADE_RAMP_DURATION_MS * step /
                APPLE_ATMOS_HOT_UPGRADE_RAMP_STEPS
            scheduleDelayed(delayMs) {
                synchronized(this@AppleAtmosVolumeProcessor) {
                    if (activeEffect !== effect ||
                        activeEffectGeneration != effectGeneration ||
                        activeEffectSessionId != sessionId
                    ) {
                        return@synchronized
                    }
                    val inputGainDb = targetInputGainDb * step /
                        APPLE_ATMOS_HOT_UPGRADE_RAMP_STEPS
                    runCatching { effect.setInputGainDb(inputGainDb) }
                        .onFailure { error ->
                            failedSessionId = sessionId
                            ProviderLogger.error(
                                "Apple Music DynamicsProcessing 热升级增益渐变失败：" +
                                    "session=$sessionId, step=$step",
                                error,
                            )
                            releaseActiveEffect("hot_upgrade_ramp_failed")
                            return@synchronized
                        }
                    if (step == APPLE_ATMOS_HOT_UPGRADE_RAMP_STEPS) {
                        runCatching {
                            ProviderLogger.info(
                                "[AtmosVolumeDiag] event=dynamics_ramp_complete," +
                                    "elapsedMs=${atmosphereDiagnosticElapsedRealtime()}," +
                                    "player=$playerIdentity,periodId=$periodId," +
                                    "sessionId=$sessionId,inputGainDb=$inputGainDb," +
                                    "durationMs=$APPLE_ATMOS_HOT_UPGRADE_RAMP_DURATION_MS," +
                                    "steps=$APPLE_ATMOS_HOT_UPGRADE_RAMP_STEPS"
                            )
                        }
                        effect.scheduleDiagnosticVerification(diagnosticContext)
                    }
                }
            }
        }
    }

    private fun clearPendingHotUpgrade(state: PlayerState) {
        state.pendingHotUpgradeSessionId = 0
        state.pendingHotUpgradeTrackIds = emptySet()
        state.rampHotUpgradeOnNextApply = false
    }

    private fun releaseActiveEffect(reason: String) {
        val effect = activeEffect ?: return
        runCatching {
            ProviderLogger.info(
                "[AtmosVolumeDiag] event=dynamics_release," +
                    "elapsedMs=${atmosphereDiagnosticElapsedRealtime()}," +
                    "player=${activePlayer?.let(System::identityHashCode)}," +
                    "sessionId=$activeEffectSessionId,reason=$reason"
            )
        }
        activeEffectGeneration += 1L
        runCatching { effect.setEnabled(false) }
        runCatching(effect::release)
        activeEffect = null
        activeEffectSessionId = 0
    }

    private fun logProcessorState(
        event: String,
        action: String,
        player: Any,
        state: PlayerState,
        extra: String? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val suffix = extra?.let { ",$it" }.orEmpty()
        ProviderLogger.diagnostic(
            "[AtmosVolumeDiag] event=$event,elapsedMs=${atmosphereDiagnosticElapsedRealtime()}," +
                "action=$action,player=${System.identityHashCode(player)}," +
                "activePlayer=${activePlayer?.let(System::identityHashCode)}," +
                "variant=${state.audioVariant},periodId=${state.periodId}," +
                "sessionId=${state.audioSessionId},loudness=${state.loudness}," +
                "channels=${state.channelCount},sessionGeneration=${state.sessionGeneration}," +
                "sessionGenerationAtLastVariant=${state.sessionGenerationAtLastVariant}," +
                "pendingHotUpgradeSessionId=${state.pendingHotUpgradeSessionId}," +
                "pendingHotUpgradeTrackIds=${state.pendingHotUpgradeTrackIds.sorted()}," +
                "rampHotUpgradeOnNextApply=${state.rampHotUpgradeOnNextApply}," +
                "activeEffectSessionId=$activeEffectSessionId," +
                "failedSessionId=$failedSessionId$suffix"
        )
    }
}
