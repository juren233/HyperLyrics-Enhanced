/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.coroutines.ModuleDispatchers
import android.content.Context
import kotlinx.coroutines.async
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceInfo
import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.online.model.LyricsLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheSong
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheUsage
import com.juren233.hyperlyricsenhanced.online.source.ttml.lookup
import com.juren233.hyperlyricsenhanced.online.LyricApiProvider
import com.juren233.hyperlyricsenhanced.online.OnlineLyricTargeter
import com.juren233.hyperlyricsenhanced.online.cleanString
import com.juren233.hyperlyricsenhanced.online.hasCommonArtist
import com.juren233.hyperlyricsenhanced.online.isWordTimedOnlineLyrics
import com.juren233.hyperlyricsenhanced.online.isStrongTitleMatch
import com.juren233.hyperlyricsenhanced.online.splitArtists
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.source.ttml.CatalogPlatform
import com.juren233.hyperlyricsenhanced.online.source.ttml.TtmlCatalogSources
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity
import kotlinx.coroutines.withContext

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

/**
 * 搜索源结果映射成补充歌词（媒体会话补充、椒盐音乐在线兜底）：按整行映射（翻译、发音照旧），来源给了逐字
 * 歌词时再按行开始时间把逐字时间轴挂到对应行上。此前只按整行映射，QQ、酷狗等的逐字结果在这里被丢成整行。
 * [tag] 为缓存歌曲标签，结果连同正文来源一起登记，供时间轴记录「上次歌词」。
 */
internal object MediaSessionSearchSongMapper {
    fun map(baseSong: Song, tag: String?, outcome: OnlineLyricTargeter.FetchOutcome): Song? {
        val lines = outcome.lines?.takeIf { it.isNotEmpty() } ?: return null
        val song = OnlineFallbackSongMapper.map(
            OnlineLyricCacheUsage.filedUnder(baseSong, tag = tag, source = outcome.selectedSource),
            lines,
        ) ?: return null
        val wordLines = outcome.wordLines?.takeIf(::isWordTimedOnlineLyrics) ?: return song
        val wordLineAt = wordLines
            .filter { line -> line.start >= 0L && line.words.any { it.text.isNotBlank() } }
            .groupBy(LyricsLine::start)
            .mapValues { it.value.first() }
        return song.copy(
            lyrics = song.lyrics?.map { line ->
                val wordLine = wordLineAt[line.begin] ?: return@map line
                line.copy(
                    words = wordLine.words.map { LyricWord(begin = it.start, end = it.end, text = it.text) },
                    secondary = wordLine.secondaryWords.joinToString("") { it.text }.takeIf(String::isNotBlank),
                    secondaryWords = wordLine.secondaryWords.takeIf { it.isNotEmpty() }
                        ?.map { LyricWord(begin = it.start, end = it.end, text = it.text) },
                )
            },
        )
    }
}

internal class MediaSessionLyricsFetcher(private val context: Context) {
    private val qqCatalogSongId = QqMusicCatalogSongId(
        details = { ids -> withContext(ModuleDispatchers.IO) { LyricApiProvider.qmSongCatalog.details(ids) } },
        match = { title, artist, durationMs ->
            withContext(ModuleDispatchers.IO) {
                LyricApiProvider.qmSongCatalog.matchByMetadata(title, artist, durationMs)
            }
        },
        sameSong = { track, detail ->
            with(OnlineLyricTargeter) {
                isStrongTitleMatch(cleanString(context, track.title), cleanString(context, detail.name)) &&
                    hasCommonArtist(
                        splitArtists(track.artist).map { cleanString(context, it) },
                        detail.singers.map { cleanString(context, it) },
                    )
            }
        },
    )

    suspend fun fetch(track: TrackIdentity, sources: List<Source>): Song? {
        val plan = MediaSessionLyricsPlan.of(sources)
        debug("开始取词: pkg=${track.packageName}, mediaId=${track.mediaId}, plan=$plan")
        catalogSong(track, plan.leadingCatalogs)?.let { return it }
        searchSong(track, plan.searchSources)?.let { return it }
        return catalogSong(track, plan.trailingCatalogs)
    }

    /**
     * 「自动」来源：ttml 与搜索源同时开始，先到的通过 [onEarly] 先交出去，ttml 无论多晚到都优先。
     * 搜索源之间赛马（先到先得、逐字优先），中途的升级同样经 [onEarly] 交出去。
     * 播放器没有可寻址的歌曲 ID、或 ttml / 搜索源有一类为空时，ttml 不参与竞速，依次查；搜索源仍赛马。
     * 排在搜索源后面的 ttml 仍然是搜索源没有结果之后才查。
     */
    suspend fun fetchRacing(
        track: TrackIdentity,
        sources: List<Source>,
        onEarly: suspend (Song) -> Unit,
    ): Song? {
        val plan = MediaSessionLyricsPlan.of(sources)
        val platform = CatalogPlatform.ofPlayerPackage(track.packageName)
        val addressable = platform != null && CatalogPlatform.songIdOf(platform, track.mediaId) != null
        if (plan.leadingCatalogs.isEmpty() || plan.searchSources.isEmpty() || !addressable) {
            catalogSong(track, plan.leadingCatalogs)?.let { return it }
            searchSong(track, plan.searchSources, race = true, onProgress = onEarly)?.let { return it }
            return catalogSong(track, plan.trailingCatalogs)
        }
        debug("开始竞速取词: pkg=${track.packageName}, mediaId=${track.mediaId}, plan=$plan")
        val final = kotlinx.coroutines.coroutineScope {
            val ttmlJobs = plan.leadingCatalogs.map { source ->
                async { runCatching { catalogSong(track, listOf(source)) } }
            }
            val searchProgress = LyricsRace.SearchProgress<Song?>()
            val searchJob = async {
                runCatching {
                    searchSong(track, plan.searchSources, race = true) { searchProgress.report(it) }
                }
            }
            LyricsRace.run(
                ttml = ttmlJobs,
                search = searchJob,
                ttmlHit = { it != null },
                searchHit = { it != null },
                onEarlyTtml = { _, song -> song?.let { onEarly(it) } },
                onEarlySearch = { song -> song?.let { onEarly(it) } },
                searchProgress = searchProgress,
            )
        }
        return when (final) {
            is LyricsRace.Final.Ttml -> final.value
            is LyricsRace.Final.Search -> final.value ?: catalogSong(track, plan.trailingCatalogs)
        }
    }

    /**
     * 主源只有整行歌词时的逐字升级，与 Apple Music「原生逐行歌词转用三方逐字歌词」同一做法：按 [sources]
     * 的顺序逐个查，目录源按平台 ID 直取，搜索源单独检索且只收逐字结果，第一个命中即用。
     */
    suspend fun fetchWordTimed(track: TrackIdentity, sources: List<Source>): Song? {
        debug("开始逐字升级取词: pkg=${track.packageName}, mediaId=${track.mediaId}, sources=$sources")
        for (source in sources) {
            val song = if (source.isTtmlCatalog) {
                catalogSong(track, listOf(source))
            } else {
                wordTimedSearchSong(track, source)
            }
            if (song != null) return song
        }
        return null
    }

    private suspend fun wordTimedSearchSong(track: TrackIdentity, source: Source): Song? {
        val outcome = try {
            OnlineLyricTargeter.fetchBestLyricWithNearMiss(
                context = context,
                pkgName = track.packageName,
                title = track.title,
                artist = track.artist,
                durationMs = track.durationMs,
                album = track.album,
                sourceOrder = listOf(source),
                requireExactVersion = true,
                cacheSong = cacheSong(track),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (BuildConfig.DEBUG) HookLogger.w(TAG, "逐字升级搜索源失败: source=$source", failure)
            return null
        }
        val wordTimed = !outcome.lines.isNullOrEmpty() && isWordTimedOnlineLyrics(outcome.wordLines)
        debug("逐字升级搜索源查询: source=$source, 命中=${!outcome.lines.isNullOrEmpty()}, 逐字=$wordTimed")
        if (!wordTimed) return null
        return searchResultSong(track, outcome.copy(selectedSource = outcome.selectedSource ?: source))
    }

    private suspend fun catalogSong(track: TrackIdentity, sources: List<Source>): Song? {
        if (sources.isEmpty()) return null
        val platform = CatalogPlatform.ofPlayerPackage(track.packageName) ?: return null
        val sessionId = CatalogPlatform.songIdOf(platform, track.mediaId) ?: return null
        // QQ 音乐本地歌的会话 ID 是本地库编号（issue #62），核对后才能当平台 ID 用。
        val songId = if (platform == CatalogPlatform.QQ_MUSIC) {
            qqCatalogSongId.resolve(sessionId, track) ?: run {
                debug("目录源跳过: QQ 会话 ID 未能核对, id=$sessionId, title=${track.title}")
                return null
            }
        } else {
            sessionId
        }
        if (songId != sessionId) debug("目录源改用核对后的 QQ 歌曲 ID: $sessionId -> $songId")
        for (source in sources) {
            val catalog = TtmlCatalogSources.of(source)?.takeIf { it.supports(platform) } ?: continue
            val match = catalog.lookup(
                context = context,
                platform = platform,
                songId = songId,
                cacheSong = cacheSong(track),
            ).match
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

    /**
     * @param race 「自动」来源的搜索源赛马；[onProgress] 收到中途的升级，最终结果与最后一次升级相同时
     *   返回同一个实例，调用方据此不重复切换。
     */
    private suspend fun searchSong(
        track: TrackIdentity,
        sources: List<Source>,
        race: Boolean = false,
        onProgress: (suspend (Song) -> Unit)? = null,
    ): Song? {
        if (sources.isEmpty()) return null
        var lastProgress: Pair<List<LrcLine>, Song>? = null
        val reportProgress: suspend (OnlineLyricTargeter.FetchOutcome) -> Unit = { outcome ->
            val lines = outcome.lines
            val song = searchResultSong(track, outcome)
            if (lines != null && song != null) {
                lastProgress = lines to song
                onProgress?.invoke(song)
            }
        }
        // 在线源需要宿主 Application 的存储与会话；模块 Context 只用于资源。
        val outcome = try {
            OnlineLyricTargeter.fetchBestLyricWithNearMiss(
                context = context,
                pkgName = track.packageName,
                title = track.title,
                artist = track.artist,
                durationMs = track.durationMs,
                album = track.album,
                sourceOrder = sources,
                requireExactVersion = true,
                cacheSong = cacheSong(track),
                raceSources = race,
                onProgress = reportProgress.takeIf { onProgress != null },
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (BuildConfig.DEBUG) HookLogger.w(TAG, "取词失败: pkg=${track.packageName}", failure)
            throw failure
        }
        val lines = outcome.lines
        debug("搜索取词完成: lines=${lines?.size ?: 0}")
        lastProgress?.takeIf { it.first === lines }?.let { return it.second }
        return searchResultSong(track, outcome)
    }

    private fun searchResultSong(track: TrackIdentity, outcome: OnlineLyricTargeter.FetchOutcome): Song? =
        MediaSessionSearchSongMapper.map(baseSong(track), cacheSong(track).tag, outcome)

    /** The cache song every lookup for [track] is filed under, catalog and search alike. */
    private fun cacheSong(track: TrackIdentity) = OnlineLyricCacheSong.forQuery(
        track.packageName, track.title, track.artist, track.durationMs, track.album,
    )

    /**
     * Carries the cache song, so the timeline can record the sources as shown; the result mapping adds
     * the source of the lyric body ([MediaSessionSearchSongMapper], catalogs name theirs in the source metadata).
     */
    private fun baseSong(track: TrackIdentity) = OnlineLyricCacheUsage.filedUnder(
        Song(
            id = track.normalizedKey(),
            name = track.title,
            artist = track.artist,
            duration = track.durationMs,
        ),
        tag = cacheSong(track).tag,
    )

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) HookLogger.d(TAG, message)
    }

    private companion object {
        const val TAG = "MediaSessionOnlineFallback"
    }
}
