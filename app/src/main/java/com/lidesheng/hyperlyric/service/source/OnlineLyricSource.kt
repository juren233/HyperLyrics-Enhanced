package com.lidesheng.hyperlyric.service.source

import com.lidesheng.hyperlyric.BuildConfig
import com.lidesheng.hyperlyric.lyric.LrcLine
import com.lidesheng.hyperlyric.lyric.ILyricProvider
import com.lidesheng.hyperlyric.lyric.LyricSearchParams

class OnlineLyricSource(private val lyricProvider: ILyricProvider) : ServiceLyricSource {
    override val id = "online"
    override val displayName = "Online"

    override suspend fun getLyrics(data: SyncData): List<LrcLine>? {
        if (!BuildConfig.ONLINE_FEATURES_ENABLED) return null
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
