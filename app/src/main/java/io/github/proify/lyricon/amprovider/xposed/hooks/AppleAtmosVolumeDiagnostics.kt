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

    private val rendererStates = WeakIdentityMap<Any, RendererState>()
    private lateinit var mediaCodecRendererClass: Class<*>

    fun installHooks() {
        if (!BuildConfig.DEBUG) return
        installMediaCodecRendererHooks()
        installSvAudioRendererHooks()
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
    }
}
