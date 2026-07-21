package com.lidesheng.hyperlyric.lyric

data class LrcLine(val startTimeMs: Long, val content: String)

data class LyricSearchParams(
    val title: String,
    val artist: String,
    val album: String,
    val packageName: String,
    val duration: Long
)

interface ILyricProvider {
    suspend fun fetchLyrics(params: LyricSearchParams): List<LrcLine>?
}
