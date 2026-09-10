/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

/** Candidate admission and disk-restore bookkeeping; no raw content or native pointers. */
internal class AppleMissingLyricsCandidateState {
    private val accepted = mutableSetOf<String>()
    private val available = mutableSetOf<String>()
    private val restoreAttempts = mutableSetOf<String>()
    private var restoreContentSongId: String? = null

    @Synchronized fun isAccepted(songId: String?): Boolean = songId in accepted
    @Synchronized fun wasAvailable(songId: String?): Boolean = songId in available
    @Synchronized fun accept(songId: String): Boolean = accepted.add(songId)
    @Synchronized fun markAvailable(songId: String): Boolean = available.add(songId)
    @Synchronized fun revokeAcceptance(songId: String) { accepted.remove(songId) }
    @Synchronized fun revokeAvailability(songId: String) { available.remove(songId) }
    @Synchronized fun forgetRestore(songId: String) { restoreAttempts.remove(songId) }
    @Synchronized fun invalidateRestoreWindow() { restoreContentSongId = null }
    @Synchronized fun resetRestoreAttempts() {
        restoreAttempts.clear()
        restoreContentSongId = null
    }

    /** Preserve the prior content-window retry policy, but test-and-record atomically. */
    @Synchronized fun beginRestore(songId: String, currentContentSongId: String?): Boolean {
        if (songId in restoreAttempts && restoreContentSongId == currentContentSongId) return false
        restoreAttempts.add(songId)
        restoreContentSongId = currentContentSongId
        return true
    }
}
