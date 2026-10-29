/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import android.content.Context
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceInfo
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.online.OnlineLyricTargeter
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.source.ttml.CatalogPlatform
import com.juren233.hyperlyricsenhanced.online.source.ttml.TtmlCatalogSources
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity

/**
 * 媒体会话补充歌词的取词顺序。TTML 目录源按平台歌曲 ID 直取，排在搜索类来源之前的先查，
 * 之后的作为搜索无结果时的补充。
 */
internal data class MediaSessionLyricsPlan(
    val leadingCatalogs: List<Source>,
    val searchSources: List<Source>,
    val trailingCatalogs: List<Source>,
) {
    companion object {
        fun of(sources: List<Source>): MediaSessionLyricsPlan {
            val leading = sources.takeWhile(Source::isTtmlCatalog)
            return MediaSessionLyricsPlan(
                leadingCatalogs = leading,
                searchSources = sources.filterNot(Source::isTtmlCatalog),
                trailingCatalogs = sources.drop(leading.size).filter(Source::isTtmlCatalog),
            )
        }
    }
}

internal class MediaSessionLyricsFetcher(private val context: Context) {
    suspend fun fetch(track: TrackIdentity, sources: List<Source>): Song? {
        val plan = MediaSessionLyricsPlan.of(sources)
        debug("开始取词: pkg=${track.packageName}, mediaId=${track.mediaId}, plan=$plan")
        catalogSong(track, plan.leadingCatalogs)?.let { return it }
        searchSong(track, plan.searchSources)?.let { return it }
        return catalogSong(track, plan.trailingCatalogs)
    }

    private suspend fun catalogSong(track: TrackIdentity, sources: List<Source>): Song? {
        if (sources.isEmpty()) return null
        val platform = CatalogPlatform.ofPlayerPackage(track.packageName) ?: return null
        val songId = CatalogPlatform.songIdOf(platform, track.mediaId) ?: return null
        for (source in sources) {
            val catalog = TtmlCatalogSources.of(source)?.takeIf { it.supports(platform) } ?: continue
            val match = catalog.findWordTimed(context, platform, songId).match
            if (match == null) {
                debug("目录源未命中: source=$source, platform=$platform, id=$songId")
                continue
            }
            val song = AppleMissingLyricsSongMapper.map(
                baseSong = baseSong(track),
                wordLines = match.parsed.wordLines,
                lrcLines = match.parsed.lrcLines,
                sourceInfo = AppleMissingLyricsSourceInfo(selectedSource = source.name, statuses = emptyList()),
            )
            if (song != null) {
                debug("目录源命中: source=$source, id=$songId, lines=${song.lyrics?.size ?: 0}")
                return song
            }
        }
        return null
    }

    private suspend fun searchSong(track: TrackIdentity, sources: List<Source>): Song? {
        if (sources.isEmpty()) return null
        // 在线源需要宿主 Application 的存储与会话；模块 Context 只用于资源。
        val lines = try {
            OnlineLyricTargeter.fetchBestLyric(
                context = context,
                pkgName = track.packageName,
                title = track.title,
                artist = track.artist,
                durationMs = track.durationMs,
                album = track.album,
                sourceOrder = sources,
                requireExactVersion = true,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (BuildConfig.DEBUG) HookLogger.w(TAG, "取词失败: pkg=${track.packageName}", failure)
            throw failure
        }
        debug("搜索取词完成: lines=${lines?.size ?: 0}")
        return lines?.let { OnlineFallbackSongMapper.map(baseSong(track), it) }
    }

    private fun baseSong(track: TrackIdentity) = Song(
        id = track.normalizedKey(),
        name = track.title,
        artist = track.artist,
        duration = track.durationMs,
    )

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) HookLogger.d(TAG, message)
    }

    private companion object {
        const val TAG = "MediaSessionOnlineFallback"
    }
}
