package com.juren233.hyperlyricsenhanced.online.model

interface SearchSource {
    val sourceType: Source
    suspend fun search(
        keyword: String,
        page: Int = 1,
        separator: String = "/",
        pageSize: Int = 20,
        durationMs: Long = 0L,
    ): List<SongSearchResult>

    /**
     * 按拆开的歌名/歌手/时长直接匹配的候选，不经过关键词搜索。不支持的来源返回空表。
     * 结果与 [search] 的结果一样交给调用方的同一套规则打分，不代表已被采纳。
     */
    suspend fun searchByMetadata(
        title: String,
        artist: String,
        durationMs: Long,
        separator: String = "/",
    ): List<SongSearchResult> = emptyList()

    suspend fun getLyrics(song: SongSearchResult): LyricsResult?
}
