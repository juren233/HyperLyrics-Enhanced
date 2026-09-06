/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.ProviderInfo
import io.github.proify.lyricon.provider.ProviderMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivePlayerCoordinatorTest {
    private val playerPackageName = "com.salt.music"
    private val legacyInfo = ProviderInfo(
        "io.github.proify.lyricon.saltprovider",
        playerPackageName,
    )
    private val officialInfo = ProviderInfo(
        "com.juren233.hyperlyricsenhanced.provider.salt-player",
        playerPackageName,
    )
    private val nativeSaltInfo = ProviderInfo(
        playerPackageName,
        playerPackageName,
    )
    private val controlOnlyInfo = ProviderInfo(
        "com.juren233.hyperlyricsenhanced.provider.salt-player",
        playerPackageName,
        metadata = ProviderMetadata(
            mapOf(OfficialProviderControlProtocol.CONTROL_ONLY_METADATA_KEY to "true"),
        ),
    )
    private val otherPlayerPackageName = "com.luna.music"
    private val otherOfficialInfo = ProviderInfo(
        "com.juren233.hyperlyricsenhanced.provider.qishui",
        otherPlayerPackageName,
    )

    @Test
    fun `enabled official Pack blocks legacy before any Provider is active`() {
        val coordinator = coordinator(officialProviderPreference = { true })
        val listener = RecordingListener()
        coordinator.addListener(listener)

        coordinator.onPlaybackStateChanged(playingRecorder(legacyInfo), true)

        assertNull(listener.activeProvider)
    }

    @Test
    fun `enabled official Pack accepts official Provider and ignores later legacy events`() {
        val coordinator = coordinator(officialProviderPreference = { true })
        val listener = RecordingListener()
        coordinator.addListener(listener)

        coordinator.onPlaybackStateChanged(playingRecorder(officialInfo), true)
        coordinator.onPlaybackStateChanged(playingRecorder(legacyInfo), true)

        assertEquals(officialInfo, listener.activeProvider)
    }

    @Test
    fun `control only official Provider never becomes active`() {
        val coordinator = coordinator(officialProviderPreference = { true })
        val listener = RecordingListener()
        coordinator.addListener(listener)

        coordinator.onPlaybackStateChanged(playingRecorder(controlOnlyInfo), true)

        assertNull(listener.activeProvider)
    }

    @Test
    fun `enabled Salt Pack keeps native Salt Lyricon active`() {
        val coordinator = coordinator(officialProviderPreference = { true })
        val listener = RecordingListener()
        coordinator.addListener(listener)

        coordinator.onPlaybackStateChanged(playingRecorder(nativeSaltInfo), true)
        coordinator.onPlaybackStateChanged(playingRecorder(controlOnlyInfo), true)

        assertEquals(nativeSaltInfo, listener.activeProvider)
    }

    @Test
    fun `enabling official Pack clears an already active legacy Provider`() {
        var preferred = false
        val coordinator = coordinator(officialProviderPreference = { preferred })
        val listener = RecordingListener()
        coordinator.addListener(listener)
        coordinator.onPlaybackStateChanged(playingRecorder(legacyInfo), true)
        assertEquals(legacyInfo, listener.activeProvider)

        preferred = true
        coordinator.onOfficialProviderPreferencesChanged(setOf(playerPackageName))

        assertNull(listener.activeProvider)
        assertEquals(false, listener.isPlaying)
    }

    @Test
    fun `disabling official Pack clears official Provider and allows legacy on next event`() {
        var preferred = true
        val coordinator = coordinator(officialProviderPreference = { preferred })
        val listener = RecordingListener()
        coordinator.addListener(listener)
        coordinator.onPlaybackStateChanged(playingRecorder(officialInfo), true)
        assertEquals(officialInfo, listener.activeProvider)

        preferred = false
        coordinator.onOfficialProviderPreferencesChanged(setOf(playerPackageName))
        coordinator.onPlaybackStateChanged(playingRecorder(legacyInfo), true)

        assertEquals(legacyInfo, listener.activeProvider)
    }

    @Test
    fun `unknown preference state preserves previous runtime source ranking`() {
        val coordinator = coordinator(officialProviderPreference = { null })
        val listener = RecordingListener()
        coordinator.addListener(listener)
        coordinator.onPlaybackStateChanged(playingRecorder(legacyInfo), true)

        coordinator.onPlaybackStateChanged(playingRecorder(officialInfo), true)

        assertEquals(officialInfo, listener.activeProvider)
    }

    @Test
    fun `audio conflict blocks a stale playing Provider before it becomes active`() {
        val coordinator = coordinator(
            officialProviderPreference = { true },
            audioConflict = { true },
        )
        val listener = RecordingListener()
        coordinator.addListener(listener)

        coordinator.onPlaybackStateChanged(playingRecorder(officialInfo), true)

        assertNull(listener.activeProvider)
        assertEquals(false, listener.isPlaying)
    }

    @Test
    fun `unknown audio state preserves existing Provider selection`() {
        val coordinator = coordinator(
            officialProviderPreference = { true },
            audioConflict = { null },
        )
        val listener = RecordingListener()
        coordinator.addListener(listener)

        coordinator.onPlaybackStateChanged(playingRecorder(officialInfo), true)

        assertEquals(officialInfo, listener.activeProvider)
        assertEquals(true, listener.isPlaying)
    }

    @Test
    fun `audio conflict pauses active Provider and next valid position resumes it`() {
        var conflict: Boolean? = false
        val coordinator = coordinator(
            officialProviderPreference = { true },
            audioConflict = { conflict },
        )
        val listener = RecordingListener()
        coordinator.addListener(listener)
        val recorder = playingRecorder(officialInfo)
        coordinator.onPlaybackStateChanged(recorder, true)
        listener.positions.clear()

        conflict = true
        coordinator.onPositionChanged(recorder, 1_000L)

        assertEquals(false, listener.isPlaying)
        assertEquals(emptyList<Long>(), listener.positions)

        conflict = null
        coordinator.onPositionChanged(recorder, 1_500L)

        assertEquals(false, listener.isPlaying)
        assertEquals(emptyList<Long>(), listener.positions)

        conflict = false
        coordinator.onPositionChanged(recorder, 2_000L)

        assertEquals(true, listener.isPlaying)
        assertEquals(listOf(2_000L), listener.positions)
    }

    @Test
    fun `actual output from another player releases stale active Provider for switching`() {
        var actualOutputPackage = playerPackageName
        val coordinator = coordinator(
            officialProviderPreference = { true },
            audioConflict = { candidate -> candidate != actualOutputPackage },
        )
        val listener = RecordingListener()
        coordinator.addListener(listener)
        val staleRecorder = playingRecorder(officialInfo)
        coordinator.onPlaybackStateChanged(staleRecorder, true)
        assertEquals(officialInfo, listener.activeProvider)

        actualOutputPackage = otherPlayerPackageName
        coordinator.onPositionChanged(staleRecorder, 1_000L)
        coordinator.onPlaybackStateChanged(playingRecorder(otherOfficialInfo), true)

        assertEquals(otherOfficialInfo, listener.activeProvider)
        assertEquals(true, listener.isPlaying)
    }

    @Test
    fun `audio conflict must remain stable through confirmation window`() {
        var now = 0L
        val coordinator = coordinator(
            officialProviderPreference = { true },
            audioConflict = { true },
            elapsedRealtime = { now },
            audioConflictConfirmationMs = 200L,
        )
        val listener = RecordingListener()
        coordinator.addListener(listener)
        val recorder = playingRecorder(officialInfo)
        coordinator.onPlaybackStateChanged(recorder, true)
        listener.positions.clear()

        now = 199L
        coordinator.onPositionChanged(recorder, 1_000L)
        assertEquals(true, listener.isPlaying)
        assertEquals(listOf(1_000L), listener.positions)

        now = 200L
        coordinator.onPositionChanged(recorder, 1_100L)
        assertEquals(false, listener.isPlaying)
        assertEquals(listOf(1_000L), listener.positions)
    }

    @Test
    fun `suppressed active Provider replays cached Song when conflict clears`() {
        var conflict: Boolean? = false
        val coordinator = coordinator(
            officialProviderPreference = { true },
            audioConflict = { conflict },
        )
        val listener = RecordingListener()
        coordinator.addListener(listener)
        val recorder = playingRecorder(officialInfo)
        val song = Song().apply {
            id = "first-song"
            name = "First Song"
        }
        recorder.song = song
        coordinator.onSongChanged(recorder, song)
        assertEquals(listOf("first-song"), listener.songIds)
        listener.songIds.clear()

        conflict = true
        coordinator.onPositionChanged(recorder, 1_000L)

        assertEquals(false, listener.isPlaying)
        assertEquals(emptyList<String>(), listener.songIds)

        conflict = false
        coordinator.onPositionChanged(recorder, 2_000L)

        assertEquals(true, listener.isPlaying)
        assertEquals(listOf("first-song"), listener.songIds)
    }

    @Test
    fun `new zero anchor reaches active subscriber without another playing event`() {
        val coordinator = coordinator(officialProviderPreference = { true })
        val listener = RecordingListener()
        coordinator.addListener(listener)
        val recorder = playingRecorder(officialInfo)
        recorder.position = 35_163L
        coordinator.onPlaybackStateChanged(recorder, true)
        listener.positions.clear()

        // No ticker and no PLAYING -> PLAYING boolean event: only the new anchor is delivered.
        recorder.position = 0L
        coordinator.onPositionChanged(recorder, 0L)

        assertEquals(listOf(0L), listener.positions)
        assertEquals(true, listener.isPlaying)
        assertEquals(officialInfo, listener.activeProvider)
    }

    @Test
    fun `late song and same song lyrics keep the new authoritative position`() {
        val coordinator = coordinator(officialProviderPreference = { true })
        val listener = RecordingListener()
        coordinator.addListener(listener)
        val recorder = playingRecorder(officialInfo)
        recorder.position = 35_163L
        coordinator.onPlaybackStateChanged(recorder, true)
        listener.positions.clear()

        recorder.position = 0L
        coordinator.onPositionChanged(recorder, 0L)
        val song = Song().apply { id = "new-song"; name = "New Song" }
        recorder.song = song
        coordinator.onSongChanged(recorder, song)
        recorder.position = 356L
        coordinator.onPositionChanged(recorder, 356L)
        // A later same-song supplement must not restore the previous track's 35 second cursor.
        coordinator.onSongChanged(recorder, song)
        recorder.position = 1_200L
        coordinator.onPositionChanged(recorder, 1_200L)

        assertEquals(listOf(0L, 356L, 1_200L), listener.positions)
        val reconnect = RecordingListener()
        coordinator.syncNewProviderState(recorder, reconnect)
        assertEquals(listOf("new-song"), reconnect.songIds)
        assertEquals(listOf(1_200L), reconnect.positions)
    }

    private fun playingRecorder(providerInfo: ProviderInfo) = PlayerRecorder(providerInfo).apply {
        isPlaying = true
    }

    private fun coordinator(
        officialProviderPreference: (String) -> Boolean?,
        audioConflict: (String) -> Boolean? = { null },
        elapsedRealtime: () -> Long = { 0L },
        audioConflictConfirmationMs: Long = 0L,
    ) = ActivePlayerCoordinator(
        officialProviderPreference = officialProviderPreference,
        decisionLogger = {},
        activeAudioPlaybackMonitor = ActiveAudioPlaybackMonitor { audioConflict(it) },
        elapsedRealtime = elapsedRealtime,
        audioConflictConfirmationMs = audioConflictConfirmationMs,
    )

    private class RecordingListener : ActivePlayerListener {
        var activeProvider: ProviderInfo? = null
        var isPlaying = false
        val positions = mutableListOf<Long>()
        val songIds = mutableListOf<String>()

        override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
            activeProvider = providerInfo
        }

        override fun onSongChanged(song: Song?) {
            songIds += song?.id.orEmpty()
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            this.isPlaying = isPlaying
        }

        override fun onPositionChanged(position: Long) {
            positions += position
        }

        override fun onSeekTo(position: Long) = Unit

        override fun onSendText(text: String?) = Unit

        override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) = Unit

        override fun onDisplayRomaChanged(isDisplayRoma: Boolean) = Unit
    }
}
