/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root

import org.junit.Assert.*
import org.junit.Test

class SystemUiLyricRuntimeModeTest {
    @Test fun `issue 43 Apple-only configuration does not participate in Central`() {
        val mode = SystemUiLyricRuntimeMode.resolve(false, false, false, true, true, true)
        assertEquals(SystemUiLyricRuntimeMode.APPLE_DIRECT, mode)
        assertFalse(mode.requiresCentral)
        assertEquals("lyricon", mode.sourceId("superlyric"))
    }

    @Test fun `disabled features start neither source nor Central`() {
        val mode = SystemUiLyricRuntimeMode.resolve(false, false, false, false, false, false)
        assertFalse(mode.requiresCentral)
        assertNull(mode.sourceId("lyricon"))
    }

    @Test fun `each lyric display retains Central and the selected source`() {
        for (features in listOf(Triple(true, false, false), Triple(false, true, false), Triple(false, false, true))) {
            for (apple in listOf(false, true)) {
                val mode = SystemUiLyricRuntimeMode.resolve(
                    features.first, features.second, features.third, apple, false, false,
                )
                assertEquals(SystemUiLyricRuntimeMode.LYRIC_DISPLAY, mode)
                assertTrue(mode.requiresCentral)
                assertEquals("superlyric", mode.sourceId("superlyric"))
            }
        }
    }

    @Test fun `reenabling display restores selection after Apple direct mode`() {
        val configuredSource = "lyricinfo"
        val withDisplay = SystemUiLyricRuntimeMode.resolve(true, false, false, true, false, false)
        val appleOnly = SystemUiLyricRuntimeMode.resolve(false, false, false, true, false, false)
        assertEquals(configuredSource, withDisplay.sourceId(configuredSource))
        assertEquals("lyricon", appleOnly.sourceId(configuredSource))
        assertEquals(configuredSource, withDisplay.sourceId(configuredSource))
    }

    @Test fun `missing lyrics alone starts the bridge without competing with the status bar Central`() {
        val mode = SystemUiLyricRuntimeMode.resolve(false, false, false, false, true, false)
        assertEquals(SystemUiLyricRuntimeMode.APPLE_DIRECT, mode)
        assertFalse(mode.requiresCentral)
        assertEquals("lyricon", mode.sourceId("superlyric"))
    }

    @Test fun `LunaBeat alone starts the bridge without translation enabled`() {
        val mode = SystemUiLyricRuntimeMode.resolve(false, false, false, false, false, true)
        assertEquals(SystemUiLyricRuntimeMode.APPLE_DIRECT, mode)
        assertFalse(mode.requiresCentral)
        assertEquals("lyricon", mode.sourceId("lyricinfo"))
    }

    @Test fun `all feature combinations preserve source ownership and disabled behavior`() {
        for (displayMask in 0..7) {
            for (appleMask in 0..7) {
                val mode = SystemUiLyricRuntimeMode.resolve(
                    hyperIsland = displayMask and 1 != 0,
                    aodLyrics = displayMask and 2 != 0,
                    dynamicIsland = displayMask and 4 != 0,
                    nativeAppleTranslation = appleMask and 1 != 0,
                    fillMissingAppleLyrics = appleMask and 2 != 0,
                    lunaBeatWordLyrics = appleMask and 4 != 0,
                )
                val case = "display=$displayMask apple=$appleMask"
                val expectedMode = when {
                    displayMask != 0 -> SystemUiLyricRuntimeMode.LYRIC_DISPLAY
                    appleMask != 0 -> SystemUiLyricRuntimeMode.APPLE_DIRECT
                    else -> SystemUiLyricRuntimeMode.DISABLED
                }
                assertEquals(case, expectedMode, mode)
                assertEquals(case, displayMask != 0, mode.requiresCentral)
                assertEquals(case, when {
                    displayMask != 0 -> "superlyric"
                    appleMask != 0 -> "lyricon"
                    else -> null
                }, mode.sourceId("superlyric"))
            }
        }
    }

    @Test fun `switching supplements keeps the direct runtime until the last feature is disabled`() {
        val modes = listOf(
            SystemUiLyricRuntimeMode.resolve(false, false, false, false, false, false),
            SystemUiLyricRuntimeMode.resolve(false, false, false, false, true, false),
            SystemUiLyricRuntimeMode.resolve(false, false, false, false, true, true),
            SystemUiLyricRuntimeMode.resolve(false, false, false, false, false, true),
            SystemUiLyricRuntimeMode.resolve(false, false, false, false, false, false),
        )
        assertEquals(
            listOf(null, "lyricon", "lyricon", "lyricon", null),
            modes.map { it.sourceId("superlyric") },
        )
        assertTrue(modes.none { it.requiresCentral })
    }
}
