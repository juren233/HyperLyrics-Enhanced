package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicProviderTest {

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

    private class FakeMediaPlayer(private val position: Long) {
        fun getCurrentPosition(): Long = position
    }
}
