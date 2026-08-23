/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleAtmosVolumeProcessorTest {
    @Test
    fun `metadata loudness drives static Atmos gain toward minus 16 LUFS`() {
        assertEquals(2f, resolveAppleAtmosGain(-18f).inputGainDb, 0.001f)
        assertEquals(1.25f, resolveAppleAtmosGain(-17.25f).inputGainDb, 0.001f)
        assertEquals(3f, resolveAppleAtmosGain(-19f).inputGainDb, 0.001f)
        assertEquals(
            APPLE_ATMOS_UNKNOWN_PEAK_MAX_GAIN_DB,
            resolveAppleAtmosGain(-30f).inputGainDb,
            0.001f,
        )
        assertFalse(resolveAppleAtmosGain(-18f).fallback)
    }

    @Test
    fun `true peak caps requested gain at limiter headroom`() {
        val unrestricted = resolveAppleAtmosGain(
            loudness = -19.5f,
            peakMetadata = peakMetadata(-19.5f, truePeak = -6f),
        )
        val limited = resolveAppleAtmosGain(
            loudness = -19.5f,
            peakMetadata = peakMetadata(-19.5f, truePeak = -3f),
        )
        val samplePeakFallback = resolveAppleAtmosGain(
            loudness = -20.25f,
            peakMetadata = peakMetadata(-20.25f, truePeak = null, samplePeak = -5f),
        )

        assertEquals(3.5f, unrestricted.requestedInputGainDb, 0.001f)
        assertEquals(3.5f, unrestricted.inputGainDb, 0.001f)
        assertFalse(unrestricted.peakLimited)
        assertEquals("true_peak", unrestricted.peakSource)

        assertEquals(2f, limited.inputGainDb, 0.001f)
        assertEquals(2f, limited.peakHeadroomDb ?: Float.NaN, 0.001f)
        assertTrue(limited.peakLimited)

        assertEquals(4f, samplePeakFallback.inputGainDb, 0.001f)
        assertEquals("sample_peak", samplePeakFallback.peakSource)
    }

    @Test
    fun `mismatched or missing peak metadata uses conservative cap`() {
        val mismatched = resolveAppleAtmosGain(
            loudness = -22f,
            peakMetadata = peakMetadata(-18f, truePeak = -10f),
        )
        val missingLoudness = resolveAppleAtmosGain(Float.NaN)

        assertEquals(APPLE_ATMOS_UNKNOWN_PEAK_MAX_GAIN_DB, mismatched.inputGainDb, 0.001f)
        assertEquals("missing", mismatched.peakSource)
        assertEquals(APPLE_ATMOS_UNKNOWN_PEAK_MAX_GAIN_DB, missingLoudness.inputGainDb, 0.001f)
        assertTrue(missingLoudness.fallback)
        assertEquals(null, missingLoudness.metadataLoudness)
    }

    @Test
    fun `only built in speaker device types are eligible`() {
        assertEquals(
            AppleAtmosOutputRoute.BUILT_IN_SPEAKER,
            resolveAppleAtmosOutputRoute(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
        )
        assertEquals(
            AppleAtmosOutputRoute.BUILT_IN_SPEAKER,
            resolveAppleAtmosOutputRoute(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE),
        )
        listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        ).forEach { deviceType ->
            assertEquals(
                AppleAtmosOutputRoute.NON_SPEAKER,
                resolveAppleAtmosOutputRoute(deviceType),
            )
        }
        assertEquals(AppleAtmosOutputRoute.UNKNOWN, resolveAppleAtmosOutputRoute(null))
        assertEquals(
            AppleAtmosOutputRoute.UNKNOWN,
            resolveAppleAtmosOutputRoute(AudioDeviceInfo.TYPE_UNKNOWN),
        )
    }

    @Test
    fun `active Atmos period creates DynamicsProcessing for its real session`() {
        val creations = mutableListOf<Creation>()
        val player = Any()
        val processor = processor(creations)

        processor.onAudioSessionId(player, 81)
        processor.onAudioVariantChanged(player, 4, 1001L, -18f, 6)
        processor.onSpeakerTrackPlayed(81, 501)
        processor.onPlayerActivated(player)

        assertEquals(1, creations.size)
        assertEquals(81, creations.single().sessionId)
        assertEquals(6, creations.single().channelCount)
        assertEquals(2f, creations.single().decision.inputGainDb, 0.001f)
        assertTrue(creations.single().effect.enabledValue)
    }

    @Test
    fun `late true peak metadata updates active session with a short static ramp`() {
        val creations = mutableListOf<Creation>()
        val player = Any()
        val processor = processor(creations)

        processor.onAudioSessionId(player, 81)
        processor.onAudioVariantChanged(player, 4, 1001L, -20f, 6)
        processor.onSpeakerTrackPlayed(81, 501)
        processor.onPlayerActivated(player)
        assertEquals(4f, creations.single().effect.inputGainValues.last(), 0.001f)

        processor.onAudioVariantChanged(
            player = player,
            audioVariant = 4,
            periodId = 1001L,
            loudness = -20f,
            channelCount = 6,
            peakMetadata = peakMetadata(-20f, truePeak = -3f),
        )

        assertEquals(1, creations.size)
        assertFalse(creations.single().effect.released)
        assertEquals(2f, creations.single().effect.inputGainValues.last(), 0.001f)
    }

    @Test
    fun `live preference disable releases effect and reenable reapplies it`() {
        val creations = mutableListOf<Creation>()
        val player = Any()
        var enabled = true
        val processor = AppleAtmosVolumeProcessor(
            preferenceEnabled = { enabled },
            effectFactory = { sessionId, channelCount, decision, initialInputGainDb ->
                FakeDynamicsEffect(initialInputGainDb).also { effect ->
                    creations += Creation(
                        sessionId,
                        channelCount,
                        decision,
                        initialInputGainDb,
                        effect,
                    )
                }
            },
            scheduleDelayed = { _, action -> action() },
        )

        processor.onAudioSessionId(player, 81)
        processor.onAudioVariantChanged(player, 4, 1001L, -18f, 6)
        processor.onSpeakerTrackPlayed(81, 501)
        processor.onPlayerActivated(player)
        assertEquals(1, creations.size)

        enabled = false
        processor.onPreferenceChanged()
        assertTrue(creations.single().effect.released)

        enabled = true
        processor.onPreferenceChanged()
        assertEquals(2, creations.size)
        assertTrue(creations.last().effect.enabledValue)
    }

    @Test
    fun `non Atmos clears stale session before later Atmos period`() {
        val creations = mutableListOf<Creation>()
        val player = Any()
        val processor = processor(creations)

        processor.onAudioSessionId(player, 81)
        processor.onAudioVariantChanged(player, 4, 1001L, -18f, 6)
        processor.onSpeakerTrackPlayed(81, 501)
        processor.onPlayerActivated(player)
        assertEquals(listOf(81), creations.map(Creation::sessionId))

        processor.onAudioVariantChanged(player, 2, 2001L, -7.5f, 2)
        assertTrue(creations.single().effect.released)

        processor.onAudioVariantChanged(player, 4, 3001L, -18f, 6)
        assertEquals(1, creations.size)

        processor.onSpeakerTrackPlayed(82, 502)
        processor.onAudioSessionId(player, 82)
        assertEquals(listOf(81, 82), creations.map(Creation::sessionId))
    }

    @Test
    fun `headphone and unknown routes never create DynamicsProcessing`() {
        listOf<Int?>(
            null,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        ).forEachIndexed { index, deviceType ->
            val creations = mutableListOf<Creation>()
            val player = Any()
            val processor = processor(creations)
            processor.onAudioSessionId(player, 81)
            processor.onAudioVariantChanged(player, 4, 1001L, -18f, 6)
            processor.onAudioTrackPlayed(81, 600 + index, deviceType)
            processor.onPlayerActivated(player)
            assertTrue("deviceType=$deviceType", creations.isEmpty())
        }
    }

    @Test
    fun `speaker to Bluetooth releases and Bluetooth to speaker reapplies`() {
        val creations = mutableListOf<Creation>()
        val player = Any()
        val processor = processor(creations)

        processor.onAudioSessionId(player, 81)
        processor.onAudioVariantChanged(player, 4, 1001L, -18f, 6)
        processor.onSpeakerTrackPlayed(81, 501)
        processor.onPlayerActivated(player)
        assertEquals(1, creations.size)

        processor.onAudioTrackRouteChanged(
            audioSessionId = 81,
            trackIdentity = 501,
            routedDeviceType = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        )
        assertTrue(creations.first().effect.released)

        processor.onAudioTrackRouteChanged(
            audioSessionId = 81,
            trackIdentity = 501,
            routedDeviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        assertEquals(2, creations.size)
        assertTrue(creations.last().effect.enabledValue)
    }

    @Test
    fun `same period upgrade waits for old AudioTrack stop then ramps current session`() {
        val creations = mutableListOf<Creation>()
        val player = Any()
        val processor = processor(creations)

        processor.onAudioSessionId(player, 81)
        processor.onAudioVariantChanged(player, 2, 1001L, -8f, 2)
        processor.onSpeakerTrackPlayed(81, 501)
        processor.onPlayerActivated(player)
        assertTrue(creations.isEmpty())

        processor.onAudioVariantChanged(player, 4, 1001L, -18f, 6)
        processor.onSpeakerTrackPlayed(81, 502)
        assertTrue(creations.isEmpty())

        processor.onAudioTrackStopped(81, 501, "stop")

        assertEquals(1, creations.size)
        assertEquals(81, creations.single().sessionId)
        assertEquals(0f, creations.single().initialInputGainDb, 0.001f)
        assertEquals(2f, creations.single().effect.inputGainValues.last(), 0.001f)
        assertTrue(creations.single().effect.enabledValue)
    }

    @Test
    fun `fresh session reported during non Atmos period is retained for same period upgrade`() {
        val creations = mutableListOf<Creation>()
        val player = Any()
        val processor = processor(creations)

        processor.onAudioSessionId(player, 81)
        processor.onAudioVariantChanged(player, 4, 1001L, -18f, 6)
        processor.onSpeakerTrackPlayed(81, 501)
        processor.onPlayerActivated(player)

        processor.onAudioVariantChanged(player, 2, 1002L, -8f, 2)
        processor.onSpeakerTrackPlayed(82, 502)
        processor.onAudioSessionId(player, 82)
        processor.onAudioVariantChanged(player, 4, 1002L, -19f, 6)
        assertEquals(listOf(81), creations.map(Creation::sessionId))

        processor.onAudioTrackStopped(82, 502, "stop")
        processor.onSpeakerTrackPlayed(82, 503)

        assertEquals(listOf(81, 82), creations.map(Creation::sessionId))
        assertEquals(3f, creations.last().decision.inputGainDb, 0.001f)
    }

    @Test
    fun `cross period non Atmos to Atmos still waits for fresh session`() {
        val creations = mutableListOf<Creation>()
        val player = Any()
        val processor = processor(creations)

        processor.onAudioSessionId(player, 81)
        processor.onAudioVariantChanged(player, 2, 1001L, -8f, 2)
        processor.onPlayerActivated(player)

        processor.onAudioVariantChanged(player, 4, 1002L, -18f, 6)
        assertTrue(creations.isEmpty())

        processor.onSpeakerTrackPlayed(82, 502)
        processor.onAudioSessionId(player, 82)
        assertEquals(listOf(82), creations.map(Creation::sessionId))
    }

    @Test
    fun `zero period callback keeps current Atmos period and active effect`() {
        val creations = mutableListOf<Creation>()
        val player = Any()
        val processor = processor(creations)

        processor.onAudioSessionId(player, 81)
        processor.onAudioVariantChanged(player, 4, 1001L, -18f, 6)
        processor.onSpeakerTrackPlayed(81, 501)
        processor.onPlayerActivated(player)

        processor.onAudioVariantChanged(player, 4, 0L, -17.75f, 6)

        assertEquals(1, creations.size)
        assertFalse(creations.single().effect.released)
    }

    @Test
    fun `new Atmos period waits for its new session`() {
        val creations = mutableListOf<Creation>()
        val player = Any()
        val processor = processor(creations)

        processor.onAudioSessionId(player, 81)
        processor.onAudioVariantChanged(player, 4, 1001L, -17.25f, 6)
        processor.onSpeakerTrackPlayed(81, 501)
        processor.onPlayerActivated(player)

        processor.onAudioVariantChanged(player, 4, 1002L, -19f, 6)
        assertEquals(1, creations.size)
        assertFalse(creations.single().effect.released)

        processor.onSpeakerTrackPlayed(82, 502)
        processor.onAudioSessionId(player, 82)
        assertTrue(creations.first().effect.released)
        assertEquals(3f, creations.last().decision.inputGainDb, 0.001f)
    }

    private fun AppleAtmosVolumeProcessor.onSpeakerTrackPlayed(
        audioSessionId: Int,
        trackIdentity: Int,
    ) {
        onAudioTrackPlayed(
            audioSessionId = audioSessionId,
            trackIdentity = trackIdentity,
            routedDeviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
    }

    private fun peakMetadata(
        loudness: Float,
        truePeak: Float?,
        samplePeak: Float? = null,
    ) = AppleAtmosPeakMetadata(
        loudness = loudness,
        truePeakDbfs = truePeak,
        samplePeakDbfs = samplePeak,
        associationSource = "test",
    )

    private fun processor(creations: MutableList<Creation>) = AppleAtmosVolumeProcessor(
        preferenceEnabled = { true },
        effectFactory = { sessionId, channelCount, decision, initialInputGainDb ->
            FakeDynamicsEffect(initialInputGainDb).also { effect ->
                creations += Creation(
                    sessionId,
                    channelCount,
                    decision,
                    initialInputGainDb,
                    effect,
                )
            }
        },
        scheduleDelayed = { _, action -> action() },
    )

    private data class Creation(
        val sessionId: Int,
        val channelCount: Int,
        val decision: AppleAtmosGainDecision,
        val initialInputGainDb: Float,
        val effect: FakeDynamicsEffect,
    )

    private class FakeDynamicsEffect(initialInputGainDb: Float) : AppleSessionDynamicsEffect {
        var enabledValue = false
        var released = false
        val inputGainValues = mutableListOf(initialInputGainDb)

        override fun setEnabled(enabled: Boolean) {
            this.enabledValue = enabled
        }

        override fun setInputGainDb(inputGainDb: Float) {
            inputGainValues += inputGainDb
        }

        override fun release() {
            released = true
        }
    }
}
