package com.juren233.hyperlyricsenhanced.service.source

import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.lyric.ILyricProvider
import com.juren233.hyperlyricsenhanced.lyric.LyricSearchParams

class OnlineLyricSource(private val lyricProvider: ILyricProvider) : ServiceLyricSource {
    override val id = "online"
    override val displayName = "Online"

    override suspend fun getLyrics(data: SyncData): List<LrcLine>? {
        return try {
            lyricProvider.fetchLyrics(
                LyricSearchParams(
                    title = data.identityTitle,
                    artist = data.identityArtist,
                    album = data.identityAlbum,
                    packageName = data.currentPackageName,
                    duration = data.duration
                )
            )
        } catch (_: Exception) {
            null
        }
    }
}
