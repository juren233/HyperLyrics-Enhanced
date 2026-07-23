package com.juren233.hyperlyricsenhanced.root.island

/** Pure timing policy for the end-of-song next-track preview. */
internal object NextSongPreviewPolicy {
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
