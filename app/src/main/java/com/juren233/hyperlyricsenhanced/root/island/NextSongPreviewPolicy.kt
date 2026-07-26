package com.juren233.hyperlyricsenhanced.root.island

import com.juren233.hyperlyricsenhanced.common.RootConstants

/** Pure timing policy for the end-of-song next-track preview. */
internal object NextSongPreviewPolicy {
    fun reservesSlot(
        previewStyle: Int,
        targetIsLeft: Boolean?,
        slotIsLeft: Boolean
    ): Boolean {
        return when (previewStyle) {
            RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL -> true
            RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF ->
                targetIsLeft == slotIsLeft
            else -> false
        }
    }

    fun resolveTrackDuration(mediaDurationMs: Long, lyricDurationMs: Long): Long {
        return mediaDurationMs.takeIf { it > 0L }
            ?: lyricDurationMs.takeIf { it > 0L }
            ?: -1L
    }

    fun shouldShow(
        positionMs: Long,
        durationMs: Long,
        lastLyricStartMs: Long,
        lastSyllableEndMs: Long?,
        previewDurationMs: Long,
        force: Boolean
    ): Boolean {
        if (positionMs < 0L || durationMs <= 0L || previewDurationMs <= 0L) return false
        if (!force) {
            val previewStartMs = durationMs - previewDurationMs
            if (lastSyllableEndMs != null) {
                if (lastSyllableEndMs >= previewStartMs) return false
            } else if (lastLyricStartMs < 0L || lastLyricStartMs > previewStartMs) {
                return false
            }
        }
        return positionMs >= durationMs - previewDurationMs && positionMs < durationMs
    }
}
