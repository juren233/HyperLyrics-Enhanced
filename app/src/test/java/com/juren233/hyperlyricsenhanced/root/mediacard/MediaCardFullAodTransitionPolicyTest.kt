package com.juren233.hyperlyricsenhanced.root.mediacard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCardFullAodTransitionPolicyTest {
    @Test
    fun `finishes the leading content transition before the native card midpoint`() {
        assertEquals(0f, MediaCardFullAodTransitionPolicy.leadingContentProgress(0f), 0.0001f)
        assertEquals(0.75f, MediaCardFullAodTransitionPolicy.leadingContentProgress(0.21f), 0.0001f)
        assertEquals(1f, MediaCardFullAodTransitionPolicy.leadingContentProgress(0.42f), 0.0001f)
        assertEquals(1f, MediaCardFullAodTransitionPolicy.leadingContentProgress(1f), 0.0001f)
    }

    @Test
    fun `retracts the whole lyric root only for paused restore-native mode`() {
        assertTrue(
            MediaCardFullAodTransitionPolicy.shouldRetractWholeLyricRoot(
                MediaCardFullAodTransitionMode.PAUSED_RESTORE_NATIVE,
            )
        )
        assertFalse(
            MediaCardFullAodTransitionPolicy.shouldRetractWholeLyricRoot(
                MediaCardFullAodTransitionMode.PAUSED_KEEP_LYRICS,
            )
        )
        assertFalse(
            MediaCardFullAodTransitionPolicy.shouldRetractWholeLyricRoot(
                MediaCardFullAodTransitionMode.DEFAULT,
            )
        )
    }

    @Test
    fun `fades playback actions whenever lyrics remain in the transition`() {
        assertTrue(
            MediaCardFullAodTransitionPolicy.shouldFadeActions(
                MediaCardFullAodTransitionMode.PAUSED_KEEP_LYRICS,
            )
        )
        assertFalse(
            MediaCardFullAodTransitionPolicy.shouldFadeActions(
                MediaCardFullAodTransitionMode.PAUSED_RESTORE_NATIVE,
            )
        )
        assertTrue(
            MediaCardFullAodTransitionPolicy.shouldFadeActions(
                MediaCardFullAodTransitionMode.DEFAULT,
            )
        )
    }
}
