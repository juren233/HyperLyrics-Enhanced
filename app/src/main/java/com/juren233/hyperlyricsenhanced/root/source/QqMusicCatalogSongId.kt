/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.SongMetadataQueryPolicy
import com.juren233.hyperlyricsenhanced.online.source.qm.QmMetadataMatch
import com.juren233.hyperlyricsenhanced.online.source.qm.QmTrackDetail
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 给 TTML 目录源用的 QQ 音乐平台歌曲 ID。QQ 音乐手机版的会话 MEDIA_ID 是 `SongInfo.H2()`
 * 原值（20.9.5.8 反编译），本地歌是本地库编号（issue #62：1926 在线是另一首歌），不能直接
 * 当平台 ID 查目录。规则：
 * 1. 会话 ID 在在线库里就是这首歌 → 用会话 ID；
 * 2. 否则按歌名匹配：会话 ID 出现在原歌名 + 整串歌手的匹配结果里 → 仍用会话 ID；
 * 3. 否则取匹配结果里第一个核对通过的在线 ID（本地歌也能查到目录里的逐字歌词）。查询组合按
 *    [SongMetadataQueryPolicy.metadataQueries] 逐个尝试，放宽后的查询里会话 ID 也要过核对；
 * 4. 都确认不了（含网络异常）→ 不查目录。
 * 「是不是这首歌」由调用方传入，复用 OnlineLyricTargeter 的歌名/歌手规则。
 */
internal class QqMusicCatalogSongId(
    private val details: suspend (List<Long>) -> Map<Long, QmTrackDetail>,
    private val match: suspend (title: String, artist: String, durationMs: Long) -> QmMetadataMatch?,
    private val sameSong: (TrackIdentity, QmTrackDetail) -> Boolean,
) {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, String?>()

    suspend fun resolve(sessionId: String, track: TrackIdentity): String? = mutex.withLock {
        val key = "$sessionId|${track.title}|${track.artist}"
        if (cache.containsKey(key)) return@withLock cache[key]
        // 网络异常只让这一次不查目录，不写缓存，下次取词重新核对。
        val resolved = runCatching { resolveUncached(sessionId, track) }.getOrElse {
            return@withLock null
        }
        cache[key] = resolved
        while (cache.size > MAX_CACHED) cache.remove(cache.keys.first())
        resolved
    }

    private suspend fun resolveUncached(sessionId: String, track: TrackIdentity): String? {
        val numericId = sessionId.toLongOrNull()?.takeIf { it > 0L } ?: return null
        details(listOf(numericId))[numericId]
            ?.takeIf { sameSong(track, it) }
            ?.let { return sessionId }
        val checked = mutableSetOf(numericId)
        SongMetadataQueryPolicy.metadataQueries(track.title, track.artist).forEachIndexed { index, (title, artist) ->
            val matched = match(title, artist, track.durationMs) ?: return@forEachIndexed
            val ordered = matched.orderedIds
            if (index == 0 && numericId in ordered) return sessionId
            // 不同查询组合常返回重叠的候选，已核对过的不再查详情
            val unchecked = ordered.filter(checked::add)
            if (unchecked.isEmpty()) return@forEachIndexed
            val found = details(unchecked)
            unchecked.firstOrNull { id -> found[id]?.let { sameSong(track, it) } == true }
                ?.let { return it.toString() }
        }
        return null
    }

    private companion object {
        const val MAX_CACHED = 16
    }
}
