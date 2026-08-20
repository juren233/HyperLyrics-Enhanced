package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandContentMotionResumePolicyTest {
    @Test
    fun metadataMarqueeRestartsAfterVisiblePlaybackResume() {
        assertTrue(
            IslandContentMotionResumePolicy.shouldRequestMarquee(
                mode = 5,
                playbackActive = true,
                lyricMarqueeEnabled = false,
                metadataMarqueeEnabled = true,
            )
        )
    }

    @Test
    fun pausedOrDisabledMarqueeDoesNotRestart() {
        assertFalse(
            IslandContentMotionResumePolicy.shouldRequestMarquee(
                mode = 5,
                playbackActive = false,
                lyricMarqueeEnabled = true,
                metadataMarqueeEnabled = true,
            )
        )
        assertFalse(
            IslandContentMotionResumePolicy.shouldRequestMarquee(
                mode = 5,
                playbackActive = true,
                lyricMarqueeEnabled = true,
                metadataMarqueeEnabled = false,
            )
        )
    }

    @Test
    fun lyricMarqueeUsesItsOwnSetting() {
        assertTrue(
            IslandContentMotionResumePolicy.shouldRequestMarquee(
                mode = 7,
                playbackActive = true,
                lyricMarqueeEnabled = true,
                metadataMarqueeEnabled = false,
            )
        )
    }
}
