package com.lidesheng.hyperlyric.service.source

import com.lidesheng.hyperlyric.common.lyric.LrcParser
import com.lidesheng.hyperlyric.lyric.LrcLine

class MetadataLrcLyricSource : ServiceLyricSource {
    override val id = "lyric"
    override val displayName = "LRC"

    override suspend fun getLyrics(data: SyncData): List<LrcLine>? {
        val lyricRaw = data.lyricRaw
        if (lyricRaw.isNullOrBlank()) return null
        return try {
            LrcParser.parse(lyricRaw)
        } catch (_: Exception) {
            null
        }
    }
}
