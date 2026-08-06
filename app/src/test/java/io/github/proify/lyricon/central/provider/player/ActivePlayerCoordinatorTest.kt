/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.ProviderInfo
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

    @Test
    fun `enabled official Pack blocks legacy before any Provider is active`() {
        val coordinator = coordinator { true }
        val listener = RecordingListener()
        coordinator.addListener(listener)

        coordinator.onPlaybackStateChanged(playingRecorder(legacyInfo), true)

        assertNull(listener.activeProvider)
    }

    @Test
    fun `enabled official Pack accepts official Provider and ignores later legacy events`() {
        val coordinator = coordinator { true }
        val listener = RecordingListener()
        coordinator.addListener(listener)

        coordinator.onPlaybackStateChanged(playingRecorder(officialInfo), true)
        coordinator.onPlaybackStateChanged(playingRecorder(legacyInfo), true)

        assertEquals(officialInfo, listener.activeProvider)
    }

    @Test
    fun `enabling official Pack clears an already active legacy Provider`() {
        var preferred = false
        val coordinator = coordinator { preferred }
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
        val coordinator = coordinator { preferred }
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
        val coordinator = coordinator { null }
        val listener = RecordingListener()
        coordinator.addListener(listener)
        coordinator.onPlaybackStateChanged(playingRecorder(legacyInfo), true)

        coordinator.onPlaybackStateChanged(playingRecorder(officialInfo), true)

        assertEquals(officialInfo, listener.activeProvider)
    }

    private fun playingRecorder(providerInfo: ProviderInfo) = PlayerRecorder(providerInfo).apply {
        isPlaying = true
    }

    private fun coordinator(
        officialProviderPreference: (String) -> Boolean?,
    ) = ActivePlayerCoordinator(
        officialProviderPreference = officialProviderPreference,
        decisionLogger = {},
    )

    private class RecordingListener : ActivePlayerListener {
        var activeProvider: ProviderInfo? = null
        var isPlaying = false

        override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
            activeProvider = providerInfo
        }

        override fun onSongChanged(song: Song?) = Unit

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            this.isPlaying = isPlaying
        }

        override fun onPositionChanged(position: Long) = Unit

        override fun onSeekTo(position: Long) = Unit

        override fun onSendText(text: String?) = Unit

        override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) = Unit

        override fun onDisplayRomaChanged(isDisplayRoma: Boolean) = Unit
    }
}
