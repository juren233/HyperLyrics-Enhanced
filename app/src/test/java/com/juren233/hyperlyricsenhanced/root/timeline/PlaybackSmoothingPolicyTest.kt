/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSmoothingPolicyTest {

    @Test
    fun `short false pause keeps same package source hint but stale pause expires`() {
        assertTrue(PlaybackSmoothingPolicy.allowsSourceHint(3_400L, null))
        assertFalse(PlaybackSmoothingPolicy.allowsSourceHint(80_000L, null))
        assertTrue(PlaybackSmoothingPolicy.allowsSourceHint(80_000L, 5_000L))
        assertFalse(PlaybackSmoothingPolicy.allowsSourceHint(80_000L, 8_000L))
    }

    @Test
    fun `anchor playing always wins`() {
        assertTrue(
            PlaybackSmoothingPolicy.effectivePlaying(
                anchorPlaying = true,
                hintPlaying = false,
                hintPackage = null,
                anchorPackage = "com.apple.android.music",
            ),
        )
    }

    @Test
    fun `buffering pause is smoothed when in-app hint stays playing for same package`() {
        assertTrue(
            PlaybackSmoothingPolicy.effectivePlaying(
                anchorPlaying = false,
                hintPlaying = true,
                hintPackage = "com.apple.android.music",
                anchorPackage = "com.apple.android.music",
            ),
        )
    }

    @Test
    fun `real pause is not smoothed when in-app hint also pauses`() {
        assertFalse(
            PlaybackSmoothingPolicy.effectivePlaying(
                anchorPlaying = false,
                hintPlaying = false,
                hintPackage = "com.apple.android.music",
                anchorPackage = "com.apple.android.music",
            ),
        )
    }

    @Test
    fun `hint from another package never smooths the anchor track`() {
        assertFalse(
            PlaybackSmoothingPolicy.effectivePlaying(
                anchorPlaying = false,
                hintPlaying = true,
                hintPackage = "com.luna.music",
                anchorPackage = "com.apple.android.music",
            ),
        )
    }

    @Test
    fun `missing package information never smooths`() {
        assertFalse(
            PlaybackSmoothingPolicy.effectivePlaying(
                anchorPlaying = false,
                hintPlaying = true,
                hintPackage = null,
                anchorPackage = "com.apple.android.music",
            ),
        )
        assertFalse(
            PlaybackSmoothingPolicy.effectivePlaying(
                anchorPlaying = false,
                hintPlaying = true,
                hintPackage = "com.apple.android.music",
                anchorPackage = null,
            ),
        )
    }

    @Test
    fun `same package track change retains in-app playback hint`() {
        assertTrue(
            PlaybackSmoothingPolicy.shouldRetainHint(
                hintPackage = "com.apple.android.music",
                nextAnchorPackage = "com.apple.android.music",
            ),
        )
    }

    @Test
    fun `cross app or cleared session drops old playback hint`() {
        assertFalse(
            PlaybackSmoothingPolicy.shouldRetainHint(
                hintPackage = "com.apple.android.music",
                nextAnchorPackage = "com.salt.music",
            ),
        )
        assertFalse(
            PlaybackSmoothingPolicy.shouldRetainHint(
                hintPackage = "com.apple.android.music",
                nextAnchorPackage = null,
            ),
        )
    }

    @Test
    fun `same package playing track change preserves current island host`() {
        assertTrue(
            PlaybackSmoothingPolicy.shouldPreserveHostAcrossTrackChange(
                currentPackage = "com.apple.android.music",
                nextPackage = "com.apple.android.music",
                effectivePlaying = true,
            ),
        )
    }

    @Test
    fun `paused or cross app track change performs a full reset`() {
        assertFalse(
            PlaybackSmoothingPolicy.shouldPreserveHostAcrossTrackChange(
                currentPackage = "com.apple.android.music",
                nextPackage = "com.apple.android.music",
                effectivePlaying = false,
            ),
        )
        assertFalse(
            PlaybackSmoothingPolicy.shouldPreserveHostAcrossTrackChange(
                currentPackage = "com.apple.android.music",
                nextPackage = "com.salt.music",
                effectivePlaying = true,
            ),
        )
    }

    @Test
    fun `empty lyrics for current track keeps already applied content`() {
        assertTrue(
            PlaybackSmoothingPolicy.emptyLyricsFallbackAction(
                appliedTrackKey = "com.apple.android.music\u001fcurrent",
                nextTrackKey = "com.apple.android.music\u001fcurrent",
                currentPackage = "com.apple.android.music",
                nextPackage = "com.apple.android.music",
                renderedPlaying = false,
            ) == PlaybackSmoothingPolicy.EmptyLyricsFallbackAction.KEEP_CURRENT_CONTENT,
        )
    }

    @Test
    fun `empty lyrics during playing same package switch preserves island host`() {
        assertTrue(
            PlaybackSmoothingPolicy.emptyLyricsFallbackAction(
                appliedTrackKey = null,
                nextTrackKey = "com.apple.android.music\u001fnext",
                currentPackage = "com.apple.android.music",
                nextPackage = "com.apple.android.music",
                renderedPlaying = true,
            ) == PlaybackSmoothingPolicy.EmptyLyricsFallbackAction.PRESERVE_HOST,
        )
    }

    @Test
    fun `empty lyrics while paused or switching apps performs full reset`() {
        assertTrue(
            PlaybackSmoothingPolicy.emptyLyricsFallbackAction(
                appliedTrackKey = null,
                nextTrackKey = "com.apple.android.music\u001fnext",
                currentPackage = "com.apple.android.music",
                nextPackage = "com.apple.android.music",
                renderedPlaying = false,
            ) == PlaybackSmoothingPolicy.EmptyLyricsFallbackAction.FULL_RESET,
        )
        assertTrue(
            PlaybackSmoothingPolicy.emptyLyricsFallbackAction(
                appliedTrackKey = null,
                nextTrackKey = "com.salt.music\u001fnext",
                currentPackage = "com.apple.android.music",
                nextPackage = "com.salt.music",
                renderedPlaying = true,
            ) == PlaybackSmoothingPolicy.EmptyLyricsFallbackAction.FULL_RESET,
        )
    }

    @Test
    fun `consumer playback mismatch forces state redispatch`() {
        assertTrue(
            PlaybackSmoothingPolicy.shouldDispatchPlaybackState(
                effectivePlaying = true,
                renderedPlaying = true,
                sinkPlaying = false,
            ),
        )
        assertFalse(
            PlaybackSmoothingPolicy.shouldDispatchPlaybackState(
                effectivePlaying = true,
                renderedPlaying = true,
                sinkPlaying = true,
            ),
        )
        assertFalse(
            PlaybackSmoothingPolicy.shouldDispatchPlaybackState(
                effectivePlaying = true,
                renderedPlaying = true,
                sinkPlaying = null,
            ),
        )
    }

    @Test
    fun `buffering keeps position loop alive until anchor advances again`() {
        assertTrue(
            PlaybackSmoothingPolicy.shouldDrivePositionLoop(
                appliedTrackKey = "com.apple.android.music\u001f1722205331",
                renderedPlaying = true,
            ),
        )
        assertFalse(
            PlaybackSmoothingPolicy.shouldDrivePositionLoop(
                appliedTrackKey = "com.apple.android.music\u001f1722205331",
                renderedPlaying = false,
            ),
        )
        assertFalse(
            PlaybackSmoothingPolicy.shouldDrivePositionLoop(
                appliedTrackKey = null,
                renderedPlaying = true,
            ),
        )
    }
}
