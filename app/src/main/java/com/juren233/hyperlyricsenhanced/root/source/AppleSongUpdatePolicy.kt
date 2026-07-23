package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.model.Song

internal object AppleSongUpdatePolicy {

    fun shouldPreserveCurrentLyrics(
        currentSong: Song,
        candidate: Song,
        sameTrack: Boolean
    ): Boolean = sameTrack &&
        !currentSong.lyrics.isNullOrEmpty() &&
        candidate.lyrics.isNullOrEmpty()

    fun shouldIgnoreMediaSessionCandidate(
        currentSong: Song?,
        candidate: Song,
        currentHasNativeLyrics: Boolean
    ): Boolean {
        if (!currentHasNativeLyrics || currentSong == null) return false
        if (!candidate.lyrics.isNullOrEmpty()) return false
        return sameTitleAndArtist(currentSong, candidate)
    }

    private fun sameTitleAndArtist(first: Song, second: Song): Boolean =
        normalize(first.name) == normalize(second.name) &&
            normalize(first.artist) == normalize(second.artist)

    private fun normalize(value: String?): String = value.orEmpty().trim().lowercase()
}
