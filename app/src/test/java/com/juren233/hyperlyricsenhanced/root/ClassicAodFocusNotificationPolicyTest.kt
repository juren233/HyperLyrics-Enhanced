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

    @Test
    fun `media session from the current lyric player is shown`() {
        assertTrue(
            ClassicAodFocusNotificationPolicy.isLyricPlayer(
                targetPackageName = "com.netease.cloudmusic",
                lyricPackageName = "com.netease.cloudmusic",
            )
        )
    }

    @Test
    fun `media session from another app is not shown`() {
        assertFalse(
            ClassicAodFocusNotificationPolicy.isLyricPlayer(
                targetPackageName = "tv.danmaku.bili",
                lyricPackageName = "com.netease.cloudmusic",
            )
        )
    }

    @Test
    fun `nothing is shown before systemui reports a lyric player`() {
        assertFalse(
            ClassicAodFocusNotificationPolicy.isLyricPlayer(
                targetPackageName = "com.netease.cloudmusic",
                lyricPackageName = null,
            )
        )
        assertFalse(
            ClassicAodFocusNotificationPolicy.isLyricPlayer(
                targetPackageName = "",
                lyricPackageName = "",
            )
        )
    }

    @Test
    fun `prefers the lyric player among concurrent sessions`() {
        val sessions = listOf("tv.danmaku.bili", "com.netease.cloudmusic")

        assertEquals(
            "com.netease.cloudmusic",
            ClassicAodFocusNotificationPolicy.preferLyricPlayer(
                items = sessions,
                packageOf = { it },
                lyricPackageName = "com.netease.cloudmusic",
            ),
        )
    }

    @Test
    fun `keeps the first session when no lyric player is known or present`() {
        val sessions = listOf("tv.danmaku.bili", "com.netease.cloudmusic")

        assertEquals(
            "tv.danmaku.bili",
            ClassicAodFocusNotificationPolicy.preferLyricPlayer(sessions, { it }, null),
        )
        assertEquals(
            "tv.danmaku.bili",
            ClassicAodFocusNotificationPolicy.preferLyricPlayer(sessions, { it }, "com.spotify.music"),
        )
        assertEquals(
            null,
            ClassicAodFocusNotificationPolicy.preferLyricPlayer(emptyList<String>(), { it }, "x"),
        )
    }
}
