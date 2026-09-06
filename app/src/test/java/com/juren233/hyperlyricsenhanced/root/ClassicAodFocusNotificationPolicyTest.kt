package com.juren233.hyperlyricsenhanced.root

import com.juren233.hyperlyricsenhanced.common.RootConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicAodFocusNotificationPolicyTest {

    @Test
    fun `requires autostart only for enabled classic aod song info`() {
        assertTrue(
            ClassicAodFocusNotificationPolicy.requiresAutoStart(
                aodLyricsEnabled = true,
                songInfoDisplayStyle =
                    RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_FOCUS_NOTIFICATION
            )
        )
    }

    @Test
    fun `does not require autostart when aod lyrics are disabled`() {
        assertFalse(
            ClassicAodFocusNotificationPolicy.requiresAutoStart(
                aodLyricsEnabled = false,
                songInfoDisplayStyle =
                    RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_FOCUS_NOTIFICATION
            )
        )
    }

    @Test
    fun `does not require autostart when song info is hidden`() {
        assertFalse(
            ClassicAodFocusNotificationPolicy.requiresAutoStart(
                aodLyricsEnabled = true,
                songInfoDisplayStyle =
                    RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_TEXT_EMBEDDED
            )
        )
    }

    @Test
    fun `song signature changes for a different track identifier`() {
        val first = ClassicAodFocusNotificationPolicy.songSignature(
            packageName = "com.apple.android.music",
            identifier = "first",
            title = "Song",
            artist = "Artist",
            format = RootConstants.AOD_SONG_INFO_FORMAT_TITLE_ARTIST,
        )
        val second = ClassicAodFocusNotificationPolicy.songSignature(
            packageName = "com.apple.android.music",
            identifier = "second",
            title = "Song",
            artist = "Artist",
            format = RootConstants.AOD_SONG_INFO_FORMAT_TITLE_ARTIST,
        )

        assertNotEquals(first, second)
    }

    @Test
    fun `alternates classic aod notification ids on every new song`() {
        assertEquals(
            2004,
            ClassicAodFocusNotificationPolicy.nextNotificationId(
                activeNotificationId = null,
                primaryNotificationId = 2004,
                secondaryNotificationId = 2005,
            )
        )
        assertEquals(
            2005,
            ClassicAodFocusNotificationPolicy.nextNotificationId(
                activeNotificationId = 2004,
                primaryNotificationId = 2004,
                secondaryNotificationId = 2005,
            )
        )
    }

    @Test
    fun `fullscreen aod is active only for the enabled raw setting`() {
        assertTrue(ClassicAodFocusNotificationPolicy.isFullScreenAodActive("1"))
    }

    @Test
    fun `classic aod stays active when fullscreen aod is disabled or unknown`() {
        assertFalse(ClassicAodFocusNotificationPolicy.isFullScreenAodActive("0"))
        assertFalse(ClassicAodFocusNotificationPolicy.isFullScreenAodActive(null))
        assertFalse(ClassicAodFocusNotificationPolicy.isFullScreenAodActive(""))
        assertFalse(ClassicAodFocusNotificationPolicy.isFullScreenAodActive("2"))
        assertFalse(ClassicAodFocusNotificationPolicy.isFullScreenAodActive("true"))
    }

    @Test
    fun `fullscreen aod setting key matches the verified system key`() {
        assertEquals("full_screen_aod_on", ClassicAodFocusNotificationPolicy.SETTING_FULL_SCREEN_AOD_ON)
    }
}
