/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleAtmosVolumeProcessorTest {
    @Test
    fun `metadata loudness drives bounded Atmos input gain`() {
        assertEquals(6.5f, resolveAppleAtmosGain(-18f).inputGainDb, 0.001f)
        assertEquals(5.75f, resolveAppleAtmosGain(-17.25f).inputGainDb, 0.001f)
        assertEquals(7.5f, resolveAppleAtmosGain(-19f).inputGainDb, 0.001f)
        assertEquals(APPLE_ATMOS_MAX_INPUT_GAIN_DB, resolveAppleAtmosGain(-30f).inputGainDb)
        assertFalse(resolveAppleAtmosGain(-18f).fallback)
    }

    @Test
    fun `missing loudness uses explicit bounded fallback`() {
        val decision = resolveAppleAtmosGain(Float.NaN)
        assertEquals(APPLE_ATMOS_FALLBACK_GAIN_DB, decision.inputGainDb)
        assertTrue(decision.fallback)
        assertEquals(null, decision.metadataLoudness)
    }

    @Test
    fun `active Atmos period creates DynamicsProcessing for its real session`() {
        val creations = mutableListOf<Creation>()
        val player = Any()
        val processor = processor(creations)

        processor.onAudioSessionId(player, 81)
        processor.onAudioVariantChanged(player, 4, 1001L, -18f, 6)
        processor.onPlayerActivated(player)

        assertEquals(1, creations.size)
        assertEquals(81, creations.single().sessionId)
        assertEquals(6, creations.single().channelCount)
        assertEquals(6.5f, creations.single().decision.inputGainDb, 0.001f)
        assertTrue(creations.single().effect.enabledValue)
    }

    @Test
    fun `non Atmos clears stale session before later Atmos period`() {
        val creations = mutableListOf<Creation>()
        val player = Any()
        val processor = processor(creations)

        processor.onAudioSessionId(player, 81)
        processor.onAudioVariantChanged(player, 4, 1001L, -18f, 6)
        processor.onPlayerActivated(player)
        assertEquals(listOf(81), creations.map(Creation::sessionId))

        processor.onAudioVariantChanged(player, 2, 2001L, -7.5f, 2)
        assertTrue(creations.single().effect.released)

        processor.onAudioVariantChanged(player, 4, 3001L, -18f, 6)
        assertEquals(1, creations.size)

        processor.onAudioSessionId(player, 82)
        assertEquals(listOf(81, 82), creations.map(Creation::sessionId))
    }

    @Test
    fun `same period upgrade waits for old AudioTrack stop then ramps current session`() {
        val creations = mutableListOf<Creation>()
        val player = Any()
        val processor = processor(creations)

        processor.onAudioSessionId(player, 81)
        processor.onAudioVariantChanged(player, 2, 1001L, -8f, 2)
        processor.onAudioTrackPlayed(81, 501)
        processor.onPlayerActivated(player)
        assertTrue(creations.isEmpty())

        processor.onAudioVariantChanged(player, 4, 1001L, -18f, 6)
        processor.onAudioTrackPlayed(81, 502)
        assertTrue(creations.isEmpty())

        processor.onAudioTrackStopped(81, 501, "stop")

        assertEquals(1, creations.size)
        assertEquals(81, creations.single().sessionId)
        assertEquals(0f, creations.single().initialInputGainDb, 0.001f)
        assertEquals(6.5f, creations.single().effect.inputGainValues.last(), 0.001f)
        assertTrue(creations.single().effect.enabledValue)
    }

    @Test
    fun `fresh session reported during non Atmos period is retained for same period upgrade`() {
        val creations = mutableListOf<Creation>()
        val player = Any()
        val processor = processor(creations)

        processor.onAudioSessionId(player, 81)
        processor.onAudioVariantChanged(player, 4, 1001L, -18f, 6)
        processor.onPlayerActivated(player)

        processor.onAudioVariantChanged(player, 2, 1002L, -8f, 2)
        processor.onAudioSessionId(player, 82)
        processor.onAudioVariantChanged(player, 4, 1002L, -19f, 6)

        assertEquals(listOf(81, 82), creations.map(Creation::sessionId))
        assertEquals(7.5f, creations.last().decision.inputGainDb, 0.001f)
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
        processor.onPlayerActivated(player)

        processor.onAudioVariantChanged(player, 4, 1002L, -19f, 6)
        assertEquals(1, creations.size)
        assertFalse(creations.single().effect.released)

        processor.onAudioSessionId(player, 82)
        assertTrue(creations.first().effect.released)
        assertEquals(7.5f, creations.last().decision.inputGainDb, 0.001f)
    }

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
