/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookTarget
import io.github.proify.lyricon.amprovider.xposed.AppleMusicProviderRuntime
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleReflection
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

internal data class ApplePcmChunkStats(
    val sumSquares: Double,
    val peak: Double,
    val sampledValues: Long,
)

internal data class ApplePcmLevelSnapshot(
    val rmsDbfs: Double,
    val peakDbfs: Double,
    val crestFactorDb: Double,
    val sampledValues: Long,
)

internal class ApplePcmLevelAccumulator {
    private var sumSquares = 0.0
    private var peak = 0.0
    private var sampledValues = 0L

    fun add(stats: ApplePcmChunkStats) {
        if (stats.sampledValues <= 0L) return
        sumSquares += stats.sumSquares
        peak = maxOf(peak, stats.peak)
        sampledValues += stats.sampledValues
    }

    fun addNormalizedSamples(vararg samples: Double) {
        samples.forEach { sample ->
            val bounded = sample.coerceIn(-1.0, 1.0)
            sumSquares += bounded * bounded
            peak = maxOf(peak, abs(bounded))
            sampledValues += 1L
        }
    }

    fun reset() {
        sumSquares = 0.0
        peak = 0.0
        sampledValues = 0L
    }

    fun snapshotAndReset(): ApplePcmLevelSnapshot? {
        if (sampledValues <= 0L) return null
        val rms = sqrt(sumSquares / sampledValues)
        val rmsDbfs = amplitudeToDbfs(rms)
        val peakDbfs = amplitudeToDbfs(peak)
        return ApplePcmLevelSnapshot(
            rmsDbfs = rmsDbfs,
            peakDbfs = peakDbfs,
            crestFactorDb = peakDbfs - rmsDbfs,
            sampledValues = sampledValues,
        ).also { reset() }
    }

    private fun amplitudeToDbfs(amplitude: Double): Double =
        if (amplitude <= 0.0) PCM_SILENCE_DBFS else 20.0 * log10(amplitude)

    private companion object {
        const val PCM_SILENCE_DBFS = -120.0
    }
}

internal fun shouldRecordFirstRendererOutput(
    pendingFormat: Boolean,
    outputHandled: Boolean,
    decodeOnly: Boolean,
): Boolean = pendingFormat && outputHandled && !decodeOnly

/**
 * Debug-only timing trace for distinguishing Apple Music's two audio renderers, periods,
 * sessions, input formats, and the first output buffer that actually reaches AudioSink.
 */
internal class AppleAtmosVolumeDiagnostics(
    private val runtime: AppleMusicProviderRuntime,
) {
    private data class FormatSnapshot(
        val codecs: String?,
        val sampleMimeType: String?,
        val loudness: Float,
        val channelCount: Int,
        val sampleRate: Int,
        val bitrate: Int,
    ) {
        val signature: String = listOf(
            codecs,
            sampleMimeType,
            loudness,
            channelCount,
            sampleRate,
            bitrate,
        ).joinToString("|")

        override fun toString(): String =
            "codecs=$codecs,mime=$sampleMimeType,loudness=$loudness," +
                "channels=$channelCount,sampleRate=$sampleRate,bitrate=$bitrate"
    }

    private data class RendererState(
        val kind: String,
        var periodId: Long = 0L,
        var audioSessionId: Int = 0,
        var format: FormatSnapshot? = null,
        var formatChangedAtMs: Long = 0L,
        var pendingFirstOutput: Boolean = false,
    )

    private data class PcmWindowState(
        val accumulator: ApplePcmLevelAccumulator = ApplePcmLevelAccumulator(),
        var startedAtMs: Long = SystemClock.elapsedRealtime(),
    )

    private val pcmWindowStates = WeakIdentityMap<AudioTrack, PcmWindowState>()
    private val rendererStates = WeakIdentityMap<Any, RendererState>()
    private lateinit var mediaCodecRendererClass: Class<*>

    fun installHooks() {
        if (!BuildConfig.DEBUG) return
        installMediaCodecRendererHooks()
        installSvAudioRendererHooks()
        installAudioTrackPcmHooks()
        ProviderLogger.diagnostic("[AtmosVolumeDiag] event=diagnostic_hooks_installed")
    }

    private fun installMediaCodecRendererHooks() {
        val periodTarget = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_PERIOD_ID
        )
        runtime.hookRegistrar.installHook(periodTarget.method, after = { chain, _ ->
            val renderer = chain.thisObject ?: return@installHook
            val periodId = chain.args.getOrNull(1) as? Long ?: return@installHook
            onPeriodId(renderer, MEDIA_CODEC_KIND, periodId)
        })

        val inputFormatTarget = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_INPUT_FORMAT
        )
        mediaCodecRendererClass = inputFormatTarget.method.declaringClass
        runtime.hookRegistrar.installHook(inputFormatTarget.method, after = { chain, _ ->
            val renderer = chain.thisObject ?: return@installHook
            val holder = chain.args.firstOrNull() ?: return@installHook
            val format = runCatching {
                AppleReflection.field(
                    holder,
                    inputFormatTarget.target.runtimeMemberName(
                        AppleMusicRuntimeMember.DEBUG_FORMAT_HOLDER_FORMAT_FIELD
                    ),
                )
            }.getOrNull() ?: return@installHook
            onFormatChanged(
                renderer = renderer,
                kind = MEDIA_CODEC_KIND,
                format = format,
                target = inputFormatTarget.target,
                extra = "source=onInputFormatChanged",
            )
        })

        val sessionTarget = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_AUDIO_SESSION
        )
        runtime.hookRegistrar.installHook(sessionTarget.method, after = { chain, _ ->
            val renderer = chain.thisObject?.takeIf(mediaCodecRendererClass::isInstance)
                ?: return@installHook
            val sessionId = chain.args.firstOrNull() as? Int ?: return@installHook
            onAudioSessionId(renderer, MEDIA_CODEC_KIND, sessionId)
        })

        val outputTarget = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_OUTPUT_BUFFER
        )
        runtime.hookRegistrar.installHook(outputTarget.method, after = { chain, result ->
            val renderer = chain.thisObject?.takeIf(mediaCodecRendererClass::isInstance)
                ?: return@installHook
            val outputHandled = result as? Boolean == true
            val decodeOnly = chain.args.getOrNull(7) as? Boolean == true
            val positionUs = chain.args.firstOrNull() as? Long
            val elapsedRealtimeUs = chain.args.getOrNull(1) as? Long
            val bufferPresentationTimeUs = chain.args.getOrNull(6) as? Long
            onMediaCodecOutputBuffer(
                renderer = renderer,
                outputHandled = outputHandled,
                decodeOnly = decodeOnly,
                positionUs = positionUs,
                elapsedRealtimeUs = elapsedRealtimeUs,
                bufferPresentationTimeUs = bufferPresentationTimeUs,
            )
        })
    }

    private fun installSvAudioRendererHooks() {
        val periodTarget = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_PERIOD_ID
        )
        runtime.hookRegistrar.installHook(periodTarget.method, after = { chain, _ ->
            val renderer = chain.thisObject ?: return@installHook
            val periodId = chain.args.getOrNull(1) as? Long ?: return@installHook
            onPeriodId(renderer, SV_AUDIO_KIND, periodId)
        })

        val streamTarget = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_STREAM_CHANGED
        )
        runtime.hookRegistrar.installHook(streamTarget.method, after = { chain, _ ->
            val renderer = chain.thisObject ?: return@installHook
            val formats = chain.args.firstOrNull() as? Array<*> ?: return@installHook
            val format = formats.firstOrNull() ?: return@installHook
            val streamOffsetUs = chain.args.getOrNull(1) as? Long
            onFormatChanged(
                renderer = renderer,
                kind = SV_AUDIO_KIND,
                format = format,
                target = streamTarget.target,
                extra = "source=onStreamChanged,streamOffsetUs=$streamOffsetUs",
            )
        })

        val sessionTarget = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_SESSION
        )
        runtime.hookRegistrar.installHook(sessionTarget.method, after = { chain, _ ->
            val renderer = chain.thisObject ?: return@installHook
            val sessionId = chain.args.firstOrNull() as? Int ?: return@installHook
            onAudioSessionId(renderer, SV_AUDIO_KIND, sessionId)
        })

        val firstBufferTarget = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_FIRST_BUFFER
        )
        runtime.hookRegistrar.installHook(firstBufferTarget.method, after = { chain, _ ->
            val renderer = chain.thisObject ?: return@installHook
            onSvAudioFirstBuffer(renderer)
        })
    }

    private fun installAudioTrackPcmHooks() {
        val intType = Int::class.javaPrimitiveType ?: return
        val longType = Long::class.javaPrimitiveType ?: return
        val byteBufferClass = ByteBuffer::class.java

        listOf(
            AudioTrack::class.java.getDeclaredMethod(
                "write",
                byteBufferClass,
                intType,
                intType,
            ) to false,
            AudioTrack::class.java.getDeclaredMethod(
                "write",
                byteBufferClass,
                intType,
                intType,
                longType,
            ) to false,
        ).forEach { (method, _) ->
            runtime.hookRegistrar.installHook(method, before = { chain ->
                val audioTrack = chain.thisObject as? AudioTrack ?: return@installHook
                val buffer = chain.args.firstOrNull() as? ByteBuffer ?: return@installHook
                val sizeInBytes = chain.args.getOrNull(1) as? Int ?: return@installHook
                sampleByteBuffer(audioTrack, buffer, sizeInBytes)
            })
        }

        val floatWrite = AudioTrack::class.java.getDeclaredMethod(
            "write",
            FloatArray::class.java,
            intType,
            intType,
            intType,
        )
        runtime.hookRegistrar.installHook(floatWrite, before = { chain ->
            val audioTrack = chain.thisObject as? AudioTrack ?: return@installHook
            val samples = chain.args.firstOrNull() as? FloatArray ?: return@installHook
            val offset = chain.args.getOrNull(1) as? Int ?: return@installHook
            val size = chain.args.getOrNull(2) as? Int ?: return@installHook
            sampleFloatArray(audioTrack, samples, offset, size)
        })

        val shortWrite = AudioTrack::class.java.getDeclaredMethod(
            "write",
            ShortArray::class.java,
            intType,
            intType,
            intType,
        )
        runtime.hookRegistrar.installHook(shortWrite, before = { chain ->
            val audioTrack = chain.thisObject as? AudioTrack ?: return@installHook
            val samples = chain.args.firstOrNull() as? ShortArray ?: return@installHook
            val offset = chain.args.getOrNull(1) as? Int ?: return@installHook
            val size = chain.args.getOrNull(2) as? Int ?: return@installHook
            sampleShortArray(audioTrack, samples, offset, size)
        })

        ProviderLogger.diagnostic("[AtmosVolumeDiag] event=pcm_hooks_installed")
    }

    private fun sampleByteBuffer(
        audioTrack: AudioTrack,
        source: ByteBuffer,
        requestedBytes: Int,
    ) {
        val encoding = runCatching(audioTrack::getAudioFormat).getOrDefault(0)
        val buffer = source.duplicate().order(ByteOrder.nativeOrder())
        val availableBytes = requestedBytes.coerceAtLeast(0).coerceAtMost(buffer.remaining())
        val channelCount = runCatching(audioTrack::getChannelCount).getOrDefault(1).coerceAtLeast(1)
        val frameStride = channelCount * PCM_FRAME_SAMPLE_STRIDE
        val stats = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val sampleCount = availableBytes / Float.SIZE_BYTES
                accumulatePcmSamples(sampleCount, frameStride) { sampleIndex ->
                    buffer.getFloat(buffer.position() + sampleIndex * Float.SIZE_BYTES).toDouble()
                }
            }
            AudioFormat.ENCODING_PCM_16BIT -> {
                val sampleCount = availableBytes / Short.SIZE_BYTES
                accumulatePcmSamples(sampleCount, frameStride) { sampleIndex ->
                    buffer.getShort(buffer.position() + sampleIndex * Short.SIZE_BYTES) / 32768.0
                }
            }
            else -> null
        }
        stats?.let { onPcmChunk(audioTrack, it, encoding) }
    }

    private fun sampleFloatArray(
        audioTrack: AudioTrack,
        source: FloatArray,
        offset: Int,
        requestedSamples: Int,
    ) {
        val start = offset.coerceIn(0, source.size)
        val sampleCount = requestedSamples.coerceAtLeast(0).coerceAtMost(source.size - start)
        val channelCount = runCatching(audioTrack::getChannelCount).getOrDefault(1).coerceAtLeast(1)
        accumulatePcmSamples(sampleCount, channelCount * PCM_FRAME_SAMPLE_STRIDE) { index ->
            source[start + index].toDouble()
        }?.let { onPcmChunk(audioTrack, it, AudioFormat.ENCODING_PCM_FLOAT) }
    }

    private fun sampleShortArray(
        audioTrack: AudioTrack,
        source: ShortArray,
        offset: Int,
        requestedSamples: Int,
    ) {
        val start = offset.coerceIn(0, source.size)
        val sampleCount = requestedSamples.coerceAtLeast(0).coerceAtMost(source.size - start)
        val channelCount = runCatching(audioTrack::getChannelCount).getOrDefault(1).coerceAtLeast(1)
        accumulatePcmSamples(sampleCount, channelCount * PCM_FRAME_SAMPLE_STRIDE) { index ->
            source[start + index] / 32768.0
        }?.let { onPcmChunk(audioTrack, it, AudioFormat.ENCODING_PCM_16BIT) }
    }

    private inline fun accumulatePcmSamples(
        sampleCount: Int,
        frameStride: Int,
        sampleAt: (Int) -> Double,
    ): ApplePcmChunkStats? {
        if (sampleCount <= 0) return null
        var sumSquares = 0.0
        var peak = 0.0
        var sampledValues = 0L
        var frameStart = 0
        while (frameStart < sampleCount) {
            val frameEnd = minOf(frameStart + frameStride / PCM_FRAME_SAMPLE_STRIDE, sampleCount)
            var sampleIndex = frameStart
            while (sampleIndex < frameEnd) {
                val sample = sampleAt(sampleIndex).coerceIn(-1.0, 1.0)
                sumSquares += sample * sample
                peak = maxOf(peak, abs(sample))
                sampledValues += 1L
                sampleIndex += 1
            }
            frameStart += frameStride
        }
        return ApplePcmChunkStats(sumSquares, peak, sampledValues)
    }

    @Synchronized
    private fun onPcmChunk(
        audioTrack: AudioTrack,
        stats: ApplePcmChunkStats,
        encoding: Int,
    ) {
        val state = pcmWindowStates[audioTrack] ?: PcmWindowState().also {
            pcmWindowStates[audioTrack] = it
        }
        state.accumulator.add(stats)
        val now = SystemClock.elapsedRealtime()
        val windowMs = now - state.startedAtMs
        if (windowMs < PCM_LOG_WINDOW_MS) return
        val snapshot = state.accumulator.snapshotAndReset() ?: return
        state.startedAtMs = now
        ProviderLogger.diagnostic(
            "[AtmosVolumeDiag] event=pcm_window,elapsedMs=$now," +
                "track=${System.identityHashCode(audioTrack)}," +
                "sessionId=${runCatching(audioTrack::getAudioSessionId).getOrDefault(0)}," +
                "routeType=${runCatching { audioTrack.routedDevice?.type }.getOrNull()}," +
                "encoding=$encoding," +
                "channels=${runCatching(audioTrack::getChannelCount).getOrDefault(-1)}," +
                "sampleRate=${runCatching(audioTrack::getSampleRate).getOrDefault(-1)}," +
                "windowMs=$windowMs,rmsDbfs=${snapshot.rmsDbfs}," +
                "peakDbfs=${snapshot.peakDbfs}," +
                "crestFactorDb=${snapshot.crestFactorDb}," +
                "sampledValues=${snapshot.sampledValues}"
        )
    }

    @Synchronized
    private fun onPeriodId(renderer: Any, kind: String, periodId: Long) {
        val state = stateFor(renderer, kind)
        state.periodId = periodId
        log("period", renderer, state)
    }

    @Synchronized
    private fun onAudioSessionId(renderer: Any, kind: String, sessionId: Int) {
        val state = stateFor(renderer, kind)
        state.audioSessionId = sessionId
        log("renderer_session", renderer, state)
    }

    @Synchronized
    private fun onFormatChanged(
        renderer: Any,
        kind: String,
        format: Any,
        target: AppleMusicHookTarget,
        extra: String,
    ) {
        val state = stateFor(renderer, kind)
        state.format = formatSnapshot(format, target)
        state.formatChangedAtMs = SystemClock.elapsedRealtime()
        state.pendingFirstOutput = true
        log("renderer_format", renderer, state, extra)
    }

    @Synchronized
    private fun onMediaCodecOutputBuffer(
        renderer: Any,
        outputHandled: Boolean,
        decodeOnly: Boolean,
        positionUs: Long?,
        elapsedRealtimeUs: Long?,
        bufferPresentationTimeUs: Long?,
    ) {
        val state = stateFor(renderer, MEDIA_CODEC_KIND)
        if (!shouldRecordFirstRendererOutput(
                pendingFormat = state.pendingFirstOutput,
                outputHandled = outputHandled,
                decodeOnly = decodeOnly,
            )
        ) {
            return
        }
        state.pendingFirstOutput = false
        log(
            event = "first_output_buffer",
            renderer = renderer,
            state = state,
            extra = "positionUs=$positionUs,elapsedRealtimeUs=$elapsedRealtimeUs," +
                "bufferPresentationTimeUs=$bufferPresentationTimeUs," +
                "sinceFormatMs=${elapsedSinceFormat(state)}",
        )
    }

    @Synchronized
    private fun onSvAudioFirstBuffer(renderer: Any) {
        val state = stateFor(renderer, SV_AUDIO_KIND)
        if (!state.pendingFirstOutput) return
        state.pendingFirstOutput = false
        log(
            event = "first_output_buffer",
            renderer = renderer,
            state = state,
            extra = "sinceFormatMs=${elapsedSinceFormat(state)}",
        )
    }

    private fun stateFor(renderer: Any, kind: String): RendererState =
        rendererStates[renderer] ?: RendererState(kind).also { rendererStates[renderer] = it }

    private fun elapsedSinceFormat(state: RendererState): Long? =
        state.formatChangedAtMs.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it }

    private fun formatSnapshot(format: Any, target: AppleMusicHookTarget): FormatSnapshot =
        FormatSnapshot(
            codecs = formatField(format, target, AppleMusicRuntimeMember.DEBUG_FORMAT_CODECS_FIELD)
                as? String,
            sampleMimeType = formatField(
                format,
                target,
                AppleMusicRuntimeMember.DEBUG_FORMAT_SAMPLE_MIME_TYPE_FIELD,
            ) as? String,
            loudness = (formatField(
                format,
                target,
                AppleMusicRuntimeMember.DEBUG_FORMAT_LOUDNESS_FIELD,
            ) as? Float) ?: Float.NaN,
            channelCount = (formatField(
                format,
                target,
                AppleMusicRuntimeMember.DEBUG_FORMAT_CHANNEL_COUNT_FIELD,
            ) as? Int) ?: -1,
            sampleRate = (formatField(
                format,
                target,
                AppleMusicRuntimeMember.DEBUG_FORMAT_SAMPLE_RATE_FIELD,
            ) as? Int) ?: -1,
            bitrate = (formatField(
                format,
                target,
                AppleMusicRuntimeMember.DEBUG_FORMAT_BITRATE_FIELD,
            ) as? Int) ?: -1,
        )

    private fun formatField(
        format: Any,
        target: AppleMusicHookTarget,
        member: AppleMusicRuntimeMember,
    ): Any? = runCatching {
        AppleReflection.field(format, target.runtimeMemberName(member))
    }.getOrNull()

    private fun log(
        event: String,
        renderer: Any,
        state: RendererState,
        extra: String? = null,
    ) {
        val suffix = extra?.let { ",$it" }.orEmpty()
        ProviderLogger.diagnostic(
            "[AtmosVolumeDiag] event=$event,elapsedMs=${SystemClock.elapsedRealtime()}," +
                "thread=${Thread.currentThread().name},kind=${state.kind}," +
                "renderer=${renderer.javaClass.name}@${System.identityHashCode(renderer)}," +
                "periodId=${state.periodId},sessionId=${state.audioSessionId}," +
                "format=${state.format}$suffix"
        )
    }

    private companion object {
        const val MEDIA_CODEC_KIND = "media_codec"
        const val SV_AUDIO_KIND = "sv_audio"
        const val PCM_LOG_WINDOW_MS = 5_000L
        const val PCM_FRAME_SAMPLE_STRIDE = 8
    }
}
