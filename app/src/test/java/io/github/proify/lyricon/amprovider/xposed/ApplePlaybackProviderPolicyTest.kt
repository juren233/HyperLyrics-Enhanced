/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplePlaybackProviderPolicyTest {

    @Test
    fun `position source reads the selected active player`() {
        val inactive = FakeMediaPlayer(position = 0L)
        val active = FakeMediaPlayer(position = 42_000L)

        val inactiveSource = resolvePlaybackPositionSource(inactive)
        val activeSource = resolvePlaybackPositionSource(active)

        assertEquals(0L, inactiveSource?.readPosition())
        assertEquals(42_000L, activeSource?.readPosition())
    }

    @Test
    fun `position source rejects objects without a position method`() {
        assertNull(resolvePlaybackPositionSource(Any()))
    }

    @Test
    fun `only callbacks from the active playback player are accepted`() {
        val active = FakeMediaPlayer(position = 42_000L)
        val queued = FakeMediaPlayer(position = 0L)

        assertTrue(isActivePlaybackCallback(active, active))
        assertFalse(isActivePlaybackCallback(queued, active))
        assertFalse(isActivePlaybackCallback(active, null))
    }

    @Test
    fun `active playback or an existing page binding may notify app models`() {
        assertTrue(shouldNotifyInAppModelChange("123", "123"))
        assertFalse(shouldNotifyInAppModelChange("123", "456"))
        assertFalse(shouldNotifyInAppModelChange("123", null))
        assertTrue(
            shouldNotifyInAppModelChange(
                mediaId = "123",
                activeMediaId = "456",
                hasBoundConsumer = true,
            )
        )
    }

    @Test
    fun `only media notifications open the full player`() {
        assertTrue(
            shouldOpenFullPlayerFromNotification(
                Notification.CATEGORY_TRANSPORT,
                hasMediaSession = false,
            )
        )
        assertTrue(
            shouldOpenFullPlayerFromNotification(
                category = null,
                hasMediaSession = true,
            )
        )
        assertFalse(
            shouldOpenFullPlayerFromNotification(
                Notification.CATEGORY_MESSAGE,
                hasMediaSession = false,
            )
        )
    }

    private class FakeMediaPlayer(private val position: Long) {
        fun getCurrentPosition(): Long = position
    }
}

