package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.model.Song

internal object AppleSongUpdatePolicy {

    /** Metadata-only updates must never replace selected lyrics or cross song identities. */
    fun refreshDisplayMetadata(current: Song?, incoming: Song?): Song? {
        if (current == null || incoming == null || current.id.isNullOrBlank() ||
            current.id != incoming.id) return null
        val name = incoming.name?.takeIf { it.isNotBlank() } ?: current.name
        val artist = incoming.artist?.takeIf { it.isNotBlank() } ?: current.artist
        if (name == current.name && artist == current.artist) return null
        return current.copy(name = name, artist = artist)
    }

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

    fun canStartFallbackFromMediaSession(
        currentSong: Song?,
        mediaSessionSong: Song,
        currentHasNativeLyrics: Boolean
    ): Boolean {
        if (currentSong == null || currentHasNativeLyrics) return false
        if (!currentSong.lyrics.isNullOrEmpty()) return false
        return sameTitleAndArtist(currentSong, mediaSessionSong)
    }

    private fun sameTitleAndArtist(first: Song, second: Song): Boolean =
        normalize(first.name) == normalize(second.name) &&
            normalize(first.artist) == normalize(second.artist)

    private fun normalize(value: String?): String = value.orEmpty().trim().lowercase()
}
