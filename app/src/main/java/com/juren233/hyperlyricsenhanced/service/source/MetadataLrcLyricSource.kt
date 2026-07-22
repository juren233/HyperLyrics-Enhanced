package com.juren233.hyperlyricsenhanced.service.source

import com.juren233.hyperlyricsenhanced.common.lyric.LrcParser
import com.juren233.hyperlyricsenhanced.lyric.LrcLine

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
