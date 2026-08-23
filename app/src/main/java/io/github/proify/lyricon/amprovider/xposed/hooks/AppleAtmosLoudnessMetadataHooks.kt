/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookTarget
import io.github.proify.lyricon.amprovider.xposed.AppleMusicProviderRuntime
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleReflection
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

internal data class AppleAtmosPeakMetadata(
    val loudness: Float,
    val truePeakDbfs: Float?,
    val samplePeakDbfs: Float?,
    val associationSource: String,
)

private data class AppleAtmosPendingPeakMetadata(
    val metadata: AppleAtmosPeakMetadata,
    val capturedAtMs: Long,
)

private data class AppleAtmosFormatFingerprint(
    val id: String?,
    val codecs: String?,
    val sampleMimeType: String?,
    val loudnessBits: Int,
    val channelCount: Int,
    val sampleRate: Int,
    val bitrate: Int,
)

private data class AppleAtmosStableFormatFingerprint(
    val codecs: String?,
    val sampleMimeType: String?,
    val loudnessBits: Int,
    val channelCount: Int,
    val sampleRate: Int,
)

/**
 * Captures ISO-BMFF `ludt/tlou` peak metadata before Apple Music discards it while copying only
 * track loudness into ExoPlayer's Format. The original 6.5.2 DEX descriptors are profiled in
 * [io.github.proify.lyricon.amprovider.xposed.AppleMusicHookProfiles].
 */
internal class AppleAtmosLoudnessMetadataHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val pendingPeakMetadata = ThreadLocal<AppleAtmosPendingPeakMetadata>()
    private val metadataByFormat = WeakIdentityMap<Any, AppleAtmosPeakMetadata>()
    private val metadataByFingerprint = LinkedHashMap<
        AppleAtmosFormatFingerprint,
        AppleAtmosPeakMetadata,
    >(APPLE_ATMOS_FORMAT_METADATA_CAPACITY, 0.75f, true)
    private val metadataByStableFingerprint = LinkedHashMap<
        AppleAtmosStableFormatFingerprint,
        AppleAtmosPeakMetadata,
    >(APPLE_ATMOS_FORMAT_METADATA_CAPACITY, 0.75f, true)
    private val trackLoudnessCallbackHit = AtomicBoolean(false)
    private val formatAssociationCallbackHit = AtomicBoolean(false)
    private val manifestPropagationCallbackHit = AtomicBoolean(false)
    private lateinit var trackLoudnessTarget: AppleMusicHookTarget
    private lateinit var formatCopyTarget: AppleMusicHookTarget

    fun installHooks() {
        installTrackLoudnessHook()
        installFormatCopyHook()
        installManifestFormatCopyHook()
    }

    fun metadataForFormat(format: Any?): AppleAtmosPeakMetadata? {
        format ?: return null
        metadataByFormat[format]?.let { metadata ->
            return metadata.copy(associationSource = "format_identity").also {
                logLookup(format, "identity", it)
            }
        }
        val target = if (::formatCopyTarget.isInitialized) formatCopyTarget else return null
        val fingerprint = runCatching { formatFingerprint(format, target) }.getOrNull() ?: return null
        synchronized(metadataByFingerprint) {
            metadataByFingerprint[fingerprint]
        }?.copy(associationSource = "format_fingerprint")?.let { metadata ->
            metadataByFormat[format] = metadata
            logLookup(format, "exact_fingerprint", metadata)
            return metadata
        }
        val stableFingerprint = fingerprint.stable()
        return synchronized(metadataByStableFingerprint) {
            metadataByStableFingerprint[stableFingerprint]
        }?.copy(associationSource = "stable_format_fingerprint")?.also { metadata ->
            metadataByFormat[format] = metadata
            logLookup(format, "stable_fingerprint", metadata)
        } ?: run {
            logLookup(format, "miss", null)
            null
        }
    }

    private fun installTrackLoudnessHook() {
        runCatching {
            val resolved = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.ATMOS_TRACK_LOUDNESS_METADATA
            )
            trackLoudnessTarget = resolved.target
            runtime.hookRegistrar.installHook(resolved.method, after = { chain, result ->
                val ludtData = chain.thisObject ?: return@installHook
                val returnedLoudness = (result as? Float)?.takeIf(Float::isFinite)
                    ?: run {
                        pendingPeakMetadata.remove()
                        return@installHook
                    }
                val metadata = readTrackPeakMetadata(
                    ludtData = ludtData,
                    returnedLoudness = returnedLoudness,
                    target = resolved.target,
                ) ?: run {
                    pendingPeakMetadata.remove()
                    return@installHook
                }
                pendingPeakMetadata.set(
                    AppleAtmosPendingPeakMetadata(
                        metadata = metadata,
                        capturedAtMs = elapsedRealtime(),
                    )
                )
                if (BuildConfig.DEBUG && trackLoudnessCallbackHit.compareAndSet(false, true)) {
                    ProviderLogger.diagnostic(
                        "Dolby Atmos ludt true-peak Hook 首次命中：" +
                            "loudness=${metadata.loudness},truePeak=${metadata.truePeakDbfs}," +
                            "samplePeak=${metadata.samplePeakDbfs}"
                    )
                }
                if (BuildConfig.DEBUG) {
                    ProviderLogger.diagnostic(
                        "[AtmosVolumeDiag] event=ludt_peak_parsed," +
                            "elapsedMs=${elapsedRealtime()},loudness=${metadata.loudness}," +
                            "truePeakDbfs=${metadata.truePeakDbfs}," +
                            "samplePeakDbfs=${metadata.samplePeakDbfs}"
                    )
                }
            })
            ProviderLogger.info("Apple Music ludt/tlou true-peak Hook 已安装")
        }.onFailure { error ->
            pendingPeakMetadata.remove()
            ProviderLogger.error("Apple Music ludt/tlou true-peak Hook 安装失败", error)
        }
    }

    private fun installFormatCopyHook() {
        runCatching {
            val resolved = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.ATMOS_FORMAT_COPY_WITH_LOUDNESS
            )
            formatCopyTarget = resolved.target
            runtime.hookRegistrar.installHook(resolved.method, after = { chain, result ->
                val pending = pendingPeakMetadata.get() ?: return@installHook
                pendingPeakMetadata.remove()
                val format = result ?: return@installHook
                val copiedLoudness = (chain.args.firstOrNull() as? Float)
                    ?.takeIf(Float::isFinite)
                    ?: return@installHook
                val ageMs = elapsedRealtime() - pending.capturedAtMs
                if (ageMs !in 0..APPLE_ATMOS_PENDING_PEAK_MAX_AGE_MS ||
                    abs(pending.metadata.loudness - copiedLoudness) >
                    APPLE_ATMOS_LOUDNESS_MATCH_TOLERANCE_LU
                ) {
                    if (BuildConfig.DEBUG) {
                        ProviderLogger.diagnostic(
                            "[AtmosVolumeDiag] event=ludt_peak_association," +
                                "action=rejected,loudness=$copiedLoudness," +
                                "pendingLoudness=${pending.metadata.loudness},ageMs=$ageMs"
                        )
                    }
                    return@installHook
                }
                val associated = pending.metadata.copy(
                    loudness = copiedLoudness,
                    associationSource = "format_identity",
                )
                val fingerprint = associateFormat(format, associated, resolved.target)
                val stableFingerprint = fingerprint.stable()
                if (BuildConfig.DEBUG && formatAssociationCallbackHit.compareAndSet(false, true)) {
                    ProviderLogger.diagnostic(
                        "Dolby Atmos Format true-peak 关联 Hook 首次命中：" +
                            "format=${System.identityHashCode(format)}," +
                            "loudness=$copiedLoudness,truePeak=${associated.truePeakDbfs}," +
                            "samplePeak=${associated.samplePeakDbfs}"
                    )
                }
                if (BuildConfig.DEBUG) {
                    ProviderLogger.diagnostic(
                        "[AtmosVolumeDiag] event=ludt_peak_association," +
                            "action=associated,elapsedMs=${elapsedRealtime()}," +
                            "format=${System.identityHashCode(format)}," +
                            "loudness=$copiedLoudness,truePeakDbfs=${associated.truePeakDbfs}," +
                            "samplePeakDbfs=${associated.samplePeakDbfs},ageMs=$ageMs," +
                            "fingerprint=$fingerprint,stableFingerprint=$stableFingerprint"
                    )
                }
            })
            ProviderLogger.info("Apple Music Format loudness/true-peak 关联 Hook 已安装")
        }.onFailure { error ->
            ProviderLogger.error("Apple Music Format loudness/true-peak 关联 Hook 安装失败", error)
        }
    }

    private fun installManifestFormatCopyHook() {
        runCatching {
            val resolved = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.ATMOS_FORMAT_COPY_WITH_MANIFEST_INFO
            )
            runtime.hookRegistrar.installHook(resolved.method, after = { chain, result ->
                val sourceFormat = chain.thisObject ?: return@installHook
                val resultFormat = result ?: return@installHook
                val sourceMetadata = metadataByFormat[sourceFormat]
                    ?: metadataForFormat(sourceFormat)
                    ?: return@installHook
                val propagated = sourceMetadata.copy(
                    associationSource = "manifest_format_copy",
                )
                val fingerprint = associateFormat(
                    format = resultFormat,
                    metadata = propagated,
                    target = resolved.target,
                )
                if (BuildConfig.DEBUG &&
                    manifestPropagationCallbackHit.compareAndSet(false, true)
                ) {
                    ProviderLogger.diagnostic(
                        "Dolby Atmos manifest Format true-peak 传播 Hook 首次命中：" +
                            "source=${System.identityHashCode(sourceFormat)}," +
                            "result=${System.identityHashCode(resultFormat)}," +
                            "truePeak=${propagated.truePeakDbfs}"
                    )
                }
                if (BuildConfig.DEBUG) {
                    ProviderLogger.diagnostic(
                        "[AtmosVolumeDiag] event=ludt_peak_propagation," +
                            "action=manifest_format_copy,elapsedMs=${elapsedRealtime()}," +
                            "sourceFormat=${System.identityHashCode(sourceFormat)}," +
                            "resultFormat=${System.identityHashCode(resultFormat)}," +
                            "truePeakDbfs=${propagated.truePeakDbfs}," +
                            "samplePeakDbfs=${propagated.samplePeakDbfs}," +
                            "fingerprint=$fingerprint"
                    )
                }
            })
            ProviderLogger.info("Apple Music manifest Format true-peak 传播 Hook 已安装")
        }.onFailure { error ->
            ProviderLogger.error("Apple Music manifest Format true-peak 传播 Hook 安装失败", error)
        }
    }

    private fun readTrackPeakMetadata(
        ludtData: Any,
        returnedLoudness: Float,
        target: AppleMusicHookTarget,
    ): AppleAtmosPeakMetadata? {
        val loudnessInfoArray = AppleReflection.field(
            ludtData,
            target.runtimeMemberName(
                AppleMusicRuntimeMember.ATMOS_LUDT_TRACK_LOUDNESS_INFO_FIELD
            ),
        ) as? Array<*> ?: return null
        val loudnessInfo = loudnessInfoArray.firstOrNull() ?: return null
        val parsedLoudness = (AppleReflection.field(
            loudnessInfo,
            target.runtimeMemberName(AppleMusicRuntimeMember.ATMOS_LUDT_LOUDNESS_FIELD),
        ) as? Float)?.takeIf(Float::isFinite) ?: returnedLoudness
        val truePeak = (AppleReflection.field(
            loudnessInfo,
            target.runtimeMemberName(AppleMusicRuntimeMember.ATMOS_LUDT_TRUE_PEAK_FIELD),
        ) as? Float)?.takeIf(Float::isFinite)
        val samplePeak = (AppleReflection.field(
            loudnessInfo,
            target.runtimeMemberName(AppleMusicRuntimeMember.ATMOS_LUDT_SAMPLE_PEAK_FIELD),
        ) as? Float)?.takeIf(Float::isFinite)
        return AppleAtmosPeakMetadata(
            loudness = parsedLoudness,
            truePeakDbfs = truePeak,
            samplePeakDbfs = samplePeak,
            associationSource = "ludt_track",
        )
    }

    private fun formatFingerprint(
        format: Any,
        target: AppleMusicHookTarget,
    ): AppleAtmosFormatFingerprint = AppleAtmosFormatFingerprint(
        id = AppleReflection.field(
            format,
            target.runtimeMemberName(AppleMusicRuntimeMember.ATMOS_FORMAT_ID_FIELD),
        ) as? String,
        codecs = AppleReflection.field(
            format,
            target.runtimeMemberName(AppleMusicRuntimeMember.ATMOS_FORMAT_CODECS_FIELD),
        ) as? String,
        sampleMimeType = AppleReflection.field(
            format,
            target.runtimeMemberName(
                AppleMusicRuntimeMember.ATMOS_FORMAT_SAMPLE_MIME_TYPE_FIELD
            ),
        ) as? String,
        loudnessBits = java.lang.Float.floatToIntBits(
            (AppleReflection.field(
                format,
                target.runtimeMemberName(AppleMusicRuntimeMember.ATMOS_FORMAT_LOUDNESS_FIELD),
            ) as? Float) ?: Float.NaN,
        ),
        channelCount = (AppleReflection.field(
            format,
            target.runtimeMemberName(AppleMusicRuntimeMember.ATMOS_FORMAT_CHANNEL_COUNT_FIELD),
        ) as? Int) ?: -1,
        sampleRate = (AppleReflection.field(
            format,
            target.runtimeMemberName(AppleMusicRuntimeMember.ATMOS_FORMAT_SAMPLE_RATE_FIELD),
        ) as? Int) ?: -1,
        bitrate = (AppleReflection.field(
            format,
            target.runtimeMemberName(AppleMusicRuntimeMember.ATMOS_FORMAT_BITRATE_FIELD),
        ) as? Int) ?: -1,
    )

    private fun associateFormat(
        format: Any,
        metadata: AppleAtmosPeakMetadata,
        target: AppleMusicHookTarget,
    ): AppleAtmosFormatFingerprint {
        metadataByFormat[format] = metadata
        val fingerprint = formatFingerprint(format, target)
        synchronized(metadataByFingerprint) {
            metadataByFingerprint[fingerprint] = metadata
            while (metadataByFingerprint.size > APPLE_ATMOS_FORMAT_METADATA_CAPACITY) {
                val oldestKey = metadataByFingerprint.entries.firstOrNull()?.key ?: break
                metadataByFingerprint.remove(oldestKey)
            }
        }
        val stableFingerprint = fingerprint.stable()
        synchronized(metadataByStableFingerprint) {
            metadataByStableFingerprint[stableFingerprint] = metadata
            while (metadataByStableFingerprint.size > APPLE_ATMOS_FORMAT_METADATA_CAPACITY) {
                val oldestKey = metadataByStableFingerprint.entries.firstOrNull()?.key ?: break
                metadataByStableFingerprint.remove(oldestKey)
            }
        }
        return fingerprint
    }

    private fun AppleAtmosFormatFingerprint.stable() = AppleAtmosStableFormatFingerprint(
        codecs = codecs,
        sampleMimeType = sampleMimeType,
        loudnessBits = loudnessBits,
        channelCount = channelCount,
        sampleRate = sampleRate,
    )

    private fun logLookup(
        format: Any,
        action: String,
        metadata: AppleAtmosPeakMetadata?,
    ) {
        if (!BuildConfig.DEBUG) return
        val fingerprint = runCatching { formatFingerprint(format, formatCopyTarget) }.getOrNull()
        ProviderLogger.diagnostic(
            "[AtmosVolumeDiag] event=ludt_peak_lookup,action=$action," +
                "elapsedMs=${elapsedRealtime()},format=${System.identityHashCode(format)}," +
                "truePeakDbfs=${metadata?.truePeakDbfs}," +
                "samplePeakDbfs=${metadata?.samplePeakDbfs}," +
                "fingerprint=$fingerprint,stableFingerprint=${fingerprint?.stable()}"
        )
    }

    private companion object {
        private const val APPLE_ATMOS_PENDING_PEAK_MAX_AGE_MS = 2_000L
        private const val APPLE_ATMOS_LOUDNESS_MATCH_TOLERANCE_LU = 0.05f
        private const val APPLE_ATMOS_FORMAT_METADATA_CAPACITY = 96
    }
}
