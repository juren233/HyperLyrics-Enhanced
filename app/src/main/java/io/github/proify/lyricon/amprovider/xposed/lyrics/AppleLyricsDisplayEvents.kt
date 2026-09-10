/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

/** Preferences dispatch only presentation work, never source requests. */
internal enum class AppleLyricsDisplayPreference { TEXT, PRONUNCIATION, BLUR, FONT }

internal fun AppleLyricsSupplementHooks.onAppleLyricsDisplayPreferenceChanged(event: AppleLyricsDisplayPreference) {
    when (event) {
        AppleLyricsDisplayPreference.TEXT -> refreshAppleLyricsDisplay()
        AppleLyricsDisplayPreference.PRONUNCIATION -> {
            clearPendingApplePronunciationRenderPlans()
            refreshAppleLyricsSupplementPresentation()
        }
        AppleLyricsDisplayPreference.BLUR -> refreshAppleLyricsBlurEffect()
        AppleLyricsDisplayPreference.FONT -> refreshAppleSystemFont()
    }
}

/** One transition coordinates pronunciation invalidation and scroll state. */
internal fun AppleLyricsSupplementHooks.onAppleLyricsDisplayTrackChanged(songId: String?) {
    if (currentAppleLyricsSongId == songId) return
    clearPendingApplePronunciationRenderPlans()
    clearPendingAppleLyricsScrollRestore()
    presentationBinding.selectSong(songId)
    appleLyricsScrollSnapshot = null
    appleLyricsScrollSnapshotSongId = null
}

/** Native presentation has returned; retain the established restore-before-blur ordering. */
internal fun AppleLyricsSupplementHooks.onAppleLyricsPresentationCompleted(fragment: Any, songId: String) {
    restoreAppleLyricsScrollSnapshot(fragment, songId)
    scheduleAppleLyricsBlur(resolveAppleLyricsRecyclerView(fragment))
}
