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

internal interface AppleSessionDynamicsEffect {
    val effectKind: String get() = "loudness_enhancer"

    fun setEnabled(enabled: Boolean)
    fun setInputGainDb(inputGainDb: Float)
    fun release()
    fun scheduleDiagnosticVerification(context: String) = Unit
}

internal data class AppleAtmosPcmGainDecision(
    val desiredBoostDb: Float,
    val peakCapDb: Float,
    val targetBoostDb: Float,
)

/**
 * 已播放部分的累计 PCM 电平决策（不是整曲预分析或 LUFS）：
 * 期望提升 = 普通音源有效参考电平 - 杜比累计有效电平（只提升不衰减），
 * 安全封顶 = 实测峰值距数字满幅的余量，取小者。
 * 不对短窗做直接 AGC；累计目标仍可能渐进变化，样本峰值不能保证未来峰值不超限。
 */
internal fun resolveAppleAtmosPcmGain(
    referenceFrontDbfs: Float,
    atmosIntegratedFrontDbfs: Float,
    learnedPeakDbfs: Float,
): AppleAtmosPcmGainDecision {
    if (!referenceFrontDbfs.isFinite() || !atmosIntegratedFrontDbfs.isFinite()) {
        return AppleAtmosPcmGainDecision(0f, 0f, 0f)
    }
    val desired = (referenceFrontDbfs - atmosIntegratedFrontDbfs)
        .coerceIn(0f, APPLE_ATMOS_MAX_INPUT_GAIN_DB)
    val peakCap = if (learnedPeakDbfs.isFinite()) {
        (APPLE_ATMOS_PCM_PEAK_CEILING_DBFS - learnedPeakDbfs)
            .coerceIn(0f, APPLE_ATMOS_MAX_INPUT_GAIN_DB)
    } else {
        0f
    }
    val target = minOf(desired, peakCap)
    return AppleAtmosPcmGainDecision(desired, peakCap, target)
}

/** 普通立体声音源有效电平的功率域指数滑动平均；首个有效窗口初始化。 */
internal fun mixAppleAtmosReferenceDb(
    currentDb: Float?,
    windowDb: Float,
    alpha: Float = APPLE_ATMOS_PCM_REFERENCE_EMA_ALPHA,
): Float {
    if (!windowDb.isFinite()) return currentDb?.takeIf { it.isFinite() }
        ?: APPLE_ATMOS_PCM_FALLBACK_REFERENCE_DBFS
    if (currentDb == null || !currentDb.isFinite()) return windowDb
    val weight = alpha.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        ?: APPLE_ATMOS_PCM_REFERENCE_EMA_ALPHA
    val currentPower = Math.pow(10.0, (currentDb / 10f).toDouble())
    val windowPower = Math.pow(10.0, (windowDb / 10f).toDouble())
    val mixed = (1 - weight) * currentPower + weight * windowPower
    return (10.0 * Math.log10(mixed)).toFloat()
}

// AM-ATMOS-IMMERSIVE-001：DynamicsProcessing 在 HyperOS 4 多声道 Atmos 路径上会触发系统流
// 音量衰减被整体绕过（最低音量过响，2026-09-07 真机功率历史证实）；LoudnessEnhancer 挂同一
// 会话无此问题，因此统一使用 LoudnessEnhancer，不再使用 DynamicsProcessing。
internal class AndroidAppleSessionLoudnessEffect(
    audioSessionId: Int,
    initialInputGainDb: Float,
) : AppleSessionDynamicsEffect {
    private val effect = LoudnessEnhancer(audioSessionId).also { created ->
        try {
            created.setTargetGain((initialInputGainDb * 100f).toInt())
        } catch (error: Throwable) {
            runCatching(created::release)
            throw error
        }
    }

    override val effectKind = "loudness_enhancer"

    override fun setEnabled(enabled: Boolean) {
        effect.enabled = enabled
    }

    override fun setInputGainDb(inputGainDb: Float) {
        effect.setTargetGain((inputGainDb * 100f).toInt())
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
                    "enabled=${effect.enabled},targetGainmB=${effect.targetGain}"
                }.fold(
                    onSuccess = { it },
                    onFailure = { error ->
                        "readError=${error.javaClass.simpleName}:${error.message}"
                    },
                )
                ProviderLogger.diagnostic(
                    "[AtmosVolumeDiag] event=loudness_verify," +
                        "elapsedMs=${atmosphereDiagnosticElapsedRealtime()}," +
                        "delayMs=$delayMs,$context,$state"
                )
            }, delayMs)
        }
    }
}

