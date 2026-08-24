package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import com.juren233.hyperlyricsenhanced.common.RootConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class AodEnvironmentPolicyTest {
    @Test
    fun `keeps HyperOS 3 original DEX show style mapping exact`() {
        assertEquals("temporary_10s_after_tap", AodEnvironmentPolicy.showStyleName(0))
        assertEquals("scheduled", AodEnvironmentPolicy.showStyleName(1))
        assertEquals("always", AodEnvironmentPolicy.showStyleName(2))
        assertEquals("smart_attention", AodEnvironmentPolicy.showStyleName(3))
        assertEquals("unknown", AodEnvironmentPolicy.showStyleName(4))
        assertEquals("missing", AodEnvironmentPolicy.showStyleName(null))
    }

    @Test
    fun `formats AOD schedule minutes without inventing invalid times`() {
        assertEquals("00:00", AodEnvironmentPolicy.formatMinuteOfDay(0))
        assertEquals("07:30", AodEnvironmentPolicy.formatMinuteOfDay(450))
        assertEquals("23:59", AodEnvironmentPolicy.formatMinuteOfDay(1439))
        assertEquals("invalid(-1)", AodEnvironmentPolicy.formatMinuteOfDay(-1))
        assertEquals("invalid(1440)", AodEnvironmentPolicy.formatMinuteOfDay(1440))
        assertEquals("missing", AodEnvironmentPolicy.formatMinuteOfDay(null))
    }

    @Test
    fun `labels module pause and song information policies`() {
        assertEquals(
            "restore_native",
            AodEnvironmentPolicy.pauseStyleName(RootConstants.AOD_PAUSE_STYLE_RESTORE),
        )
        assertEquals(
            "keep_lyrics",
            AodEnvironmentPolicy.pauseStyleName(RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS),
        )
        assertEquals(
            "focus_notification",
            AodEnvironmentPolicy.songInfoDisplayStyleName(
                RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_FOCUS_NOTIFICATION,
            ),
        )
        assertEquals(
            "embedded_text",
            AodEnvironmentPolicy.songInfoDisplayStyleName(
                RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_TEXT_EMBEDDED,
            ),
        )
    }
}
