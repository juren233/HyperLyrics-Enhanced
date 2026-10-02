/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

/**
 * Converts ExoPlayer's separate playback-intent and engine-state signals into one publication.
 *
 * Apple Music's public MediaSession and LocalMediaPlayerController both collapse buffering into
 * PAUSED. The original ExoMediaPlayer binary retains the missing distinction in
 * `onPlayerStateChanged(boolean playWhenReady, int state)`:
 *
 * - playWhenReady=true + BUFFERING/IDLE: playback surface stays active, timeline is frozen;
 * - playWhenReady=true + READY: playback surface is active and the timeline advances;
 * - playWhenReady=false, or ENDED: inactive/paused.
 */
internal object AppleExoPlaybackIntentPolicy {
    const val STATE_IDLE = 1
    const val STATE_BUFFERING = 2
    const val STATE_READY = 3
    const val STATE_ENDED = 4

    enum class Publication {
        PLAYING,
        BUFFERING,
        PAUSED,
    }

    data class Resolution(
        val publication: Publication,
        val playbackActive: Boolean,
        val advancesTimeline: Boolean,
    )

    enum class MediaSessionPauseDecision {
        KEEP,
        REWRITE_BUFFERING,
    }

    fun resolve(playWhenReady: Boolean, state: Int): Resolution {
        if (!playWhenReady || state == STATE_ENDED) {
            return Resolution(Publication.PAUSED, playbackActive = false, advancesTimeline = false)
        }
        return if (state == STATE_READY) {
            Resolution(Publication.PLAYING, playbackActive = true, advancesTimeline = true)
        } else {
            // IDLE is the short prepare window before BUFFERING. Both represent retained play
            // intent without a progressing media clock. Unknown non-ended states fail the same
            // way instead of momentarily collapsing the island during a player upgrade.
            Resolution(Publication.BUFFERING, playbackActive = true, advancesTimeline = false)
        }
    }

    /**
     * Apple Music may still publish PAUSED through its platform MediaSession while ExoPlayer
     * retains play intent. SystemUI's native media island consumes that public session directly,
     * so publishing BUFFERING only through Lyricon Central cannot keep the native island alive.
     *
     * Rewrite only a PAUSED publication backed by a currently active Exo signal. Explicit
     * pause/stop/release clears that signal before the platform call, and ENDED resolves inactive,
     * so real pause semantics remain untouched.
     */
    fun decideMediaSessionPause(
        incomingPaused: Boolean,
        activeResolution: Resolution?,
    ): MediaSessionPauseDecision = if (
        incomingPaused && activeResolution?.playbackActive == true
    ) {
        MediaSessionPauseDecision.REWRITE_BUFFERING
    } else {
        MediaSessionPauseDecision.KEEP
    }
}
