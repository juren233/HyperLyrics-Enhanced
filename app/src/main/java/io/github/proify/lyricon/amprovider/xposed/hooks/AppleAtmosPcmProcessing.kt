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

// These extensions must share the receiver monitor with the synchronized playback callbacks.
// @Synchronized on an extension would lock the generated file class instead.
internal fun AppleAtmosVolumeProcessor.capturePcmContext(audioSessionId: Int, trackIdentity: Int): AppleAtmosPcmContext? {
    synchronized(this) {
        if (!runCatching(preferenceEnabled).getOrDefault(false)) return null
        val state = activePlayer?.let(playerStates::get) ?: return null
        if (audioSessionId <= 0 || state.audioVariant < 0 ||
            state.pendingHotUpgradeTrackIds.isNotEmpty()
        ) return null
        val isStateSession = audioSessionId == state.audioSessionId
        // Renderer switches can start a new AudioTrack session without a player session
        // callback (AM-ATMOS-IMMERSIVE-001 2026-09-07 真机：Atmos 3401 → ALAC 3321 无回调)。
        // 非 Atmos 期间放行本进程内已注册的真实扬声器音轨；Atmos 期间仍要求状态会话严格一致。
        if (!isStateSession && state.audioVariant == APPLE_AUDIO_VARIANT_DOLBY_ATMOS) return null
        if (trackIdentity !in activeAudioTrackIdsBySession[audioSessionId].orEmpty() ||
            audioTrackRoutes[trackIdentity] != AppleAtmosOutputRoute.BUILT_IN_SPEAKER ||
            outputRouteForSession(audioSessionId) != AppleAtmosOutputRoute.BUILT_IN_SPEAKER
        ) return null
        return AppleAtmosPcmContext(audioSessionId, trackIdentity, pcmGeneration)
    }
}

internal fun AppleAtmosVolumeProcessor.onPcmDiscontinuity(audioSessionId: Int, trackIdentity: Int, flush: Boolean) {
    synchronized(this) {
        if (audioSessionId != activePlayer?.let(playerStates::get)?.audioSessionId ||
            trackIdentity !in activeAudioTrackIdsBySession[audioSessionId].orEmpty()
        ) return
        invalidatePcm(resetStats = flush)
        if (flush) reconcileActivePlayer("pcm_flush")
    }
}

/** Only the active speaker Track/Period may contribute a successful-write PCM window. */
internal fun AppleAtmosVolumeProcessor.onPcmWindow(window: AppleAtmosPcmWindow) {
    synchronized(this) {
        if (window.context != capturePcmContext(window.sessionId, window.context.trackIdentity)) return
        if (window.frameCount <= 0 || window.sampleRate !in 8_000..384_000 ||
            window.channelCount !in 1..32 || !window.effectivePeakDbfs.isFinite()
        ) return
        val player = activePlayer ?: return
        val state = playerStates[player] ?: return
        if (window.sessionId != state.audioSessionId) {
            // 已注册的异会话音轨（渲染器切换未触发会话回调）只允许学习响度参考，
            // 严禁驱动 Atmos 增益路径：效果必须只挂在状态会话上。
            if (state.audioVariant != APPLE_AUDIO_VARIANT_DOLBY_ATMOS &&
                window.channelCount <= 2 && window.frontEffectiveDbfs.isFinite() &&
                window.frontEffectiveDbfs >= APPLE_ATMOS_PCM_MIN_LEVEL_DBFS
            ) {
                updateNonAtmosReference(state, window)
            }
            return
        }
        if (state.audioVariant == APPLE_AUDIO_VARIANT_DOLBY_ATMOS) {
            updateAtmosPcmGain(player, state, window)
        } else if (window.channelCount <= 2 && window.frontEffectiveDbfs.isFinite() &&
            window.frontEffectiveDbfs >= APPLE_ATMOS_PCM_MIN_LEVEL_DBFS
        ) {
            updateNonAtmosReference(state, window)
        }
    }
}

internal fun AppleAtmosVolumeProcessor.updateAtmosPcmGain(
    player: Any,
    state: AppleAtmosVolumeProcessor.PlayerState,
    window: AppleAtmosPcmWindow,
) {
    if (state.pcmLearnedPeakDbfs.isNaN() || window.effectivePeakDbfs > state.pcmLearnedPeakDbfs) {
        state.pcmLearnedPeakDbfs = window.effectivePeakDbfs
    }
    val peakCap = (APPLE_ATMOS_PCM_PEAK_CEILING_DBFS - state.pcmLearnedPeakDbfs)
        .coerceIn(0f, APPLE_ATMOS_MAX_INPUT_GAIN_DB)
    val hasSignal = window.frontEffectiveDbfs.isFinite() &&
        window.frontEffectiveDbfs >= APPLE_ATMOS_PCM_MIN_LEVEL_DBFS
    if (hasSignal) {
        // Gain is applied per write by the meter, not retroactively using the last volume.
        val windowPower = Math.pow(10.0, (window.frontEffectiveDbfs / 10f).toDouble())
        state.pcmFrontPowerSum += windowPower * window.frameCount
        state.pcmFrontFrameCount += window.frameCount
    }
    val integratedFrontDb = state.integratedFrontPowerDb()
    val reference = nonAtmosReferenceDbfs ?: APPLE_ATMOS_PCM_FALLBACK_REFERENCE_DBFS
    val decision = if (integratedFrontDb != null) {
        resolveAppleAtmosPcmGain(reference, integratedFrontDb, state.pcmLearnedPeakDbfs)
    } else {
        AppleAtmosPcmGainDecision(activeEffectInputGainDb, peakCap, minOf(activeEffectInputGainDb, peakCap))
    }
    if (BuildConfig.DEBUG) {
        ProviderLogger.diagnostic(
            "[AtmosVolumeDiag] event=pcm_control,elapsedMs=${atmosphereDiagnosticElapsedRealtime()}," +
                "player=${System.identityHashCode(player)},periodId=${state.periodId}," +
                "sessionId=${state.audioSessionId},integratedFrontDb=$integratedFrontDb," +
                "clientGainDb=${window.clientGainDb},atmosEffectiveDb=$integratedFrontDb," +
                "referenceDb=$reference,referenceSource=" +
                "${if (nonAtmosReferenceDbfs == null) "fallback" else "non_atmos_ema"}," +
                "learnedPeakDbfs=${state.pcmLearnedPeakDbfs}," +
                "windowPeakDbfs=${window.peakDbfs}," +
                "desiredBoostDb=${decision.desiredBoostDb},peakCapDb=${decision.peakCapDb}," +
                "targetBoostDb=${decision.targetBoostDb},commandedTargetDb=$pcmTargetGainDb," +
                "activeEffectSessionId=$activeEffectSessionId," +
                "activeEffectInputGainDb=$activeEffectInputGainDb"
        )
    }
    if (activeEffect == null || activeEffectSessionId != state.audioSessionId) return
    val commanded = pcmTargetGainDb ?: activeEffectInputGainDb
    if (peakCap < maxOf(commanded, activeEffectInputGainDb)) {
        // Observed peak headroom takes precedence over both hysteresis and a pending rise.
        applyGainToActiveEffect(player, state, minOf(peakCap, decision.targetBoostDb),
            "pcm_safety_down", immediate = true)
        return
    }
    if (!hasSignal) return // Silence must not request a boost or contaminate the reference.
    val target = decision.targetBoostDb
    val reason = when {
        target >= commanded + APPLE_ATMOS_PCM_UP_HYSTERESIS_DB -> "pcm_target_up"
        target <= commanded - APPLE_ATMOS_PCM_DOWN_HYSTERESIS_DB -> "pcm_target_down"
        else -> return
    }
    // Quiet intros cannot cause a single large step to the +10 dB ceiling.
    applyGainToActiveEffect(player, state, minOf(target, commanded + 1f), reason)
}

internal fun AppleAtmosVolumeProcessor.updateNonAtmosReference(state: AppleAtmosVolumeProcessor.PlayerState, window: AppleAtmosPcmWindow) {
    val previous = nonAtmosReferenceDbfs
    // 响侧离群门限：比当前参考响出 6 dB 的窗口视为未做 Sound Check 的爆响流
    // （如杜比歌的立体声起播段），拒绝入库；低于参考的窗口正常接受，保证可向下恢复。
    if (previous != null && previous.isFinite() &&
        window.frontEffectiveDbfs > previous + APPLE_ATMOS_PCM_REFERENCE_MAX_ABOVE_DB
    ) {
        if (BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "[AtmosVolumeDiag] event=pcm_reference_skip," +
                    "elapsedMs=${atmosphereDiagnosticElapsedRealtime()}," +
                    "sessionId=${state.audioSessionId},variant=${state.audioVariant}," +
                    "windowEffectiveDb=${window.frontEffectiveDbfs}," +
                    "referenceDb=$previous,limitDb=${previous + APPLE_ATMOS_PCM_REFERENCE_MAX_ABOVE_DB}"
            )
        }
        return
    }
    val updated = mixAppleAtmosReferenceDb(previous, window.frontEffectiveDbfs)
    nonAtmosReferenceDbfs = updated
    if (BuildConfig.DEBUG && previous != updated) {
        ProviderLogger.diagnostic(
            "[AtmosVolumeDiag] event=pcm_reference," +
                "elapsedMs=${atmosphereDiagnosticElapsedRealtime()}," +
                "sessionId=${state.audioSessionId},variant=${state.audioVariant}," +
                "frontRmsDbfs=${window.frontRmsDbfs},clientGainDb=${window.clientGainDb}," +
                "windowEffectiveDb=${window.frontEffectiveDbfs}," +
                "previousDb=$previous,updatedDb=$updated"
        )
    }
}

internal fun AppleAtmosVolumeProcessor.applyGainToActiveEffect(
    player: Any,
    state: AppleAtmosVolumeProcessor.PlayerState,
    targetGainDb: Float,
    reason: String,
    immediate: Boolean = false,
) {
    val effect = activeEffect ?: return
    activeEffectGeneration += 1L
    val effectGeneration = activeEffectGeneration
    pcmTargetGainDb = targetGainDb
    if (immediate) {
        runCatching { effect.setInputGainDb(targetGainDb) }
            .onSuccess {
                activeEffectInputGainDb = targetGainDb
                if (BuildConfig.DEBUG) {
                    effect.scheduleDiagnosticVerification("sessionId=${state.audioSessionId}," +
                        "reason=$reason,expectedInputGainDb=$targetGainDb")
                }
            }
            .onFailure {
                failedSessionId = state.audioSessionId
                ProviderLogger.error("Apple Music PCM 峰值保护增益更新失败", it)
                releaseActiveEffect("pcm_safety_update_failed")
            }
        return
    }
    logProcessorState(
        event = "processor_reconcile",
        action = "pcm_gain_update",
        player = player,
        state = state,
        extra = "reason=$reason,fromInputGainDb=$activeEffectInputGainDb," +
            "targetInputGainDb=$targetGainDb",
    )
    scheduleInputGainRamp(
        effect = effect,
        effectGeneration = effectGeneration,
        playerIdentity = System.identityHashCode(player),
        periodId = state.periodId,
        sessionId = state.audioSessionId,
        fromInputGainDb = activeEffectInputGainDb,
        targetInputGainDb = targetGainDb,
        reason = reason,
        durationMs = APPLE_ATMOS_PCM_RAMP_DURATION_MS,
        diagnosticContext = "player=${System.identityHashCode(player)}," +
            "periodId=${state.periodId},sessionId=${state.audioSessionId}," +
            "expectedInputGainDb=$targetGainDb",
    )
}

internal fun AppleAtmosVolumeProcessor.onPlayerReleased(player: Any) {
    synchronized(this) {
        playerStates.remove(player)
        if (activePlayer === player) {
            invalidatePcm()
            releaseActiveEffect("player_released")
            activePlayer = null
            failedSessionId = 0
        }
    }
}

internal fun AppleAtmosVolumeProcessor.playerState(player: Any): AppleAtmosVolumeProcessor.PlayerState =
    playerStates[player] ?: AppleAtmosVolumeProcessor.PlayerState().also { playerStates[player] = it }

internal fun AppleAtmosVolumeProcessor.reconcileActivePlayer(trigger: String) {
    val player = activePlayer
    val state = player?.let(playerStates::get)
    val enabled = runCatching(preferenceEnabled).getOrDefault(false)
    val outputRoute = state?.audioSessionId
        ?.takeIf { it > 0 }
        ?.let(::outputRouteForSession)
        ?: AppleAtmosOutputRoute.UNKNOWN
    val blockedReason = when {
        state == null -> "no_active_state"
        !enabled -> "preference_disabled"
        state.audioVariant != APPLE_AUDIO_VARIANT_DOLBY_ATMOS -> "not_atmos"
        state.audioSessionId <= 0 -> "no_session"
        state.pendingHotUpgradeTrackIds.isNotEmpty() -> "waiting_old_audio_tracks"
        outputRoute == AppleAtmosOutputRoute.UNKNOWN -> "route_unknown"
        outputRoute != AppleAtmosOutputRoute.BUILT_IN_SPEAKER -> "non_speaker_route"
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
                extra = "trigger=$trigger,preferenceEnabled=$enabled," +
                    "outputRoute=$outputRoute",
            )
        }
        return
    }
    checkNotNull(player)
    checkNotNull(state)
    val decision = resolveAppleAtmosGain(state.loudness, state.peakMetadata)
    if (activeEffect != null && activeEffectSessionId == state.audioSessionId) {
        // PCM 校准接管后，重复元数据/路由通知不得覆盖累计目标。
        if (pcmTargetGainDb == null &&
            kotlin.math.abs(decision.inputGainDb - activeEffectInputGainDb) >= 0.05f
        ) {
            activeEffectGeneration += 1L
            val effectGeneration = activeEffectGeneration
            val effect = checkNotNull(activeEffect)
            logProcessorState(
                event = "processor_reconcile",
                action = "updating_active_gain",
                player = player,
                state = state,
                extra = "trigger=$trigger,fromInputGainDb=$activeEffectInputGainDb," +
                    "targetInputGainDb=${decision.inputGainDb}," +
                    gainDecisionLogFields(decision),
            )
            scheduleInputGainRamp(
                effect = effect,
                effectGeneration = effectGeneration,
                playerIdentity = System.identityHashCode(player),
                periodId = state.periodId,
                sessionId = state.audioSessionId,
                fromInputGainDb = activeEffectInputGainDb,
                targetInputGainDb = decision.inputGainDb,
                reason = "metadata_update",
                diagnosticContext = "player=${System.identityHashCode(player)}," +
                    "periodId=${state.periodId},sessionId=${state.audioSessionId}," +
                    "expectedInputGainDb=${decision.inputGainDb}",
            )
            return
        }
        logProcessorState(
            event = "processor_reconcile",
            action = "already_active",
            player = player,
            state = state,
            extra = "trigger=$trigger,preferenceEnabled=$enabled," +
                "outputRoute=$outputRoute," + gainDecisionLogFields(decision),
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
            extra = "trigger=$trigger,preferenceEnabled=$enabled," +
                "outputRoute=$outputRoute",
        )
        return
    }

    val hotUpgradeRamp = state.rampHotUpgradeOnNextApply
    val initialInputGainDb = if (hotUpgradeRamp) 0f else decision.inputGainDb
    logProcessorState(
        event = "processor_reconcile",
        action = "creating_effect",
        player = player,
        state = state,
        extra = "trigger=$trigger,preferenceEnabled=$enabled," +
            "initialInputGainDb=$initialInputGainDb," +
            "hotUpgradeRamp=$hotUpgradeRamp,fallback=${decision.fallback}," +
            "outputRoute=$outputRoute," + gainDecisionLogFields(decision),
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
        activeEffectInputGainDb = initialInputGainDb
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
        if (BuildConfig.DEBUG) runCatching {
            ProviderLogger.diagnostic(
                "[AtmosVolumeDiag] event=dynamics_apply," +
                    "elapsedMs=${atmosphereDiagnosticElapsedRealtime()}," +
                    "player=${System.identityHashCode(player)}," +
                    "periodId=$appliedPeriodId,sessionId=$appliedSessionId," +
                    "effect=${effect.effectKind}," +
                    "loudness=${decision.metadataLoudness},channels=${state.channelCount}," +
                    "initialInputGainDb=$initialInputGainDb," +
                    "targetInputGainDb=${decision.inputGainDb}," +
                    "requestedInputGainDb=${decision.requestedInputGainDb}," +
                    "peakDbfs=${decision.peakDbfs},peakSource=${decision.peakSource}," +
                    "peakHeadroomDb=${decision.peakHeadroomDb}," +
                    "peakLimited=${decision.peakLimited}," +
                    "peakAssociation=${state.peakMetadata?.associationSource}," +
                    "hotUpgradeRamp=$hotUpgradeRamp,fallback=${decision.fallback}," +
                    "outputRoute=$outputRoute"
            )
        }
        if (hotUpgradeRamp && decision.inputGainDb > initialInputGainDb) {
            scheduleInputGainRamp(
                effect = effect,
                effectGeneration = effectGeneration,
                playerIdentity = System.identityHashCode(player),
                periodId = appliedPeriodId,
                sessionId = appliedSessionId,
                fromInputGainDb = initialInputGainDb,
                targetInputGainDb = decision.inputGainDb,
                reason = "hot_upgrade",
                diagnosticContext = diagnosticContext,
            )
        } else if (BuildConfig.DEBUG) {
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
                "Apple Music 当前音频会话不支持 LoudnessEnhancer 音量提升：" +
                    "session=${state.audioSessionId}",
                error,
            )
        }
    }
}

internal fun AppleAtmosVolumeProcessor.scheduleInputGainRamp(
    effect: AppleSessionDynamicsEffect,
    effectGeneration: Long,
    playerIdentity: Int,
    periodId: Long,
    sessionId: Int,
    fromInputGainDb: Float,
    targetInputGainDb: Float,
    reason: String,
    diagnosticContext: String,
    durationMs: Long = APPLE_ATMOS_HOT_UPGRADE_RAMP_DURATION_MS,
) {
    repeat(APPLE_ATMOS_HOT_UPGRADE_RAMP_STEPS) { zeroBasedStep ->
        val step = zeroBasedStep + 1
        val delayMs = durationMs * step /
            APPLE_ATMOS_HOT_UPGRADE_RAMP_STEPS
        scheduleDelayed(delayMs) {
            synchronized(this) {
                if (activeEffect !== effect ||
                    activeEffectGeneration != effectGeneration ||
                    activeEffectSessionId != sessionId ||
                    activePlayer?.let(System::identityHashCode) != playerIdentity ||
                    activePlayer?.let(playerStates::get)?.periodId != periodId
                ) {
                    return@synchronized
                }
                val progress = step.toFloat() / APPLE_ATMOS_HOT_UPGRADE_RAMP_STEPS
                val inputGainDb = fromInputGainDb +
                    (targetInputGainDb - fromInputGainDb) * progress
                runCatching { effect.setInputGainDb(inputGainDb) }
                    .onSuccess { activeEffectInputGainDb = inputGainDb }
                    .onFailure { error ->
                        failedSessionId = sessionId
                        ProviderLogger.error(
                            "Apple Music LoudnessEnhancer 增益渐变失败：" +
                                "session=$sessionId, step=$step, reason=$reason",
                            error,
                        )
                        releaseActiveEffect("input_gain_ramp_failed")
                        return@synchronized
                    }
                if (BuildConfig.DEBUG && step == APPLE_ATMOS_HOT_UPGRADE_RAMP_STEPS) {
                    runCatching {
                        ProviderLogger.diagnostic(
                            "[AtmosVolumeDiag] event=dynamics_ramp_complete," +
                                "elapsedMs=${atmosphereDiagnosticElapsedRealtime()}," +
                            "player=$playerIdentity,periodId=$periodId," +
                            "sessionId=$sessionId,inputGainDb=$inputGainDb," +
                            "reason=$reason," +
                            "durationMs=$durationMs," +
                                "steps=$APPLE_ATMOS_HOT_UPGRADE_RAMP_STEPS"
                        )
                    }
                    effect.scheduleDiagnosticVerification(diagnosticContext)
                }
            }
        }
    }
}

internal fun AppleAtmosVolumeProcessor.outputRouteForSession(audioSessionId: Int): AppleAtmosOutputRoute {
    val routes = activeAudioTrackIdsBySession[audioSessionId]
        .orEmpty()
        .mapNotNull(audioTrackRoutes::get)
    return when {
        routes.any { it == AppleAtmosOutputRoute.NON_SPEAKER } ->
            AppleAtmosOutputRoute.NON_SPEAKER
        routes.any { it == AppleAtmosOutputRoute.UNKNOWN } ->
            AppleAtmosOutputRoute.UNKNOWN
        routes.any { it == AppleAtmosOutputRoute.BUILT_IN_SPEAKER } ->
            AppleAtmosOutputRoute.BUILT_IN_SPEAKER
        else -> AppleAtmosOutputRoute.UNKNOWN
    }
}

internal fun AppleAtmosVolumeProcessor.clearPendingHotUpgrade(state: AppleAtmosVolumeProcessor.PlayerState) {
    state.pendingHotUpgradeSessionId = 0
    state.pendingHotUpgradeTrackIds = emptySet()
    state.rampHotUpgradeOnNextApply = false
}

internal fun AppleAtmosVolumeProcessor.gainDecisionLogFields(decision: AppleAtmosGainDecision): String =
    "inputGainDb=${decision.inputGainDb}," +
        "requestedInputGainDb=${decision.requestedInputGainDb}," +
        "peakDbfs=${decision.peakDbfs},peakSource=${decision.peakSource}," +
        "peakHeadroomDb=${decision.peakHeadroomDb}," +
        "peakLimited=${decision.peakLimited},fallback=${decision.fallback}"

internal fun AppleAtmosVolumeProcessor.releaseActiveEffect(reason: String) {
    pcmTargetGainDb = null
    val effect = activeEffect ?: return
    if (BuildConfig.DEBUG) runCatching {
        ProviderLogger.diagnostic(
            "[AtmosVolumeDiag] event=dynamics_release," +
                "elapsedMs=${atmosphereDiagnosticElapsedRealtime()}," +
                "player=${activePlayer?.let(System::identityHashCode)}," +
                "sessionId=$activeEffectSessionId,effect=${effect.effectKind},reason=$reason"
        )
    }
    activeEffectGeneration += 1L
    runCatching { effect.setEnabled(false) }
    runCatching(effect::release)
    activeEffect = null
    activeEffectSessionId = 0
    activeEffectInputGainDb = 0f
    pcmTargetGainDb = null
}

internal fun AppleAtmosVolumeProcessor.logProcessorState(
    event: String,
    action: String,
    player: Any,
    state: AppleAtmosVolumeProcessor.PlayerState,
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
            "truePeakDbfs=${state.peakMetadata?.truePeakDbfs}," +
            "samplePeakDbfs=${state.peakMetadata?.samplePeakDbfs}," +
            "peakAssociation=${state.peakMetadata?.associationSource}," +
            "channels=${state.channelCount},sessionGeneration=${state.sessionGeneration}," +
            "sessionGenerationAtLastVariant=${state.sessionGenerationAtLastVariant}," +
            "pendingHotUpgradeSessionId=${state.pendingHotUpgradeSessionId}," +
            "pendingHotUpgradeTrackIds=${state.pendingHotUpgradeTrackIds.sorted()}," +
            "rampHotUpgradeOnNextApply=${state.rampHotUpgradeOnNextApply}," +
            "outputRoute=${outputRouteForSession(state.audioSessionId)}," +
            "activeEffectSessionId=$activeEffectSessionId," +
            "activeEffectInputGainDb=$activeEffectInputGainDb," +
            "failedSessionId=$failedSessionId$suffix"
    )
}
