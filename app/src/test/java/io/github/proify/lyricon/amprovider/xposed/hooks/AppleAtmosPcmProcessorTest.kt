/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

class AppleAtmosPcmProcessorTest {
    private fun assertWaitsForProcessorLock(processor: AppleAtmosVolumeProcessor, action: () -> Unit) {
        val task = FutureTask<Unit> { action() }
        val worker = Thread(task, "Atmos processor lock regression").apply { isDaemon = true }
        try {
            synchronized(processor) {
                worker.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!task.isDone && worker.state != Thread.State.BLOCKED &&
                    System.nanoTime() < deadline
                ) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
                }
                assertEquals(
                    "PCM and release callbacks must wait for the playback processor monitor",
                    Thread.State.BLOCKED,
                    worker.state,
                )
            }
        } finally {
            // Join only after releasing the monitor; also propagate worker exceptions.
            task.get(5, TimeUnit.SECONDS)
            worker.join(1_000)
        }
    }

    @Test
    fun `PCM context capture shares the playback state lock`() {
        val f = Fixture()
        f.start()
        assertWaitsForProcessorLock(f.processor) {
            assertNotNull(f.processor.capturePcmContext(f.session, f.track))
        }
    }

    @Test
    fun `PCM discontinuity shares the playback state lock`() {
        val f = Fixture()
        f.start()
        val before = f.window().context
        assertWaitsForProcessorLock(f.processor) {
            f.processor.onPcmDiscontinuity(f.session, f.track, flush = true)
        }
        assertNotEquals(before, f.window().context)
    }

    @Test
    fun `PCM reference update shares the playback state lock`() {
        val f = Fixture()
        f.start(variant = 1)
        val window = f.window(channels = 2)
        assertWaitsForProcessorLock(f.processor) { f.processor.onPcmWindow(window) }
        assertEquals(-27f, checkNotNull(f.processor.nonAtmosReferenceDbfs), 0.001f)
    }

    @Test
    fun `player release shares the playback state lock`() {
        val f = Fixture()
        f.start()
        assertWaitsForProcessorLock(f.processor) { f.processor.onPlayerReleased(f.player) }
        assertTrue(f.effects.last().released)
        assertNull(f.processor.capturePcmContext(f.session, f.track))
    }

    private class Effect(initial: Float) : AppleSessionDynamicsEffect {
        var gain = initial
        var released = false
        override fun setEnabled(enabled: Boolean) = Unit
        override fun setInputGainDb(inputGainDb: Float) { gain = inputGainDb }
        override fun release() { released = true }
    }

    private class Fixture(var queued: Boolean = false) {
        var enabled = true
        val player = Any()
        val effects = mutableListOf<Effect>()
        val pending = mutableListOf<() -> Unit>()
        val processor = AppleAtmosVolumeProcessor(
            preferenceEnabled = { enabled },
            effectFactory = { _, _, _, gain -> Effect(gain).also(effects::add) },
            scheduleDelayed = { _, action -> if (queued) pending += action else action() },
        )
        var session = 10
        var track = 100
        var period = 1L
        fun start(variant: Int = 4, route: Int? = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            processor.onAudioSessionId(player, session)
            processor.onAudioVariantChanged(player, variant, period, -18f, if (variant == 4) 12 else 2)
            processor.onAudioTrackPlayed(session, track, route)
            processor.onPlayerActivated(player)
        }
        fun next(variant: Int = 4) {
            processor.onAudioTrackStopped(session, track, "test")
            session++
            track++
            period++
            start(variant)
        }
        fun window(
            front: Float = -27f,
            peak: Float = -15f,
            channels: Int = 12,
            effective: Float = front,
            effectivePeak: Float = peak,
        ) = AppleAtmosPcmWindow(
            checkNotNull(processor.capturePcmContext(session, track)),
            channels, 48_000, AudioFormat.ENCODING_PCM_FLOAT, 96_000,
            front, effective, peak, effectivePeak,
        )
        fun feed(front: Float = -27f, peak: Float = -15f) = processor.onPcmWindow(window(front, peak))
        fun drain() { pending.toList().also { pending.clear() }.forEach { it() } }
        val gain get() = effects.last().gain
    }

    @Test
    fun `fallback PCM gradually raises Atmos without returning to metadata on repeated notifications`() {
        val f = Fixture()
        f.start()
        assertEquals(2f, f.gain, 0f)
        f.feed()
        assertEquals(3f, f.gain, 0.001f)
        f.processor.onAudioVariantChanged(f.player, 4, f.period, -18f, 12)
        f.processor.onAudioTrackRouteChanged(f.session, f.track, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertEquals(3f, f.gain, 0.001f)
        repeat(12) { f.feed() }
        assertEquals(7f, f.gain, 0.001f)
    }

    @Test
    fun `non Atmos client attenuated reference wins over fallback and is never amplified`() {
        val f = Fixture()
        f.start(variant = 1)
        f.processor.onPcmWindow(f.window(front = -16f, peak = -3f, channels = 2,
            effective = -26f, effectivePeak = -13f))
        assertTrue(f.effects.isEmpty())
        f.next()
        f.feed()
        assertEquals(1f, f.gain, 0.001f) // measured -26 minus -27, not fallback -20 minus -27
    }

    @Test
    fun `new ordinary track refreshes reference instead of retaining the prior curve forever`() {
        val f = Fixture()
        f.start(variant = 1)
        f.processor.onPcmWindow(f.window(front = -26f, channels = 2))
        f.next(variant = 1)
        repeat(50) { f.processor.onPcmWindow(f.window(front = -22f, channels = 2)) }
        f.next()
        repeat(8) { f.feed() }
        assertTrue(f.gain in 4.0f..5.1f)
    }

    @Test
    fun `silence invalid and multichannel ordinary windows cannot poison reference`() {
        for (front in listOf(Float.NEGATIVE_INFINITY, Float.NaN, -90f)) {
            val f = Fixture()
            f.start(variant = 1)
            f.processor.onPcmWindow(f.window(front = front, channels = 2))
            f.processor.onPcmWindow(f.window(front = -50f, channels = 12))
            f.next()
            f.feed()
            assertEquals(3f, f.gain, 0.001f)
        }
    }

    @Test
    fun `muted and silent Atmos cannot request a rise`() {
        val f = Fixture()
        f.start()
        f.processor.onPcmWindow(f.window(front = Float.NEGATIVE_INFINITY))
        f.processor.onPcmWindow(f.window(effective = Float.NEGATIVE_INFINITY,
            effectivePeak = Float.NEGATIVE_INFINITY))
        assertEquals(2f, f.gain, 0f)
    }

    @Test
    fun `every channel peak cap wins immediately including sub-hysteresis changes`() {
        val f = Fixture()
        f.start()
        f.feed(peak = -5.9f)
        assertEquals(2f, f.gain, 0.001f) // less than 1 dB does not trigger an upward step
        f.feed(peak = -4.9f)
        assertEquals(1.9f, f.gain, 0.001f) // 0.1 dB safety fall must not wait for 0.25 hysteresis
        repeat(5) { f.feed(peak = -20f) }
        assertEquals(1.9f, f.gain, 0.001f) // observed peak is retained for this Period
    }

    @Test
    fun `peak headroom includes actual client attenuation`() {
        val f = Fixture()
        f.start()
        f.processor.onPcmWindow(f.window(front = -17f, peak = -1f,
            effective = -27f, effectivePeak = -11f))
        assertEquals(3f, f.gain, 0.001f)
    }

    @Test
    fun `observed peak cancels pending upward ramp`() {
        val f = Fixture(queued = true)
        f.start()
        f.feed()
        assertTrue(f.pending.isNotEmpty())
        assertEquals(2f, f.gain, 0f)
        f.feed(peak = -3.5f)
        assertEquals(0.5f, f.gain, 0.001f)
        f.drain()
        assertEquals(0.5f, f.gain, 0.001f)
    }

    @Test
    fun `disabled unknown headphone and inactive tracks cannot capture PCM`() {
        val f = Fixture()
        f.start(route = null)
        assertNull(f.processor.capturePcmContext(f.session, f.track))
        f.processor.onAudioTrackRouteChanged(f.session, f.track, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        assertNull(f.processor.capturePcmContext(f.session, f.track))
        f.processor.onAudioTrackRouteChanged(f.session, f.track, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertNotNull(f.processor.capturePcmContext(f.session, f.track))
        assertNull(f.processor.capturePcmContext(f.session, f.track + 1))
        assertNull(f.processor.capturePcmContext(f.session + 1, f.track))
        f.enabled = false
        f.processor.onPreferenceChanged()
        assertNull(f.processor.capturePcmContext(f.session, f.track))
        assertTrue(f.effects.last().released)
    }

    @Test
    fun `windows spanning speaker headphones speaker or disable enable are discarded`() {
        val f = Fixture()
        f.start()
        val beforeRoute = f.window()
        f.processor.onAudioTrackRouteChanged(f.session, f.track, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        f.processor.onAudioTrackRouteChanged(f.session, f.track, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        f.processor.onPcmWindow(beforeRoute)
        assertEquals(2f, f.gain, 0f)
        val beforeToggle = f.window()
        f.enabled = false
        f.processor.onPreferenceChanged()
        f.enabled = true
        f.processor.onPreferenceChanged()
        f.processor.onPcmWindow(beforeToggle)
        assertEquals(2f, f.gain, 0f)
        f.feed()
        assertEquals(3f, f.gain, 0.001f)
    }

    @Test
    fun `same numeric session in a fresh Period cannot reuse the old target or window`() {
        val f = Fixture()
        f.start()
        f.feed()
        val stale = f.window()
        val old = f.effects.last()
        f.processor.onAudioSessionId(f.player, f.session) // fresh callback before new format
        f.processor.onAudioVariantChanged(f.player, 4, ++f.period, -18f, 12)
        assertTrue(old.released)
        assertEquals(2f, f.gain, 0f)
        f.processor.onPcmWindow(stale)
        assertEquals(2f, f.gain, 0f)
    }

    @Test
    fun `switching session rejects late successful writes and scheduled gain work`() {
        val f = Fixture(queued = true)
        f.start()
        f.feed()
        val stale = f.window()
        val old = f.effects.last()
        f.next()
        f.processor.onPcmWindow(stale)
        f.drain()
        assertTrue(old.released)
        assertEquals(2f, old.gain, 0f)
        assertEquals(2f, f.gain, 0f)
    }

    @Test
    fun `pause discards partial window and flush also discards learned target`() {
        val f = Fixture()
        f.start()
        f.feed()
        val old = f.window()
        f.processor.onPcmDiscontinuity(f.session, f.track, flush = false)
        f.processor.onPcmWindow(old)
        assertEquals(3f, f.gain, 0.001f)
        val beforeFlush = f.window()
        f.processor.onPcmDiscontinuity(f.session, f.track, flush = true)
        assertEquals(2f, f.gain, 0.001f)
        f.processor.onPcmWindow(beforeFlush)
        assertEquals(2f, f.gain, 0.001f)
    }

    @Test
    fun `ordinary to Atmos hot upgrade does not measure old ordinary tail`() {
        val f = Fixture()
        f.start(variant = 1)
        f.processor.onAudioVariantChanged(f.player, 4, f.period, -18f, 12)
        assertNull(f.processor.capturePcmContext(f.session, f.track))
        assertTrue(f.effects.isEmpty())
        f.processor.onAudioTrackStopped(f.session, f.track, "stop")
        f.track++
        f.processor.onAudioTrackPlayed(f.session, f.track, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertNotNull(f.processor.capturePcmContext(f.session, f.track))
        f.feed()
        assertEquals(3f, f.gain, 0.001f)
    }
    @Test
    fun `an unresolved additional route cannot inherit a known speaker boost`() {
        val f = Fixture()
        f.start()
        f.feed()
        val old = f.effects.last()
        f.processor.onAudioTrackPlayed(f.session, f.track + 1, null)
        assertTrue(old.released)
        assertNull(f.processor.capturePcmContext(f.session, f.track))
        f.processor.onAudioTrackStopped(f.session, f.track + 1, "stop")
        assertNotNull(f.processor.capturePcmContext(f.session, f.track))
        assertEquals(2f, f.gain, 0f)
    }

    @Test
    fun `lossless on a fresh session without player callback still feeds the reference`() {
        val f = Fixture()
        f.start()
        f.feed()
        // Renderer switch in the real host: period changes to an ALAC track whose AudioTrack
        // uses a DIFFERENT session, and the player session callback never fires again.
        f.processor.onAudioSessionId(f.player, f.session) // generation bump only (live: gen 6→7)
        val staleAtmos = f.window()
        f.processor.onAudioTrackStopped(f.session, f.track, "test")
        val losslessSession = f.session + 90
        val losslessTrack = f.track + 90
        f.processor.onAudioVariantChanged(f.player, 2, f.period + 1, -8.25f, 2)
        f.processor.onAudioTrackPlayed(losslessSession, losslessTrack,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertNotNull(f.processor.capturePcmContext(losslessSession, losslessTrack))
        // State session was retained, so the stale Atmos window is rejected by generation.
        assertNull(f.processor.capturePcmContext(f.session, f.track))
        f.processor.onPcmWindow(AppleAtmosPcmWindow(
            checkNotNull(f.processor.capturePcmContext(losslessSession, losslessTrack)),
            2, 44_100, AudioFormat.ENCODING_PCM_FLOAT, 88_200,
            frontRmsDbfs = -26f, frontEffectiveDbfs = -26f,
            peakDbfs = -8f, effectivePeakDbfs = -8f,
        ))
        // Next Atmos track: the learned -26 reference must win over the -20 fallback.
        f.next()
        f.feed()
        assertEquals(1f, f.gain, 0.001f)
        f.processor.onPcmWindow(staleAtmos)
        assertEquals(1f, f.gain, 0.001f)
    }

    @Test
    fun `foreign session capture is blocked while Atmos so gains cannot leak across sessions`() {
        val f = Fixture()
        f.start()
        f.feed()
        f.processor.onAudioTrackPlayed(f.session + 50, f.track + 50,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertNull(f.processor.capturePcmContext(f.session + 50, f.track + 50))
        f.processor.onAudioTrackStopped(f.session + 50, f.track + 50, "stop")
        assertNotNull(f.processor.capturePcmContext(f.session, f.track))
    }

    @Test
    fun `unnormalized loud ordinary intro cannot inflate the learned reference`() {
        val f = Fixture()
        f.start(variant = 1)
        f.processor.onPcmWindow(f.window(front = -26f, channels = 2))
        // Live repro: the stereo fallback of an Atmos song plays without Sound Check.
        f.processor.onPcmWindow(f.window(front = -10f, channels = 2))
        // With the outlier accepted the reference would jump to about -17.7 and command 3.
        f.next()
        f.feed()
        assertEquals(1f, f.gain, 0.001f)
    }

    @Test
    fun `reference recovers toward quieter normal listening from a polluted high baseline`() {
        val f = Fixture()
        f.start(variant = 1)
        f.processor.onPcmWindow(f.window(front = -12f, channels = 2))
        repeat(30) {
            f.processor.onPcmWindow(f.window(front = -22f, channels = 2))
        }
        f.next()
        repeat(5) { f.feed() }
        // -21.7 learned reference wants about +5.3 dB; a polluted -12 baseline would climb to 7+.
        assertEquals(5f, f.gain, 0.001f)
    }

}
