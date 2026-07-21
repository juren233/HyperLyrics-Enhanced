package com.lidesheng.hyperlyric.lyric

import android.content.Context

class LyricProviderImpl(private val context: Context) : ILyricProvider {
    override suspend fun fetchLyrics(params: LyricSearchParams): List<LrcLine>? {
        // 离线版不提供在线搜索功能
        return null
    }
}
