package com.lidesheng.hyperlyric.service.source

import com.lidesheng.hyperlyric.lyric.LrcLine

interface ServiceLyricSource {
    val id: String
    val displayName: String
    
    /**
     * 根据当前的媒体状态获取并解析滚动歌词行列表。
     * 如果不支持或者没有获取到，返回 null。
     */
    suspend fun getLyrics(data: SyncData): List<LrcLine>?
}
